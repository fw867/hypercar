package io.github.hypercar.carui

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val White = Color(0xFFF5F5FA)
private val Muted = Color(0xFFB4B5C5)
private val CardShape = RoundedCornerShape(22.dp)

@Composable
fun CarHomeScreen() {
    // 以 1920x720@240dpi（Compose 1280x480dp）为设计基准；
    // 分辨率变化时整体按比例缩放，而不是让 Dock/卡片在高分屏上显得越来越小。
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val base = LocalDensity.current
        val scale = minOf(maxWidth.value / 1280f, maxHeight.value / 480f).coerceIn(0.75f, 2.0f)
        CompositionLocalProvider(
            LocalDensity provides Density(base.density * scale, base.fontScale),
        ) {
            CarHomeContent()
        }
    }
}

@Composable
private fun CarHomeContent() {
    val music by MusicBridge.state.collectAsStateWithLifecycle()
    var time by remember { mutableStateOf(LocalTime.now()) }
    var apps by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var battery by remember { mutableIntStateOf(0) }
    var charging by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            time = LocalTime.now()
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let {
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                battery = it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / scale
                charging = it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            }
            delay(1000)
        }
    }
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val dark = when (CarUiSettings.themeMode) {
        CarThemeMode.LIGHT -> false
        CarThemeMode.DARK -> true
        CarThemeMode.AUTO -> CarUiSettings.carNight ?: systemDark
    }
    CarThemeState.dark = dark
    val wallpaper = remember(CarUiSettings.wallpaper, dark) {
        CarWallpapers.load(context, CarUiSettings.wallpaper, dark)
    }
    // Keep the encoder's display rendering even while the application page is idle.
    val pulse by rememberInfiniteTransition(label = "homeFrames").animateFloat(
        0.3f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "frame",
    )
    Box(Modifier.fillMaxSize().background(CarBg)) {
        wallpaper?.let {
            Image(it.asImageBitmap(), "壁纸", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(
            if (dark) Color.Black.copy(alpha = .22f) else Color.White.copy(alpha = .10f),
        ))
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color(0x01000000), pulse, Offset.Zero)
        }
        Row(Modifier.fillMaxSize().padding(end = 22.dp, top = 22.dp, bottom = 22.dp)) {
            Column(Modifier.width(110.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(time.format(DateTimeFormatter.ofPattern("HH:mm")), color = CarInk,
                    fontSize = 25.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                BatteryIndicator(battery, charging)
                Spacer(Modifier.height(16.dp))
                // Dock 4 个：高德 / 音乐 / 设置 / 更多（更多按钮在应用页和首页之间切换）
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    DockButton(Icons.Rounded.Navigation, "高德地图",
                        if (CarThemeState.dark) Color(0xFF12B5D2) else CarAccent) { MapController.requestEnter() }
                    DockButton(Icons.Rounded.MusicNote, "音乐", Color(0xFF7B51EB)) {
                        launchMusic(context, music)
                    }
                    DockButton(Icons.Rounded.Settings, "设置", Color(0xFF5B5675)) {
                        settings = true
                        apps = false
                    }
                }
                Spacer(Modifier.weight(1f))
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (settings) {
                    CarSettingsPage(Modifier.weight(1f)) { settings = false }
                } else if (about) {
                    CarAboutPage(Modifier.weight(1f)) { about = false }
                } else if (apps) {
                    AppsPage(Modifier.weight(1f), music, { settings = true }, { about = true })
                } else {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        MapCard(Modifier.weight(1.5f).fillMaxHeight())
                        MusicCard(music, Modifier.weight(1f).fillMaxHeight())
                    }
                }
                Row(Modifier.fillMaxWidth().height(15.dp), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.Bottom) {
                    repeat(2) { index ->
                        Box(Modifier.padding(horizontal = 4.dp).size(5.dp).clip(CircleShape)
                            .background(if ((index == 1) == apps) CarInk else CarInk.copy(alpha = .3f)))
                    }
                }
            }
        }
        // 左下角：首页显示“更多应用”，应用/设置/关于页显示“首页”
        val onHome = !apps && !settings && !about
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 25.dp, bottom = 16.dp)
                .size(60.dp)
                .clickable {
                    if (onHome) {
                        apps = true
                    } else {
                        apps = false
                        settings = false
                        about = false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (onHome) Icons.Rounded.Apps else Icons.Rounded.Home,
                if (onHome) "更多应用" else "返回首页", tint = CarInk, modifier = Modifier.size(44.dp))
        }
    }
}

