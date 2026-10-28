package com.vibe.tvlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// Root
// ---------------------------------------------------------------------------------------------

@Composable
fun HomeRoot(state: LauncherState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var manage by remember { mutableStateOf(false) }

    // Keep the app list fresh when apps are installed, removed or updated.
    DisposableEffect(Unit) {
        var job: Job? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                job?.cancel()
                job = scope.launch {
                    delay(800)
                    state.refresh()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { ctx.unregisterReceiver(receiver) }
    }
    LaunchedEffect(Unit) { state.refresh() }

    // Pressing Home while already on the launcher closes the editor and jumps to the top.
    LaunchedEffect(state.homePresses) {
        if (state.homePresses > 0) manage = false
    }

    BackHandler(enabled = manage) { manage = false }
    BackHandler(enabled = !manage) { /* this is the home screen: Back does nothing */ }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Wallpaper(state.animated)
        if (manage) {
            ManageScreen(state)
        } else {
            HomeScreen(state, onManage = { manage = true })
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Wallpaper: a gradient that follows the time of day, with two slowly drifting glows
// ---------------------------------------------------------------------------------------------

private class Stop(val hour: Float, val top: Color, val bottom: Color, val glow: Color)

private val NIGHT = Stop(0f, Color(0xFF070B1F), Color(0xFF1A1240), Color(0xFF5B3FD0))
private val STOPS = listOf(
    NIGHT,
    Stop(6f, Color(0xFF1B1B4B), Color(0xFFB4506B), Color(0xFFFFA36C)),
    Stop(12f, Color(0xFF0E2A55), Color(0xFF2B6CB0), Color(0xFF6CC4FF)),
    Stop(18f, Color(0xFF1A1646), Color(0xFFC2543E), Color(0xFFFF8A5B)),
    Stop(24f, NIGHT.top, NIGHT.bottom, NIGHT.glow),
)

private fun currentHour(): Float {
    val t = LocalDateTime.now()
    return t.hour + t.minute / 60f
}

private fun paletteAt(hour: Float): Triple<Color, Color, Color> {
    val i = STOPS.indexOfLast { it.hour <= hour }.coerceIn(0, STOPS.size - 2)
    val a = STOPS[i]
    val b = STOPS[i + 1]
    val t = ((hour - a.hour) / (b.hour - a.hour)).coerceIn(0f, 1f)
    return Triple(lerp(a.top, b.top, t), lerp(a.bottom, b.bottom, t), lerp(a.glow, b.glow, t))
}

@Composable
private fun Wallpaper(animated: Boolean) {
    var hour by remember { mutableFloatStateOf(currentHour()) }
    LaunchedEffect(Unit) {
        while (true) {
            hour = currentHour()
            delay(60_000)
        }
    }
    val (top, bottom, glow) = paletteAt(hour)

    val phase: State<Float> = if (animated) {
        rememberInfiniteTransition(label = "bg").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(45_000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "phase",
        )
    } else {
        remember { mutableStateOf(0.5f) }
    }

    Canvas(Modifier.fillMaxSize()) {
        val p = phase.value
        val w = size.width
        val h = size.height
        drawRect(Brush.verticalGradient(listOf(top, bottom)))

        val c1 = Offset(w * (0.15f + 0.45f * p), h * (0.20f + 0.25f * p))
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(glow.copy(alpha = 0.50f), Color.Transparent),
                center = c1,
                radius = w * 0.55f,
            ),
            radius = w * 0.55f,
            center = c1,
        )

        val c2 = Offset(w * (0.85f - 0.40f * p), h * (0.75f - 0.30f * p))
        val glow2 = lerp(bottom, Color.White, 0.25f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(glow2.copy(alpha = 0.30f), Color.Transparent),
                center = c2,
                radius = w * 0.45f,
            ),
            radius = w * 0.45f,
            center = c2,
        )

        // Darken the lower half so the rows stay readable on bright palettes.
        drawRect(Brush.verticalGradient(0.40f to Color.Transparent, 1f to Color(0xCC000000)))
    }
}

// ---------------------------------------------------------------------------------------------
// Home screen
// ---------------------------------------------------------------------------------------------

@Composable
private fun HomeScreen(state: LauncherState, onManage: () -> Unit) {
    val listState = rememberLazyListState()
    val heroFocus = remember { FocusRequester() }

    // Put focus on the hero card once there is an app to show.
    val hasHero = state.continueApp != null
    LaunchedEffect(hasHero) {
        if (hasHero) {
            delay(150)
            runCatching { heroFocus.requestFocus() }
        }
    }
    LaunchedEffect(state.homePresses) {
        if (state.homePresses > 0) {
            listState.animateScrollToItem(0)
            runCatching { heroFocus.requestFocus() }
        }
    }

    val favorites = state.favorites
    val others = state.others
    val inputs = state.inputs

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 56.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "hero") { Hero(state, heroFocus) }

        if (favorites.isNotEmpty()) {
            item(key = "favorites") {
                Shelf("Favorites") {
                    items(favorites, key = { it.pkg }) { app ->
                        AppCard(app, width = 250.dp, onClick = { state.launch(app.pkg) })
                    }
                }
            }
        }

        others.chunked(8).forEachIndexed { index, chunk ->
            item(key = "apps-$index") {
                Shelf(if (index == 0) "Apps" else null) {
                    items(chunk, key = { it.pkg }) { app ->
                        AppCard(app, width = 210.dp, onClick = { state.launch(app.pkg) })
                    }
                }
            }
        }

        if (inputs.isNotEmpty()) {
            item(key = "inputs") {
                Shelf("Inputs") {
                    items(inputs, key = { it.id }) { input ->
                        InfoCard(input.label, "Switch input", onClick = { state.openInput(input.id) })
                    }
                }
            }
        }

        item(key = "settings") {
            Shelf("Settings") {
                item(key = "manage") { InfoCard("Manage apps", "Pin & hide", onClick = onManage) }
                item(key = "wallpaper") {
                    InfoCard(
                        "Wallpaper",
                        if (state.animated) "Animated" else "Still",
                        onClick = { state.toggleAnimated() },
                    )
                }
                items(SHORTCUTS, key = { it.action }) { s ->
                    InfoCard(s.label, null, onClick = { state.openSettings(s.action) })
                }
            }
        }
    }
}

