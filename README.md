# Vibe Launcher

A clean, ad-free, Apple TV-style home screen for Android / Google TV, plus a small tool that
swaps it in for the stock home screen over wireless adb and removes the bloat.

Built for a Sony Bravia 2 (K-43S20, Google TV), but it works on any Android TV 8+ device.

**What you get**

- Big clock and greeting, a "Jump back in" card for the app you used last
- Favorites row, then all your apps in rows. Cards grow and glow when selected
- A wallpaper that changes colour with the time of day and drifts slowly (can be set to still)
- An **Inputs** row (HDMI 1, HDMI 2...) so input switching keeps working without the stock home
- A **Settings** row with Wi-Fi, Display, Sound, Bluetooth and all settings, so you are never locked out
- **Manage apps**: press OK on an app to cycle normal, pinned to Favorites, hidden
- No ads, no recommendation feeds, no network access, no accounts

## 1. Put the code on GitHub

Upload the contents of this folder to your repo. The `.github` folder must go too (it holds the
build). On a Mac, press Cmd+Shift+. in Finder to show hidden folders before dragging.

## 2. Build the APK (no Android Studio needed)

1. Open the repo on GitHub, then the **Actions** tab.
2. Choose **Build APK**, then **Run workflow**. (It also runs on every push to `main`.)
3. When it turns green, open the run and download **vibe-launcher-apk** at the bottom. Unzip it
   to get `app-debug.apk`.

To publish it on the Releases page instead, push a tag such as `v0.1.0`.

If the build fails, copy the red error text and send it over; it is usually a one-line fix.

## 3. Get adb

- **Windows / Mac / Linux:** download Android platform-tools and put the folder on your PATH, or
  unzip it into `scripts/platform-tools/`. You also need Python 3.8+.
- **Android phone:** install Termux, then `pkg install android-tools python`.

The computer or phone must be on the **same wifi** as the TV.

## 4. Connect to the TV

On the TV: Settings > System > Developer options > **Wireless debugging** > on.
Then tap **Pair device with pairing code**. You will see an IP:port and a 6-digit code.

```
cd scripts
python3 tvctl.py pair 192.168.1.50:37123 123456      # the pairing IP:port and code
python3 tvctl.py connect 192.168.1.50:41999          # the MAIN IP:port on the Wireless debugging screen
```

The two ports are different. The connect port changes each time wireless debugging is switched
off and on.

## 5. Install, switch over, remove bloat

```
python3 tvctl.py install app-debug.apk
python3 tvctl.py audit          # lists everything on the TV, flags what to review
python3 tvctl.py activate       # makes Vibe Launcher the home screen
python3 tvctl.py debloat        # dry run: shows what it would disable
python3 tvctl.py debloat --apply
```

Run `audit` and send the saved file back so the debloat list can be tuned to your exact TV;
the starter list only has well-known Google recommendation and media packages.

## Safety

- **Order matters.** `activate` refuses to run unless the launcher is already installed. It then
  test-launches Vibe Launcher and only carries on if it is still running a few seconds later; if
  it crashed, nothing is changed. Only after that does it disable the stock home, and it then
  checks that the Home button opens Vibe Launcher. If it does not, it switches the stock home
  back on by itself.
- **Everything is reversible.** The tool only disables apps for the TV's main user (or, with
  `--mode uninstall`, runs `pm uninstall -k --user 0`, which keeps the app in the system
  partition). Nothing is deleted.
- **Undo anytime:** `python3 tvctl.py restore` re-enables everything the tool changed and always
  brings the stock home screen back, even if its state file is lost.
- **Protected packages.** Core Android and Google Play services packages are never touched.
  Sony, MediaTek and TV-platform packages are skipped unless you pass `--force`, because some of
  them run the tuner, HDMI inputs and remote.
- **Last resort:** if the TV is ever stuck with no home screen, a factory reset fixes it.
  A gentler alternative is to skip `activate` and use the Button Mapper app to point the
  remote's Home button at Vibe Launcher; the TV then still boots into the stock home.
- Some TVs re-enable apps after a reboot or update. If ads come back, run `debloat --apply` again.
- Disabling Google apps can stop some remote buttons (for example a dedicated Assistant or
  YouTube button) from working. `restore --pkg <package>` brings back a single app.

## Customising

- Time-of-day colours: the `STOPS` list at the top of the wallpaper section in
  `app/src/main/java/com/vibe/tvlauncher/Home.kt`.
- Card sizes and row layout: `HomeScreen` in the same file. Rows hold 8 apps each.
- Settings shortcuts: `SHORTCUTS` in `Data.kt`.

## About the signing key

`app/debug.keystore` is a throwaway key committed on purpose, so every build is signed the same
way and `tvctl.py install` can update the launcher in place without losing your pinned apps. It
is fine for a launcher on your own TV. Do not reuse it for anything you publish.
