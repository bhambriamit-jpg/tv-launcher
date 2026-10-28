package com.vibe.tvlauncher

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One launchable app. [image] is a banner when [isBanner] is true, otherwise a square icon. */
data class AppEntry(
    val pkg: String,
    val label: String,
    val image: ImageBitmap?,
    val isBanner: Boolean,
)

data class InputEntry(val id: String, val label: String)

data class Shortcut(val label: String, val action: String)

enum class Status { Normal, Pinned, Hidden }

val SHORTCUTS = listOf(
    Shortcut("Wi-Fi", Settings.ACTION_WIFI_SETTINGS),
    Shortcut("Display", Settings.ACTION_DISPLAY_SETTINGS),
    Shortcut("Sound", Settings.ACTION_SOUND_SETTINGS),
    Shortcut("Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS),
    Shortcut("All settings", Settings.ACTION_SETTINGS),
)

private fun Drawable.toImage(w: Int, h: Int): ImageBitmap? =
    try {
        toBitmap(w, h).asImageBitmap()
    } catch (_: Throwable) {
        null
    }

/** Lists launchable apps: TV apps first, then ordinary (sideloaded phone-style) apps. */
@Suppress("DEPRECATION")
suspend fun loadApps(ctx: Context): List<AppEntry> = withContext(Dispatchers.Default) {
    val pm = ctx.packageManager
    val own = ctx.packageName
    fun query(category: String) =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)

    val seen = HashSet<String>()
    val out = ArrayList<AppEntry>()
    for ((list, isTv) in listOf(query(Intent.CATEGORY_LEANBACK_LAUNCHER) to true, query(Intent.CATEGORY_LAUNCHER) to false)) {
        for (ri in list) {
            val pkg = ri.activityInfo.packageName
            if (pkg == own || !seen.add(pkg)) continue
            val label = ri.loadLabel(pm).toString()
            val banner: Drawable? = if (isTv) ri.activityInfo.loadBanner(pm) else null
            val image = if (banner != null) banner.toImage(480, 270) else ri.loadIcon(pm).toImage(192, 192)
            out += AppEntry(pkg, label, image, banner != null && image != null)
        }
    }
    out.sortedBy { it.label.lowercase() }
}

/** HDMI / AV passthrough inputs, so input switching keeps working without the stock home screen. */
fun loadInputs(ctx: Context): List<InputEntry> =
    try {
        val tv = ctx.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
        tv?.tvInputList
            ?.filter { it.isPassthroughInput }
            ?.map { info ->
                val label = (info.loadCustomLabel(ctx) ?: info.loadLabel(ctx)).toString()
                InputEntry(info.id, label)
            }
            ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

fun openSettingsAction(ctx: Context, action: String) {
    try {
        ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Throwable) {
        toast(ctx, "This TV has no screen for that setting")
    }
}

fun launchInput(ctx: Context, id: String) {
    try {
        val uri = TvContract.buildChannelUriForPassthroughInput(id)
        ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Throwable) {
        toast(ctx, "Could not switch to that input")
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("vibe", Context.MODE_PRIVATE)

    private fun getList(key: String): List<String> =
        (sp.getString(key, "") ?: "").split('\n').filter { it.isNotBlank() }

    private fun putList(key: String, v: List<String>) {
        sp.edit().putString(key, v.joinToString("\n")).apply()
    }

    var pinned: List<String>
        get() = getList("pinned")
        set(v) = putList("pinned", v)

    var hidden: List<String>
        get() = getList("hidden")
        set(v) = putList("hidden", v)

    var recent: List<String>
        get() = getList("recent")
        set(v) = putList("recent", v)

    var animated: Boolean
        get() = sp.getBoolean("animated", true)
        set(v) {
            sp.edit().putBoolean("animated", v).apply()
        }
}

/** All launcher state. Compose reads these properties, so changes redraw the screen. */
class LauncherState(private val app: Context) {
    private val prefs = Prefs(app)

    var apps by mutableStateOf<List<AppEntry>>(emptyList())
        private set
    var inputs by mutableStateOf<List<InputEntry>>(emptyList())
        private set
    var pinned by mutableStateOf(prefs.pinned)
        private set
    var hidden by mutableStateOf(prefs.hidden.toSet())
        private set
    var recent by mutableStateOf(prefs.recent)
        private set
    var animated by mutableStateOf(prefs.animated)
        private set
    var homePresses by mutableIntStateOf(0)
        private set

    val visibleApps: List<AppEntry> get() = apps.filter { it.pkg !in hidden }
    val favorites: List<AppEntry>
        get() = pinned.mapNotNull { p -> apps.firstOrNull { it.pkg == p && it.pkg !in hidden } }
    val others: List<AppEntry> get() = visibleApps.filter { it.pkg !in pinned }
    val continueApp: AppEntry?
        get() = recent.firstNotNullOfOrNull { p -> visibleApps.firstOrNull { it.pkg == p } }
            ?: favorites.firstOrNull()
            ?: visibleApps.firstOrNull()

    suspend fun refresh() {
        apps = loadApps(app)
        inputs = loadInputs(app)
    }

    fun onHomePressed() {
        homePresses++
    }

    fun statusOf(pkg: String): Status = when {
        pkg in pinned -> Status.Pinned
        pkg in hidden -> Status.Hidden
        else -> Status.Normal
    }

    /** Normal -> Pinned -> Hidden -> Normal. */
    fun cycle(pkg: String) {
        when (statusOf(pkg)) {
            Status.Normal -> {
                pinned = pinned + pkg
                prefs.pinned = pinned
            }
            Status.Pinned -> {
                pinned = pinned - pkg
                hidden = hidden + pkg
                prefs.pinned = pinned
                prefs.hidden = hidden.toList()
            }
            Status.Hidden -> {
                hidden = hidden - pkg
                prefs.hidden = hidden.toList()
            }
        }
    }

    fun toggleAnimated() {
        animated = !animated
        prefs.animated = animated
    }

    fun launch(pkg: String) {
        val pm = app.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            toast(app, "Can't open that app")
            return
        }
        try {
            app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            recent = (listOf(pkg) + recent.filter { it != pkg }).take(12)
            prefs.recent = recent
        } catch (_: Throwable) {
            toast(app, "Can't open that app")
        }
    }

    fun openInput(id: String) = launchInput(app, id)

    fun openSettings(action: String) = openSettingsAction(app, action)
}
