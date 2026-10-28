#!/usr/bin/env python3
"""tvctl: set up Vibe Launcher on an Android / Google TV over wireless adb.

Standard library only. Needs `adb` (Android platform-tools) and Python 3.8+.
Run it from a laptop, or from a phone with Termux, on the same wifi as the TV.

    python3 tvctl.py pair 192.168.1.50:37123 123456
    python3 tvctl.py connect 192.168.1.50:41999
    python3 tvctl.py install app-debug.apk
    python3 tvctl.py audit
    python3 tvctl.py activate
    python3 tvctl.py debloat            # dry run: shows what it would do
    python3 tvctl.py debloat --apply
    python3 tvctl.py restore            # undo everything
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
STATE_FILE = HERE / "tvctl-state.json"
DEFAULT_LIST = HERE / "debloat-list.txt"

LAUNCHER_PKG = "com.vibe.tvlauncher"
LAUNCHER_COMPONENT = LAUNCHER_PKG + "/.MainActivity"

# Seconds to wait after test-launching the launcher before checking it is still running.
SMOKE_WAIT = float(os.environ.get("TVCTL_SMOKE_WAIT", "4"))

# Stock home screens. `activate` disables these (after the new launcher is
# installed) and `restore` always tries to switch them back on. setupwraith is
# the setup-wizard package that otherwise re-enables the stock launcher.
STOCK_HOME = [
    "com.google.android.apps.tv.launcherx",
    "com.google.android.tvlauncher",
    "com.google.android.leanbacklauncher",
    "com.google.android.tungsten.setupwraith",
]

# Never touched, even with --force: the system would stop working.
HARD_PROTECTED_EXACT = {"android"}
HARD_PROTECTED_PREFIX = (
    "android.",
    "com.android.",
    "com.google.android.gms",
    "com.google.android.gsf",
    "com.google.android.webview",
    "com.google.android.permissioncontroller",
    "com.google.android.packageinstaller",
    "com.google.android.inputmethod",
    "com.google.android.ext.",
    "com.google.android.modulemetadata",
    "com.google.android.networkstack",
    "com.google.android.tts",
)

# Skipped unless --force: may run the tuner, HDMI inputs, remote or updates.
SOFT_PROTECTED_PREFIX = (
    "com.sony.",
    "com.mediatek.",
    "com.google.android.tv.",
    "com.google.android.apps.tv.",
)


# --------------------------------------------------------------------------------------
# helpers
# --------------------------------------------------------------------------------------

def die(msg: str, code: int = 1) -> "None":
    sys.stdout.flush()
    print("error: " + msg, file=sys.stderr)
    sys.exit(code)


def find_adb(explicit):
    if explicit:
        return explicit
    found = shutil.which("adb")
    if found:
        return found
    local = HERE / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
    if local.exists():
        return str(local)
    die(
        "adb not found. Install Android platform-tools "
        "(https://developer.android.com/tools/releases/platform-tools), "
        "or on Termux run: pkg install android-tools. "
        "Then re-run, or pass --adb /path/to/adb."
    )


def load_state() -> dict:
    if STATE_FILE.exists():
        try:
            data = json.loads(STATE_FILE.read_text())
            data.setdefault("device", None)
            data.setdefault("changes", [])
            return data
        except (ValueError, OSError):
            pass
    return {"device": None, "changes": []}


def save_state(state: dict) -> None:
    STATE_FILE.write_text(json.dumps(state, indent=2))


def record_change(state: dict, pkg: str, mode: str) -> None:
    state["changes"] = [c for c in state["changes"] if c["pkg"] != pkg]
    state["changes"].append({"pkg": pkg, "mode": mode, "time": time.strftime("%Y-%m-%d %H:%M:%S")})


def confirm(prompt: str, assume_yes: bool) -> bool:
    if assume_yes:
        return True
    try:
        return input(prompt + " [y/N] ").strip().lower() in ("y", "yes")
    except EOFError:
        return False


class Adb:
    def __init__(self, path: str, device):
        self.path = path
        self.device = device

    def raw(self, *args, timeout: int = 90) -> subprocess.CompletedProcess:
        cmd = [self.path]
        if self.device:
            cmd += ["-s", self.device]
        cmd += list(args)
        try:
            return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        except FileNotFoundError:
            die("could not run adb at: " + self.path)
        except subprocess.TimeoutExpired:
            die("adb timed out. Is the TV awake and still connected? Run `connect` again.")

    def text(self, *args, timeout: int = 90) -> str:
        r = self.raw(*args, timeout=timeout)
        return ((r.stdout or "") + (r.stderr or "")).strip()

    def shell(self, *args, timeout: int = 90) -> str:
        return self.text("shell", *args, timeout=timeout)


def online_devices(adb_path: str):
    try:
        r = subprocess.run([adb_path, "devices"], capture_output=True, text=True, timeout=30)
    except (FileNotFoundError, subprocess.TimeoutExpired):
        return []
    devices = []
    for line in (r.stdout or "").splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])
    return devices


def get_adb(args) -> Adb:
    path = find_adb(args.adb)
    device = args.device or load_state().get("device")
    if not device:
        devs = online_devices(path)
        if len(devs) == 1:
            device = devs[0]
        else:
            die("no TV selected. Run `connect HOST:PORT` first (see README).")
    adb = Adb(path, device)
    if device not in online_devices(path):
        die("TV %s is not connected. Run `connect HOST:PORT` again (the port changes when "
            "wireless debugging is turned off and on)." % device)
    return adb


def package_set(adb: Adb, *flags: str) -> set:
    out = adb.shell("pm", "list", "packages", *flags)
    return {line[len("package:"):].strip() for line in out.splitlines() if line.startswith("package:")}


def is_installed(adb: Adb, pkg: str) -> bool:
    return "package:" in adb.shell("pm", "path", pkg)


def is_hard_protected(pkg: str) -> bool:
    return pkg in HARD_PROTECTED_EXACT or pkg.startswith(HARD_PROTECTED_PREFIX)


def classify(pkg: str) -> str:
    if pkg == LAUNCHER_PKG:
        return "own"
    if pkg in STOCK_HOME:
        return "stock-home"
    if is_hard_protected(pkg):
        return "protected"
    if pkg.startswith(SOFT_PROTECTED_PREFIX):
        return "review"
    return "other"


def disable_pkg(adb: Adb, pkg: str):
    out = adb.shell("pm", "disable-user", "--user", "0", pkg)
    return ("new state: disabled" in out), out


def enable_pkg(adb: Adb, pkg: str):
    out = adb.shell("pm", "enable", "--user", "0", pkg)
    if "new state: enabled" in out:
        return True, out
    out2 = adb.shell("cmd", "package", "install-existing", "--user", "0", pkg)
    return ("installed for user" in out2), out2


def uninstall_keep_data(adb: Adb, pkg: str):
    out = adb.shell("pm", "uninstall", "-k", "--user", "0", pkg)
    return ("Success" in out), out


def smoke_test(adb: Adb) -> bool:
    """Start the launcher and check it is still running a few seconds later (i.e. it did not crash)."""
    adb.shell("am", "start", "-n", LAUNCHER_COMPONENT)
    time.sleep(SMOKE_WAIT)
    out = adb.shell("pidof", LAUNCHER_PKG).strip()
    if out.replace(" ", "").isdigit():
        return True
    if "not found" in out.lower() or "inaccessible" in out.lower():
        # Older builds without pidof: fall back to the process list.
        return LAUNCHER_PKG in adb.shell("ps", "-A")
    return False


def parse_list(path: Path):
    pkgs = []
    for line in path.read_text().splitlines():
        line = line.split("#", 1)[0].strip()
        if line:
            pkgs.append(line)
    return pkgs


# --------------------------------------------------------------------------------------
# commands
# --------------------------------------------------------------------------------------

def cmd_pair(args):
    path = find_adb(args.adb)
    code = args.code or input("Pairing code shown on the TV: ").strip()
    r = subprocess.run([path, "pair", args.hostport, code], capture_output=True, text=True, timeout=60)
    out = ((r.stdout or "") + (r.stderr or "")).strip()
    print(out)
    if "Successfully paired" not in out:
        die("pairing failed. On the TV open Developer options > Wireless debugging > "
            "Pair device with pairing code, and use the IP:port and code shown there.")
    print("\nPaired. Now run: python3 tvctl.py connect HOST:PORT")
    print("(use the main IP address and port shown on the Wireless debugging screen, "
          "which is different from the pairing port)")


def cmd_connect(args):
    path = find_adb(args.adb)
    r = subprocess.run([path, "connect", args.hostport], capture_output=True, text=True, timeout=60)
    out = ((r.stdout or "") + (r.stderr or "")).strip()
    print(out)
    low = out.lower()
    if "connected to" not in low or "failed" in low or "cannot" in low:
        die("could not connect. Check the TV is on the same wifi and wireless debugging is on, "
            "and that you used the connect port, not the pairing port.")
    state = load_state()
    state["device"] = args.hostport
    save_state(state)
    print("Saved. Other commands will use this TV automatically.")


def cmd_status(args):
    adb = get_adb(args)
    installed = package_set(adb)
    disabled = package_set(adb, "-d")
    print("TV:               " + adb.device)
    print("Vibe Launcher:    " + ("installed" if LAUNCHER_PKG in installed else "NOT installed"))
    print("Stock home apps:")
    for pkg in STOCK_HOME:
        if pkg not in installed:
            state = "not present"
        elif pkg in disabled:
            state = "disabled"
        else:
            state = "enabled"
        print("  %-48s %s" % (pkg, state))
    res = adb.shell("cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN",
                    "-c", "android.intent.category.HOME")
    print("Home button opens: " + (res.splitlines()[-1] if res else "unknown"))
    changes = load_state()["changes"]
    print("Packages disabled by this tool: %d" % len(changes))


def cmd_audit(args):
    adb = get_adb(args)
    installed = package_set(adb)
    disabled = package_set(adb, "-d")
    system = package_set(adb, "-s")
    third = package_set(adb, "-3")
    wanted = set(parse_list(Path(args.list))) if Path(args.list).exists() else set()

    rows = []
    for pkg in sorted(installed):
        kind = classify(pkg)
        where = "user-app" if pkg in third else ("system" if pkg in system else "other")
        rows.append((pkg, kind, where, "disabled" if pkg in disabled else "enabled", pkg in wanted))

    out_path = Path(args.out) if args.out else HERE / ("audit-%s.txt" % time.strftime("%Y%m%d-%H%M%S"))
    lines = ["%-60s %-11s %-9s %s" % ("package", "class", "type", "state")]
    for pkg, kind, where, state, listed in rows:
        lines.append("%-60s %-11s %-9s %s%s" % (pkg, kind, where, state, "  [in debloat list]" if listed else ""))
    out_path.write_text("\n".join(lines) + "\n")

    def show(title, items):
        print("\n" + title)
        if not items:
            print("  (none)")
        for pkg, kind, where, state, listed in items:
            print("  %-56s %s" % (pkg, state))

    print("%d packages on the TV (%d user apps, %d disabled)." % (len(installed), len(third), len(disabled)))
    show("Stock home screen packages (handled by `activate`):", [r for r in rows if r[1] == "stock-home"])
    show("In your debloat list and installed (handled by `debloat`):", [r for r in rows if r[4]])
    show("Sony / MediaTek / TV-platform packages (review before touching):", [r for r in rows if r[1] == "review"])
    show("Apps you installed yourself:", [r for r in rows if r[2] == "user-app"])
    print("\nFull list saved to: " + str(out_path))
    print("Send that file (or the sections above) back and the debloat list gets tuned to your TV.")


def cmd_install(args):
    adb = get_adb(args)
    apk = Path(args.apk)
    if not apk.exists():
        die("APK not found: " + str(apk))
    out = adb.text("install", "-r", str(apk), timeout=300)
    print(out)
    if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in out or "signatures do not match" in out:
        print("\nA copy signed with a different key is already installed.")
        if not confirm("Uninstall it and install this one? (resets pinned/hidden apps)", args.yes):
            die("left the old copy in place.")
        adb.text("uninstall", LAUNCHER_PKG)
        out = adb.text("install", str(apk), timeout=300)
        print(out)
    if "Success" not in out:
        die("install failed. See the message above.")
    print("\nInstalled. Next: python3 tvctl.py audit, then python3 tvctl.py activate")


def cmd_activate(args):
    adb = get_adb(args)
    if not is_installed(adb, LAUNCHER_PKG):
        die("Vibe Launcher is not installed on the TV. Run `install app-debug.apk` first. "
            "The stock home screen is only disabled after the new one is installed.")
    installed = package_set(adb)
    targets = [p for p in STOCK_HOME if p in installed]
    print("Stock home packages found on this TV:")
    for pkg in targets or ["(none)"]:
        print("  " + pkg)
    if not targets:
        print("Nothing to disable. Press Home on the remote and choose Vibe Launcher > Always.")
    elif not confirm("Disable them and make Vibe Launcher the home screen?", args.yes):
        die("cancelled. Nothing was changed.")

    print("Test-launching Vibe Launcher before touching the stock home screen...")
    if not smoke_test(adb):
        die("Vibe Launcher did not stay running on the TV, so the stock home screen was left alone "
            "and nothing was changed. To see why, run: adb logcat -d -s AndroidRuntime:E  "
            "and send me the output.")
    print("Launcher started and stayed running.")

    out = adb.shell("cmd", "package", "set-home-activity", LAUNCHER_COMPONENT)
    if out and any(w in out.lower() for w in ("error", "exception", "unknown", "usage")):
        print("This TV can't set the default home from adb (that's fine, checked below instead).")
    else:
        print("Set Vibe Launcher as the default home.")

    state = load_state()
    done = []
    for pkg in targets:
        ok, msg = disable_pkg(adb, pkg)
        print("  %-48s %s" % (pkg, "disabled" if ok else "FAILED: " + msg[:80]))
        if ok:
            record_change(state, pkg, "disable")
            done.append(pkg)
    save_state(state)

    time.sleep(1)
    res = adb.shell("cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN",
                    "-c", "android.intent.category.HOME")
    if LAUNCHER_PKG in res:
        print("\nHome button now opens Vibe Launcher.")
    elif "ResolverActivity" in res:
        print("\nThe TV will ask which home app to use. Press Home on the remote, pick "
              "Vibe Launcher and choose Always.")
    else:
        print("\nHome does not resolve to Vibe Launcher (" + (res.splitlines()[-1] if res else "no answer")
              + "). Rolling back so the TV keeps a working home screen.")
        for pkg in done:
            enable_pkg(adb, pkg)
        state["changes"] = [c for c in state["changes"] if c["pkg"] not in done]
        save_state(state)
        die("rolled back. Nothing is disabled now. Send the output above for a fix.")
    adb.shell("am", "start", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
    print("To undo at any time: python3 tvctl.py restore")


def cmd_debloat(args):
    adb = get_adb(args)
    list_path = Path(args.list)
    if not list_path.exists():
        die("list file not found: " + str(list_path))
    wanted = parse_list(list_path)
    installed = package_set(adb)
    disabled = package_set(adb, "-d")

    plan = []
    print("%-52s %s" % ("package", "decision"))
    for pkg in wanted:
        kind = classify(pkg)
        if kind in ("own", "stock-home"):
            verdict = "skip (home screen: use `activate`)"
        elif kind == "protected":
            verdict = "REFUSED (core system package)"
        elif kind == "review" and not args.force:
            verdict = "skip (Sony/MediaTek/TV platform: needs --force)"
        elif pkg not in installed:
            verdict = "skip (not installed)"
        elif args.mode == "disable" and pkg in disabled:
            verdict = "skip (already disabled)"
        else:
            verdict = "WILL " + args.mode
            plan.append(pkg)
        print("%-52s %s" % (pkg, verdict))

    if not plan:
        print("\nNothing to do.")
        return
    if not args.apply:
        print("\nDry run only. %d package(s) would change. Re-run with --apply to do it." % len(plan))
        return
    if not confirm("\nApply %d change(s)?" % len(plan), args.yes):
        die("cancelled. Nothing was changed.")

    state = load_state()
    for pkg in plan:
        if args.mode == "disable":
            ok, msg = disable_pkg(adb, pkg)
        else:
            ok, msg = uninstall_keep_data(adb, pkg)
        print("  %-48s %s" % (pkg, "done" if ok else "FAILED: " + msg[:80]))
        if ok:
            record_change(state, pkg, args.mode)
    save_state(state)
    print("\nFinished. Some TVs re-enable apps after a reboot; if ads come back, run this again.")
    print("To undo: python3 tvctl.py restore")


def cmd_restore(args):
    adb = get_adb(args)
    state = load_state()
    changes = state["changes"]
    if args.pkg:
        changes = [c for c in changes if c["pkg"] == args.pkg]
        if not changes:
            changes = [{"pkg": args.pkg, "mode": "disable"}]
    restored = []

    for c in changes:
        ok, msg = enable_pkg(adb, c["pkg"])
        print("  %-48s %s" % (c["pkg"], "restored" if ok else "FAILED: " + msg[:80]))
        if ok:
            restored.append(c["pkg"])

    if not args.pkg:
        # Always try the stock home screen, even if the state file was lost.
        for pkg in STOCK_HOME:
            if pkg in restored:
                continue
            out = adb.shell("pm", "enable", "--user", "0", pkg)
            if "new state: enabled" in out:
                print("  %-48s %s" % (pkg, "restored"))
                restored.append(pkg)

    state["changes"] = [c for c in state["changes"] if c["pkg"] not in restored]
    save_state(state)
    print("\nRestored %d package(s). Press Home on the remote; if the TV asks, pick the home "
          "app you want and choose Always." % len(restored))


# --------------------------------------------------------------------------------------
# entry point
# --------------------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="tvctl.py", description="Set up Vibe Launcher on a TV over wireless adb.")
    p.add_argument("--adb", help="path to the adb binary (default: found on PATH)")
    p.add_argument("--device", help="HOST:PORT of the TV (default: the one saved by `connect`)")
    p.add_argument("-y", "--yes", action="store_true", help="do not ask for confirmation")
    sub = p.add_subparsers(dest="command", required=True)

    s = sub.add_parser("pair", help="pair with the TV using the 6-digit code")
    s.add_argument("hostport", help="pairing IP:port shown on the TV")
    s.add_argument("code", nargs="?", help="6-digit pairing code")
    s.set_defaults(fn=cmd_pair)

    s = sub.add_parser("connect", help="connect to the TV")
    s.add_argument("hostport", help="IP:port from the Wireless debugging screen")
    s.set_defaults(fn=cmd_connect)

    s = sub.add_parser("status", help="show launcher and stock-home state")
    s.set_defaults(fn=cmd_status)

    s = sub.add_parser("audit", help="list everything installed and flag what to review")
    s.add_argument("--list", default=str(DEFAULT_LIST))
    s.add_argument("--out", help="where to save the full list")
    s.set_defaults(fn=cmd_audit)

    s = sub.add_parser("install", help="install the launcher APK")
    s.add_argument("apk")
    s.set_defaults(fn=cmd_install)

    s = sub.add_parser("activate", help="make Vibe Launcher the home screen (safe order, auto rollback)")
    s.set_defaults(fn=cmd_activate)

    s = sub.add_parser("debloat", help="disable the packages in the list (dry run unless --apply)")
    s.add_argument("--list", default=str(DEFAULT_LIST))
    s.add_argument("--apply", action="store_true")
    s.add_argument("--mode", choices=["disable", "uninstall"], default="disable",
                   help="disable (default) or `pm uninstall -k --user 0`; both are reversible")
    s.add_argument("--force", action="store_true", help="also allow Sony/MediaTek/TV platform packages")
    s.set_defaults(fn=cmd_debloat)

    s = sub.add_parser("restore", help="undo changes and bring the stock home screen back")
    s.add_argument("--pkg", help="restore just this package")
    s.set_defaults(fn=cmd_restore)
    return p


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    args.fn(args)
    return 0


if __name__ == "__main__":
    sys.exit(main())