@Composable
private fun Hero(state: LauncherState, focus: FocusRequester) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    val timeFmt = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    val dateFmt = remember { DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()) }
    val greeting = when {
        now.hour < 5 -> "Good night"
        now.hour < 12 -> "Good morning"
        now.hour < 17 -> "Good afternoon"
        else -> "Good evening"
    }
    val hero = state.continueApp

    Row(
        Modifier
            .fillMaxWidth()
            .height(300.dp)
            .padding(start = 56.dp, end = 56.dp, top = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(greeting, color = Color.White.copy(alpha = 0.75f), fontSize = 24.sp, fontWeight = FontWeight.Medium)
            Text(
                now.format(timeFmt),
                color = Color.White,
                fontSize = 96.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = (-2).sp,
            )
            Text(now.format(dateFmt), color = Color.White.copy(alpha = 0.75f), fontSize = 24.sp)
        }
        if (hero != null) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "Jump back in",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                AppCard(
                    hero,
                    width = 400.dp,
                    showLabel = true,
                    modifier = Modifier.focusRequester(focus),
                    onClick = { state.launch(hero.pkg) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Building blocks
// ---------------------------------------------------------------------------------------------

@Composable
private fun Shelf(title: String?, content: LazyListScope.() -> Unit) {
    Column {
        if (title != null) {
            Text(
                title,
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 56.dp, bottom = 2.dp),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 56.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            content = content,
        )
    }
}

/** A focusable tile that grows and gets a white outline when selected. */
@Composable
private fun TvCard(
    modifier: Modifier,
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
    content: @Composable BoxScope.(Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, tween(160), label = "scale")
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .size(width, height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(if (focused) 16.dp else 0.dp, shape)
            .then(if (focused) Modifier.border(3.dp, Color.White.copy(alpha = 0.95f), shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        content(focused)
    }
}

@Composable
private fun AppCard(
    app: AppEntry,
    width: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showLabel: Boolean = false,
) {
    TvCard(modifier, width, width * 9 / 16, onClick) { focused ->
        val image = app.image
        if (image != null && app.isBanner) {
            Image(image, app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(listOf(Color(0x40FFFFFF), Color(0x18FFFFFF)))),
                contentAlignment = Alignment.Center,
            ) {
                if (image != null) {
                    Image(
                        image,
                        app.label,
                        Modifier.fillMaxHeight(0.55f).aspectRatio(1f),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text(app.label.take(1), color = Color.White, fontSize = 40.sp)
                }
            }
        }
        val labelAlpha by animateFloatAsState(
            if (focused || showLabel) 1f else 0f,
            tween(160),
            label = "label",
        )
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .graphicsLayer { alpha = labelAlpha }
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                app.label,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun InfoCard(title: String, subtitle: String?, onClick: () -> Unit) {
    TvCard(Modifier, 200.dp, 113.dp, onClick) { _ ->
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(Color(0x44FFFFFF), Color(0x1AFFFFFF)))),
        )
        Column(
            Modifier.align(Alignment.Center).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            if (subtitle != null) {
                Text(subtitle, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Manage apps: OK cycles an app through normal -> pinned -> hidden
// ---------------------------------------------------------------------------------------------

@Composable
private fun ManageScreen(state: LauncherState) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(200)
        runCatching { first.requestFocus() }
    }
    LazyColumn(
        Modifier.fillMaxSize().background(Color(0x99000000)).padding(horizontal = 120.dp),
        contentPadding = PaddingValues(vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") {
            Column(Modifier.padding(bottom = 12.dp)) {
                Text("Manage apps", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Press OK on an app to cycle: normal, pinned to Favorites, hidden. Press Back when done.",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 18.sp,
                )
            }
        }
        itemsIndexed(state.apps, key = { _, a -> a.pkg }) { index, app ->
            ManageRow(
                app,
                state.statusOf(app.pkg),
                if (index == 0) Modifier.focusRequester(first) else Modifier,
            ) { state.cycle(app.pkg) }
        }
    }
}

@Composable
private fun ManageRow(app: AppEntry, status: Status, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val (statusText, statusColor) = when (status) {
        Status.Pinned -> "★ Pinned" to Color(0xFFFFD166)
        Status.Hidden -> "Hidden" to Color(0xFFFF8A8A)
        Status.Normal -> "" to Color.White
    }
    Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(Color.White.copy(alpha = if (focused) 0.22f else 0.07f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val image = app.image
        if (image != null) {
            Image(
                image,
                null,
                Modifier
                    .size(width = if (app.isBanner) 71.dp else 40.dp, height = 40.dp)
                    .clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Spacer(Modifier.width(40.dp))
        }
        Spacer(Modifier.width(16.dp))
        Text(
            app.label,
            color = Color.White,
            fontSize = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(statusText, color = statusColor, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}
