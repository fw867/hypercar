package io.github.hypercar.carui

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.text.style.TextAlign
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
        val layout by CarDisplayLayout.state.collectAsStateWithLifecycle()
        val canvasWidth = constraints.maxWidth
        val canvasHeight = constraints.maxHeight
        val full = DisplayRect(0, 0, canvasWidth, canvasHeight)
        val safe = layout?.contentBounds(canvasWidth, canvasHeight) ?: full
        val background = layout?.backgroundBounds(canvasWidth, canvasHeight) ?: full
        val base = LocalDensity.current
        val scale = minOf(maxWidth.value / 1280f, maxHeight.value / 480f).coerceIn(0.75f, 2.0f)
        CompositionLocalProvider(
            LocalDensity provides Density(base.density * scale, base.fontScale),
        ) {
            CarHomeContent(safe = safe, background = background)
        }
    }
}

@Composable
private fun CarHomeContent(safe: DisplayRect, background: DisplayRect) {
    val density = LocalDensity.current
    val musicCardWidth = with(density) { (safe.width * .25f).toDp() }
    fun bounds(rect: DisplayRect): Modifier = with(density) {
        Modifier.absoluteOffset(rect.x.toDp(), rect.y.toDp())
            .requiredSize(rect.width.toDp(), rect.height.toDp()).clipToBounds()
    }
    val music by MusicBridge.state.collectAsStateWithLifecycle()
    var time by remember { mutableStateOf(LocalTime.now()) }
    var apps by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    val enterMap: () -> Unit = {
        apps = false
        settings = false
        about = false
        MapController.requestEnter()
    }
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
    // **蜂窝信号**（2026-10-31）：Dock 顶部只显示「信号柱 + 制式(4G/5G)」，不显示 Wi-Fi。
    // 电平与制式都靠 Shizuku 读 telephony.registry，避开 READ_PHONE_STATE 权限。
    var cellLevel by remember { mutableIntStateOf(0) }      // 0..4
    var cellKnown by remember { mutableStateOf(false) }
    var cellLabel by remember { mutableStateOf("") }        // 5G / 4G / 3G / 2G
    LaunchedEffect(Unit) {
        while (true) {
            if (io.github.hypercar.shizuku.ShizukuTools.isReady) {
                // **必须切到 IO 线程**：ShizukuTools.exec 有主线程保护，在 Compose 的
                // LaunchedEffect（Main）里直接调会被拒绝（日志："调用 shell（第 N 次）…
                // ——必须切到后台线程"），于是信号永远读不到、指示器不显示（2026-10-31）。
                val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        io.github.hypercar.shizuku.ShizukuTools.exec(
                            "dumpsys telephony.registry | grep -m1 mSignalStrength; " +
                            "dumpsys telephony.registry | grep -m1 mNetworkType", 4_000)
                    }.getOrNull()
                }
                val raw = r?.out.orEmpty()
                if (raw.isNotBlank()) {
                    // MIUI 用 miuiLevel，AOSP 用 level；两者都试
                    val m = Regex("miuiLevel = (\\d+)").find(raw)
                        ?: Regex("level = (\\d+)").find(raw)
                    m?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
                        cellLevel = it.coerceIn(0, 4); cellKnown = true
                    }
                    // 制式 → 显示文本（mNetworkType=NR / LTE / HSPA / GSM …）
                    Regex("mNetworkType=([A-Z_]+)").find(raw)?.groupValues?.getOrNull(1)?.let { rat ->
                        cellLabel = when (rat) {
                            "NR" -> "5G"
                            "LTE" -> "4G"
                            "HSPAP", "HSPA", "HSDPA", "HSUPA", "UMTS", "TD_SCDMA", "EVDO" -> "3G"
                            "EDGE", "GPRS", "GSM", "CDMA" -> "2G"
                            else -> ""
                        }
                    }
                }
            }
            delay(15_000)
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
    Box(Modifier.fillMaxSize().background(Color.Black)) {
      Box(bounds(background).background(CarBg)) {
        wallpaper?.let {
            Image(it.asImageBitmap(), "壁纸", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(
            if (dark) Color.Black.copy(alpha = .22f) else Color.White.copy(alpha = .10f),
        ))
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color(0x01000000), pulse, Offset.Zero)
        }
      }
      Box(bounds(safe)) {
        Row(Modifier.fillMaxSize().padding(end = 22.dp)) {
            // **Dock 玻璃底板**（2026-10-31）：以前 Dock 直接浮在地图上，没有承托。
            // 参考 CarWith 的磨砂玻璃观感——Compose 做不到"背景模糊"（需要系统级 backdrop），
            // 所以用三层叠加模拟：半透明渐变底 + 细描边高光 + 圆角裁切，
            // 地图在下面移动时能透出来，视觉上就是一块玻璃条。
            val dockShape = RoundedCornerShape(topEnd = 30.dp, bottomEnd = 30.dp)   // 左侧齐边，只有右侧圆角
            Column(
                Modifier
                    .width(100.dp)
                    .fillMaxHeight()
                    .clip(dockShape)
                    .background(
                        Brush.verticalGradient(
                            if (CarThemeState.dark) listOf(
                                Color.White.copy(alpha = .12f), Color.White.copy(alpha = .05f))
                            else listOf(
                                Color.White.copy(alpha = .58f), Color.White.copy(alpha = .30f))
                        )
                    )
                    .border(
                        width = 1.dp,
                        color = if (CarThemeState.dark) Color.White.copy(alpha = .14f)
                        else Color.White.copy(alpha = .70f),
                        shape = dockShape,
                    )
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(time.format(DateTimeFormatter.ofPattern("HH:mm")), color = CarInk,
                    fontSize = 25.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                NetworkIndicator(cellLevel = cellLevel, cellKnown = cellKnown, label = cellLabel)
                Spacer(Modifier.height(6.dp))
                BatteryIndicator(battery, charging)
                Spacer(Modifier.height(16.dp))
                // Dock 4 个：高德 / 音乐 / 设置 / 更多（更多按钮在应用页和首页之间切换）
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // **地图/音乐用当前 App 的真实图标**（2026-10-31）：
                    // 之前是固定的「导航针 / 音符」矢量图，切到高德/百度、QQ音乐/汽水后
                    // 图标不变，看不出当前用的是谁。这里按包名取 PackageManager 图标，
                    // 取不到（未选/未安装）才退回内置矢量图。
                    val mapProvider = MapApps.selected(context)
                    val mapIcon = remember(mapProvider?.packageName) {
                        mapProvider?.let { MusicApps.iconFor(context, it.packageName) }
                    }
                    val musicIcon = remember(music.packageName) {
                        music.packageName.takeIf { it.isNotBlank() }
                            ?.let { MusicApps.iconFor(context, it) }
                    }
                    DockButton(Icons.Rounded.Navigation, mapProvider?.label ?: "地图",
                        if (CarThemeState.dark) Color(0xFF12B5D2) else CarAccent,
                        buttonSize = 68, bitmap = mapIcon) { enterMap() }
                    DockButton(Icons.Rounded.MusicNote, "音乐", Color(0xFF7B51EB),
                        buttonSize = 68, bitmap = musicIcon) {
                        launchMusic(context, music)
                    }
                    DockButton(Icons.Rounded.Settings, "设置", Color(0xFF5B5675), buttonSize = 68) {
                        settings = true
                        apps = false
                    }
                }
                Spacer(Modifier.weight(1f))
                // **底部「更多应用 / 返回首页」移进 Dock 列**（2026-10-31）：
                // 之前是外面单独一个 BottomStart 的 Box，和 Dock 列的中轴对不齐（偏右约 10dp）。
                // 放进同一列后由 CenterHorizontally 统一对齐。
                val onHome = !apps && !settings && !about
                Box(
                    Modifier
                        .size(68.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .clickable {
                            if (onHome) {
                                apps = true
                            } else {
                                apps = false; settings = false; about = false
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (onHome) Icons.Rounded.Apps else Icons.Rounded.Home,
                        if (onHome) "更多应用" else "返回首页", tint = CarInk,
                        modifier = Modifier.size(38.dp))
                }
            }
            // 与「地图卡 ↔ 音乐卡」相同的 20dp 间距
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f).fillMaxHeight().padding(top = 22.dp, bottom = 22.dp)) {
                if (settings) {
                    CarSettingsPage(Modifier.weight(1f)) { settings = false }
                } else if (about) {
                    CarAboutPage(Modifier.weight(1f)) { about = false }
                } else if (apps) {
                    AppsPage(Modifier.weight(1f), music, { settings = true }, { about = true }, enterMap)
                } else {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        MapCard(Modifier.weight(1f).fillMaxHeight(), enterMap)
                        MusicCard(music, Modifier.width(musicCardWidth).fillMaxHeight())
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
private fun DockButton(
    icon: ImageVector,
    label: String,
    color: Color,
    buttonSize: Int = 68,
    /** 当前 App 的图标（2026-10-31）；为 null 时退回内置矢量图标。 */
    bitmap: android.graphics.Bitmap? = null,
    onClick: () -> Unit,
) {
    // **统一图标尺寸**（2026-10-31，对齐 CarWith 观感）：
    // 三种按钮的可视块统一 56dp —— App 图标直接画 56dp；
    // 内置矢量图标（设置齿轮）画在 56dp 实色圆角块上、齿轮 30dp。
    // 外框 68dp 只作为点击热区。这样三个图标外轮廓一致，不再一大一小。
    val visual = 56.dp
    val visualShape = RoundedCornerShape(15.dp)
    Box(
        modifier = Modifier.size(buttonSize.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), label,
                modifier = Modifier.size(visual).clip(visualShape),
                contentScale = ContentScale.Crop)
        } else {
            Box(
                modifier = Modifier.size(visual).clip(visualShape).background(color),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, label, tint = White, modifier = Modifier.size(30.dp))
            }
        }
    }
}

@Composable
private fun MapCard(modifier: Modifier, onMap: () -> Unit) {
    val status by MapPreview.status.collectAsStateWithLifecycle()
    Box(modifier.clip(CardShape).background(Color(0xFF1B2637))) {
        MapPreviewView(Modifier.fillMaxSize())
        // The card is an overview. Touch opens the fully interactive map on D2.
        Box(Modifier.fillMaxSize().clickable(onClickLabel = "打开全屏地图") { onMap() })
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
    var queueOpen by remember(music.packageName) { mutableStateOf(false) }
    var modesOpen by remember(music.packageName) { mutableStateOf(false) }
    val mode = music.dedicatedMode ?: MusicPlaybackModes.current(music.repeatMode, music.shuffleMode)
    val modes = if (music.dedicatedMode != null) listOf(MusicPlaybackMode.ALL, MusicPlaybackMode.ONE, MusicPlaybackMode.SHUFFLE)
        else MusicPlaybackModes.available(music.canRepeat, music.canShuffle, music.repeatMode, music.shuffleMode)
    val currentIcon = remember(music.packageName) { MusicApps.iconFor(context, music.packageName) }
    val dark = CarThemeState.dark
    val ink = if (dark) White else Color(0xFF1B1730)
    val sub = if (dark) White.copy(alpha = .65f) else Color(0xFF5F5870)
    val pickerBg = if (dark) Color(0xF21B1930) else Color(0xF7FFFFFF)
    Box(modifier.clip(CardShape).background(Brush.verticalGradient(
        if (dark) listOf(Color(0xFF484265), Color(0xFF25203A), Color(0xFF171623))
        else listOf(Color(0xFFD6E5F5), Color(0xFFF1F7FE), Color(0xFFE4EFFA))))) {
        music.albumArt?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(if (dark) {
            // 标题以上的区域尽量透（2026-10-31 第二轮）：顶部几乎不压暗，封面原色可见；
            // 到标题附近才迅速收紧，保证文字/进度条对比度不受影响。
            Brush.verticalGradient(listOf(
                Color.Black.copy(alpha = .02f), Color.Black.copy(alpha = .12f),
                Color.Black.copy(alpha = .72f), Color.Black.copy(alpha = .85f)))
        } else {
            // CarLife 白天卡：标题以上只留极淡的雾（原来 .22/.50 仍然偏白），
            // 下方文字区保持高不透明度以维持深色文字可读。
            Brush.verticalGradient(listOf(
                Color(0xFFF2F7FD).copy(alpha = .10f), Color(0xFFF2F7FD).copy(alpha = .32f),
                Color(0xFFF2F7FD).copy(alpha = .86f), Color(0xFFF2F7FD).copy(alpha = .95f)))
        }))
        Column(Modifier.fillMaxSize().padding(18.dp)) {
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(music.title.ifBlank { "听点喜欢的" }, color = ink, fontSize = 21.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(music.artist.ifBlank { if (music.available) "选择一首音乐" else "连接你的音乐" },
                        color = sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                PlayerButton(if (music.liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    if (music.liked) "取消喜欢" else "喜欢", 26,
                    if (music.liked) Color(0xFFE65E79) else ink,
                    enabled = music.canLike, touchSize = 44) { MusicBridge.toggleFavorite() }
            }
            Spacer(Modifier.height(16.dp))
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
                    PlayerButton(Icons.Rounded.QueueMusic, "播放列表", 25, ink, touchSize = 44) {
                        queueOpen = true; MusicBridge.loadMusicQueue()
                    }
                    PlayerButton(Icons.Rounded.SkipPrevious, "上一首", 31, ink, touchSize = 44) { MusicBridge.previous() }
                    PlayerButton(if (music.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (music.playing) "暂停" else "播放", 40, ink, touchSize = 52) { MusicBridge.playPause() }
                    PlayerButton(Icons.Rounded.SkipNext, "下一首", 31, ink, touchSize = 44) { MusicBridge.next() }
                    PlayerButton(mode?.icon() ?: Icons.Rounded.Repeat,
                        mode?.label ?: "播放模式", 25, ink, touchSize = 44) {
                        modesOpen = true; MusicBridge.refreshPlaybackMode()
                    }
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
                    .fillMaxWidth()
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
        if (queueOpen || modesOpen) {
            Column(Modifier.fillMaxSize().background(pickerBg).padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (queueOpen) "播放列表" else "播放模式", color = ink, fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { queueOpen = false; modesOpen = false }) {
                        Icon(Icons.Rounded.Close, "关闭", tint = ink)
                    }
                }
                if (queueOpen) {
                    if (music.queue.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(if (music.queueLoading) "正在获取播放列表…" else music.queueError.ifBlank { "当前播放器暂未提供播放列表" }, color = sub, fontSize = 14.sp)
                        }
                        if (music.packageName == QqMusicAdapter.PACKAGE) TextButton(onClick = { MusicBridge.loadMusicQueue() }) { Text("重试", color = ink) }
                        TextButton(onClick = { launchMusic(context, music) }) { Text("在手机播放器中查看", color = ink) }
                    } else LazyColumn(Modifier.weight(1f)) {
                        items(music.queue) { song ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .background(if (song.id == music.activeQueueId) ink.copy(alpha = .08f) else Color.Transparent)
                                .clickable(enabled = music.canSelectQueue) {
                                    MusicBridge.playQueueItem(music.packageName, song.id, song.sourceId); queueOpen = false
                                }.padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(song.title.ifBlank { "未知歌曲" }, color = ink, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (song.artist.isNotBlank()) Text(song.artist, color = sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (song.id == music.activeQueueId) Icon(Icons.Rounded.GraphicEq, "当前歌曲", tint = ink, modifier = Modifier.size(20.dp))
                            }
                        }
                        if (music.queueError.isNotBlank()) item { Text(music.queueError, color = sub, modifier = Modifier.padding(12.dp)) }
                        if (music.queueHasMore) item {
                            TextButton(onClick = { MusicBridge.loadMusicQueue(more = true) }, enabled = !music.queueLoading) {
                                Text(if (music.queueLoading) "正在加载…" else "加载更多", color = ink)
                            }
                        }
                    }
                } else if (modes.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(music.modeError.ifBlank { "当前播放器暂不支持切换播放模式" }, color = sub, fontSize = 14.sp)
                    }
                    if (music.packageName == QqMusicAdapter.PACKAGE) TextButton(onClick = { MusicBridge.refreshPlaybackMode() }) { Text("重试", color = ink) }
                    TextButton(onClick = { launchMusic(context, music) }) { Text("在手机播放器中切换", color = ink) }
                } else modes.forEach { option ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable { MusicBridge.setPlaybackMode(option); modesOpen = false }
                        .padding(horizontal = 12.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        // **每种模式都有独立图标**（2026-10-31）：之前这一列只有文字，
                        // 四种模式看起来一模一样，用户以为图标画错了。
                        Icon(option.icon(), option.label, tint = ink, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(option.label, color = ink, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        if (option == mode) Icon(Icons.Rounded.Check, "当前模式", tint = ink)
                    }
                }
            }
        }
    }
}

/**
 * 播放模式 → 图标（2026-10-31 修正）。
 *
 * 四种模式各用不同图标，彼此一眼可区分：
 *  · 顺序播放 → PlaylistPlay（列表播完即停）
 *  · 列表循环 → Repeat（首尾相接的循环箭头）
 *  · 单曲循环 → RepeatOne（循环箭头 + 数字 1）
 *  · 随机播放 → Shuffle（交叉箭头）
 *
 * 之前模式面板里只有文字、没有任何图标，按钮上「顺序/列表循环」的图标又都很像，
 * 于是看起来像"列表播放模式的图标做错了"。
 */
private fun MusicPlaybackMode.icon() = when (this) {
    MusicPlaybackMode.SEQUENTIAL -> Icons.Rounded.PlaylistPlay
    MusicPlaybackMode.ALL -> Icons.Rounded.Repeat
    MusicPlaybackMode.ONE -> Icons.Rounded.RepeatOne
    MusicPlaybackMode.SHUFFLE -> Icons.Rounded.Shuffle
}

@Composable
private fun PlayerButton(
    icon: ImageVector,
    label: String,
    size: Int,
    tint: Color,
    enabled: Boolean = true,
    touchSize: Int = 58,
    click: () -> Unit,
) {
    IconButton(onClick = click, enabled = enabled, modifier = Modifier.size(touchSize.dp)) {
        Icon(icon, label, tint = if (enabled) tint else tint.copy(alpha = .3f), modifier = Modifier.size(size.dp))
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppsPage(modifier: Modifier, music: NowPlaying, onSettings: () -> Unit = {}, onAbout: () -> Unit = {}, onMap: () -> Unit = {}) {
    val context = LocalContext.current
    val musicApps = remember(context) { MusicApps.installed(context) }
    val mapLabel = MapApps.selected(context)?.label ?: "地图"
    Box(modifier.fillMaxWidth().clip(CardShape).background(CarPanel)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp)) {
            Text("应用", color = CarInk, fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(28.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // **顺序（2026-10-31）**：自动获取的地图 → 自动获取的音乐 → 设置 → 关于 → 返回车机
                val installedMaps = remember(context) { MapApps.installed(context) }
                val selectedMap = MapApps.selected(context)
                installedMaps.forEach { provider ->
                    key(provider.id) {
                        val icon = remember(provider.packageName) {
                            MusicApps.iconFor(context, provider.packageName)
                        }
                        AppTile(bitmap = icon, fallback = Icons.Rounded.Navigation,
                            color = if (CarThemeState.dark) Color(0xFF12B5D2) else CarAccent,
                            label = provider.label,
                            onClickLabel = "使用${provider.label}",
                            label2 = if (provider == selectedMap) "当前" else null) {
                            // 点哪个地图就把哪个设为默认，并直接进全屏地图
                            CarUiSettings.updateDefaultMap(provider)
                            onMap()
                        }
                    }
                }
                musicApps.forEach { app ->
                    key(app.packageName) {
                        AppTile(bitmap = app.icon, fallback = Icons.Rounded.MusicNote,
                            color = CarCard, label = app.label,
                            onClickLabel = "打开${app.label}") {
                            MusicBridge.selectPackage(context, app.packageName)
                        }
                    }
                }
                AppTile(icon = Icons.Rounded.Settings, color = Color(0xFF5B5675),
                    label = "设置") { onSettings() }
                AppTile(icon = Icons.Rounded.Info, color = Color(0xFF2F80ED),
                    label = "关于应用") { onAbout() }
                AppTile(icon = Icons.Rounded.ExitToApp, color = Color(0xFF64748B),
                    label = "返回车机") {
                    val queued = io.github.hypercar.core.RustCoreBridge.nativeRequestHostUi()
                    io.github.hypercar.session.HyperCarRepo.log("CARUI: 返回车机请求 queued=$queued")
                    if (!queued) android.widget.Toast.makeText(context, "尚未连接车机", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}


/**
 * 蜂窝信号指示（2026-10-31）：左侧四格信号柱，右侧制式文字（5G / 4G / 3G / 2G）。
 * 不显示 Wi-Fi；读不到（无 SIM / Shizuku 未启动）时整块不画。
 */
@Composable
private fun NetworkIndicator(cellLevel: Int, cellKnown: Boolean, label: String) {
    if (!cellKnown) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(24.dp).height(14.dp)) {
            val bars = 4
            val gap = 2f
            val barW = (size.width - gap * (bars - 1)) / bars
            for (i in 0 until bars) {
                val h = size.height * (0.32f + 0.22f * i)
                drawRoundRect(
                    color = CarInk.copy(alpha = if (i < cellLevel) .9f else .22f),
                    topLeft = Offset(i * (barW + gap), size.height - h),
                    size = androidx.compose.ui.geometry.Size(barW, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f),
                )
            }
        }
        if (label.isNotBlank()) {
            Spacer(Modifier.width(4.dp))
            Text(label, color = CarInk.copy(alpha = .8f), fontSize = 11.sp,
                fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * 「更多应用」里的单个应用磁贴（2026-10-31）。
 *
 * 统一规格：88dp 圆角方块 + 下方文字。有系统 App 图标就整块铺满（Crop），
 * 没有就画内置矢量图标（52dp），保证所有磁贴视觉尺寸一致。
 */
@Composable
private fun AppTile(
    label: String,
    color: Color,
    icon: ImageVector? = null,
    fallback: ImageVector? = null,
    bitmap: android.graphics.Bitmap? = null,
    onClickLabel: String? = null,
    /** 小标记，例如地图的「当前」（2026-10-31）。 */
    label2: String? = null,
    onClick: () -> Unit,
) {
    Column(Modifier.width(104.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            // 有系统图标时不铺底色：Adaptive Icon 边缘透明，底色会透出来变成"外框"
            Modifier.size(88.dp).clip(RoundedCornerShape(22.dp))
                .background(if (bitmap == null) color else Color.Transparent)
                .clickable(onClickLabel = onClickLabel, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), label, Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop)
            } else {
                Icon(icon ?: fallback ?: Icons.Rounded.MusicNote, label, tint = White,
                    modifier = Modifier.size(52.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(label, color = CarInk, fontSize = 16.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (label2 != null) {
            Text(label2, color = CarAccent, fontSize = 12.sp)
        }
    }
}