@Composable
private fun BatteryIndicator(level: Int, charging: Boolean) {
    val pct = level.coerceIn(0, 100)
    val color = when {
        charging -> Color(0xFF34D399)
        pct <= 15 -> Color(0xFFEF4444)
        pct <= 40 -> Color(0xFFF59E0B)
        else -> Color(0xFF34D399)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(38.dp).height(18.dp)) {
            val cap = 3f
            val bodyW = size.width - cap - 1f
            val bodyH = size.height
            val corner = androidx.compose.ui.geometry.CornerRadius(4f)
            drawRoundRect(color.copy(alpha = .16f),
                size = androidx.compose.ui.geometry.Size(bodyW, bodyH), cornerRadius = corner)
            drawRoundRect(color,
                size = androidx.compose.ui.geometry.Size(bodyW, bodyH), cornerRadius = corner,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
            val pad = 3.5f
            val fillW = (bodyW - pad * 2) * pct / 100f
            if (fillW > 0f) {
                drawRoundRect(color.copy(alpha = .78f),
                    topLeft = androidx.compose.ui.geometry.Offset(pad, pad),
                    size = androidx.compose.ui.geometry.Size(fillW, bodyH - pad * 2),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f))
            }
            drawRoundRect(color,
                topLeft = androidx.compose.ui.geometry.Offset(bodyW + 1f, bodyH * .3f),
                size = androidx.compose.ui.geometry.Size(cap, bodyH * .4f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1f))
        }
        Spacer(Modifier.width(6.dp))
        Text("$pct%", color = CarInk, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DockButton(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Box(Modifier.size(60.dp).clip(RoundedCornerShape(18.dp))
        .background(Brush.linearGradient(listOf(color, color.copy(alpha = .65f))))
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = White, modifier = Modifier.size(40.dp))
    }
}

@Composable
private fun MapCard(modifier: Modifier) {
    val status by AmapPreview.status.collectAsStateWithLifecycle()
    Box(modifier.clip(CardShape).background(Color(0xFF1B2637))) {
        AmapPreviewView(Modifier.fillMaxSize())
        // The card is an overview. Touch opens the fully interactive map on D2.
        Box(Modifier.fillMaxSize().clickable(onClickLabel = "打开全屏高德地图") { MapController.requestEnter() })
        if (status != "") {
            Column(Modifier.align(Alignment.Center).clip(RoundedCornerShape(18.dp))
                .background(if (CarThemeState.dark) Color(0xDD10141F) else Color(0xEAF6FAFF)).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Navigation, null, tint = CarAccent, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(12.dp))
                Text(status, color = CarInk, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun MusicCard(music: NowPlaying, modifier: Modifier) {
    val context = LocalContext.current
    val installed = remember(context) { MusicApps.installed(context) }
    var picker by remember { mutableStateOf(false) }
    val currentIcon = remember(music.packageName) { MusicApps.iconFor(context, music.packageName) }
    val dark = CarThemeState.dark
    val ink = if (dark) White else Color(0xFF1B1730)
    val sub = if (dark) White.copy(alpha = .65f) else Color(0xFF5F5870)
    val pickerBg = if (dark) Color(0xF21B1930) else Color(0xF7FFFFFF)
    Box(modifier.clip(CardShape).background(Brush.verticalGradient(
        if (dark) listOf(Color(0xFF484265), Color(0xFF25203A), Color(0xFF171623))
        else listOf(Color(0xFFD6E5F5), Color(0xFFF1F7FE), Color(0xFFE4EFFA))))) {
        music.albumArt?.let {
            Image(it.asImageBitmap(), "专辑封面", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(if (dark) {
            Brush.verticalGradient(listOf(
                Color.Black.copy(alpha = .08f), Color.Black.copy(alpha = .26f),
                Color.Black.copy(alpha = .76f), Color.Black.copy(alpha = .84f)))
        } else {
            // CarLife 白天卡：封面保留但压成浅蓝白，标题/歌手保持深色可读。
            Brush.verticalGradient(listOf(
                Color(0xFFF2F7FD).copy(alpha = .55f), Color(0xFFF2F7FD).copy(alpha = .72f),
                Color(0xFFF2F7FD).copy(alpha = .90f), Color(0xFFF2F7FD).copy(alpha = .95f)))
        }))
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = installed.isNotEmpty()) { picker = true }
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(White.copy(alpha = .92f)),
                    contentAlignment = Alignment.Center) {
                    if (currentIcon != null) {
                        Image(currentIcon.asImageBitmap(), null, Modifier.fillMaxSize().clip(CircleShape),
                            contentScale = ContentScale.Crop)
                    } else {
                        Icon(Icons.Rounded.MusicNote, null, tint = Color(0xFF7461D9), modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(music.appLabel.ifBlank { "音乐" }, color = ink.copy(alpha = .85f), fontSize = 12.sp)
                if (installed.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Rounded.ExpandMore, "切换音乐应用", tint = ink.copy(alpha = .8f),
                        modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.weight(1f))
                if (music.playing) Icon(Icons.Rounded.GraphicEq, "正在播放", tint = ink, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(music.title.ifBlank { "听点喜欢的" }, color = ink, fontSize = 27.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Text(music.artist.ifBlank { if (music.available) "选择一首音乐，开始旅程" else "连接你的音乐" },
                color = sub, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(28.dp))
            if (music.available) {
                val progress = if (music.durationMs > 0) (music.positionMs.toFloat() / music.durationMs).coerceIn(0f, 1f) else 0f
                Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                    drawRoundRect(ink.copy(alpha = .18f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f))
                    if (progress > 0) drawRoundRect(ink.copy(alpha = .85f),
                        size = androidx.compose.ui.geometry.Size(size.width * progress, size.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f))
                }
                Row(Modifier.fillMaxWidth().padding(top = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(music.positionMs), color = sub.copy(alpha = .7f), fontSize = 10.sp)
                    Text(formatTime(music.durationMs), color = sub.copy(alpha = .7f), fontSize = 10.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    PlayerButton(Icons.Rounded.SkipPrevious, "上一首", 38, ink) { MusicBridge.previous() }
                    PlayerButton(if (music.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (music.playing) "暂停" else "播放", 48, ink) { MusicBridge.playPause() }
                    PlayerButton(Icons.Rounded.SkipNext, "下一首", 38, ink) { MusicBridge.next() }
                }
            } else {
                Text("在手机上播放音乐，即可在这里控制", color = sub, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { MusicBridge.openAccessSettings(context) }) {
                    Text("连接音乐", color = ink, fontSize = 15.sp)
                }
            }
        }
        if (picker) {
            // 直接画在卡片内，不用 Popup：D1 虚拟屏上 Popup 可能落在别的窗口，
            // 编码器只采到 Activity 这一层，切换列表就看不到。
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = .45f))
                    .clickable { picker = false },
            )
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(18.dp)
                    .width(300.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(pickerBg)
                    .padding(vertical = 8.dp),
            ) {
                Text("切换音乐应用", color = sub, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                installed.forEach { app ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                picker = false
                                MusicBridge.selectPackage(context, app.packageName)
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (app.icon != null) {
                            Image(app.icon.asImageBitmap(), null, Modifier.size(28.dp).clip(CircleShape),
                                contentScale = ContentScale.Crop)
                        } else {
                            Icon(Icons.Rounded.MusicNote, null, tint = ink, modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(app.label, color = ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        if (app.packageName == music.packageName) {
                            Icon(Icons.Rounded.Check, "当前", tint = Color(0xFF6EE7B7), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerButton(icon: ImageVector, label: String, size: Int, tint: Color, click: () -> Unit) {
    IconButton(onClick = click, modifier = Modifier.size(58.dp)) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(size.dp))
    }
}

private fun launchMusic(context: android.content.Context, music: NowPlaying) {
    val intent = music.packageName.takeIf { it.isNotBlank() }
        ?.let { context.packageManager.getLaunchIntentForPackage(it) }
    if (intent != null) runCatching {
        val options = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(0)
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle())
    }
    else MusicBridge.openAccessSettings(context)
}

@Composable
private fun AppsPage(modifier: Modifier, music: NowPlaying, onSettings: () -> Unit = {}, onAbout: () -> Unit = {}) {
    val context = LocalContext.current
    Column(modifier.fillMaxWidth().clip(CardShape).background(CarPanel).padding(28.dp)) {
        Text("应用", color = CarInk, fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(42.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                DockButton(Icons.Rounded.Navigation, "高德地图", if (CarThemeState.dark) Color(0xFF12B5D2) else CarAccent) { MapController.requestEnter() }
                Spacer(Modifier.height(12.dp))
                Text("高德地图", color = CarInk, fontSize = 14.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                DockButton(Icons.Rounded.MusicNote, "音乐", Color(0xFF7B51EB)) { launchMusic(context, music) }
                Spacer(Modifier.height(12.dp))
                Text(music.appLabel.ifBlank { "音乐" }, color = CarInk, fontSize = 14.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                DockButton(Icons.Rounded.Settings, "设置", Color(0xFF5B5675), onSettings)
                Spacer(Modifier.height(12.dp))
                Text("设置", color = CarInk, fontSize = 14.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                DockButton(Icons.Rounded.Info, "关于应用", Color(0xFF2F80ED), onAbout)
                Spacer(Modifier.height(12.dp))
                Text("关于应用", color = CarInk, fontSize = 14.sp)
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
