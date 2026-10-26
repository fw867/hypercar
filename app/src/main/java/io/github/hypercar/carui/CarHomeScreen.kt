package io.github.hypercar.carui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hypercar.ui.theme.CoralError
import io.github.hypercar.ui.theme.DeepSpace
import io.github.hypercar.ui.theme.ElectricCyan
import io.github.hypercar.ui.theme.MintGreen
import io.github.hypercar.ui.theme.PanelGlass
import io.github.hypercar.ui.theme.StrokeGlass
import io.github.hypercar.ui.theme.TextPrimary
import io.github.hypercar.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val hhmm: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun CarHomeScreen() {
    val nowPlaying by MusicBridge.state.collectAsStateWithLifecycle()
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    var showApps by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1_000)
        }
    }

    HomeContent(
        now = now,
        nowPlaying = nowPlaying,
        showApps = showApps,
        onToggleApps = { showApps = !showApps },
        onMapClick = { MapController.requestEnter() },
    )
}

@Composable
private fun HomeContent(
    now: LocalDateTime,
    nowPlaying: NowPlaying,
    showApps: Boolean,
    onToggleApps: () -> Unit,
    onMapClick: () -> Unit,
) {
    // 顶层帧脉冲：切到 AppsPage 后 MapCard 被移出组合树，否则整屏只剩 1s 时钟，
    // 编码 Surface 会掉到 1fps，点图标要等半秒~1 秒才看到反馈。
    val frameKeepAlive by rememberInfiniteTransition(label = "d1KeepAlive").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "d1Pulse",
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF05070C), Color(0xFF09131E), Color(0xFF05070C)),
                ),
            ),
    ) {
        Canvas(Modifier.size(1.dp)) {
            drawCircle(Color(0x0100E5FF), radius = frameKeepAlive)
        }
        Row(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
            DockRail(
                Modifier.width(96.dp).fillMaxHeight(),
                showApps = showApps,
                onToggleApps = onToggleApps,
                onMapClick = onMapClick,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                TopBar(now)
                Spacer(Modifier.height(10.dp))
                if (showApps) {
                    AppsPage(Modifier.weight(1f), onMapClick = onMapClick)
                } else {
                    Row(Modifier.weight(1f)) {
                        MapCard(Modifier.weight(1.62f).fillMaxHeight(), onClick = onMapClick)
                        Spacer(Modifier.width(16.dp))
                        MusicCard(nowPlaying, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(now: LocalDateTime) {
    Row(
        Modifier.fillMaxWidth().height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "HYPERCAR",
                color = ElectricCyan,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
            )
            Text("CarPlay 无线投屏 · 自绘车机界面", color = TextSecondary, fontSize = 11.sp)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                now.format(hhmm),
                color = TextPrimary,
                fontSize = 44.sp,
                fontWeight = FontWeight.Light,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "${now.monthValue}月${now.dayOfMonth}日 ${weekdayCn(now)}",
                color = TextSecondary,
                fontSize = 11.sp,
            )
        }
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusChip("CarPlay 在线", accent = true)
            Spacer(Modifier.width(8.dp))
            StatusChip("1920×720")
            Spacer(Modifier.width(8.dp))
            StatusChip("60 fps")
        }
    }
}

@Composable
private fun ConnectionCard(modifier: Modifier = Modifier) {
    GlassCard(modifier) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(MintGreen.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Wifi, null, tint = MintGreen, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("CarPlay 已连接", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("无线投屏 · 车机端实时画面", color = TextSecondary, fontSize = 11.sp)
            }
            Spacer(Modifier.weight(1f))
            Icon(Icons.Rounded.DirectionsCar, null, tint = ElectricCyan.copy(alpha = 0.8f), modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun MapCard(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "mapPulse").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "pulse",
    )
    GlassCard(modifier.clickable { onClick() }) {
        Canvas(Modifier.fillMaxSize()) { drawMapBackdrop(pulse) }
        Column(
            Modifier.fillMaxSize().padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            SearchPill("搜索目的地")
            Column {
                Text("地图", color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("点击打开高德地图 · 顶部下滑返回", color = TextSecondary, fontSize = 13.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill("实时路况")
                Spacer(Modifier.width(8.dp))
                Pill("车机导航")
                Spacer(Modifier.weight(1f))
                Text("全屏 ›", color = ElectricCyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun MusicCard(nowPlaying: NowPlaying, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clickToGrant = if (!nowPlaying.available) {
        Modifier.clickable { MusicBridge.openAccessSettings(context) }
    } else {
        Modifier
    }
    GlassCard(modifier.then(clickToGrant)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("正在播放", color = TextSecondary, fontSize = 12.sp, letterSpacing = 1.sp)
                Spacer(Modifier.weight(1f))
                if (nowPlaying.available) {
                    Text(nowPlaying.appLabel, color = ElectricCyan, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(150.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF0B1622))
                        .border(1.dp, StrokeGlass, RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    val art = nowPlaying.albumArt
                    if (art != null) {
                        Image(
                            bitmap = art.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Icon(
                            Icons.Rounded.MusicNote,
                            null,
                            tint = TextSecondary,
                            modifier = Modifier.size(56.dp),
                        )
                    }
                }
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        nowPlaying.title.ifBlank { "未在播放" },
                        color = TextPrimary,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        nowPlaying.artist.ifBlank {
                            nowPlaying.reason.ifBlank { "打开手机音乐 App 播放一首" }
                        },
                        color = TextSecondary,
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (nowPlaying.available) {
                Text(
                    "${formatTime(nowPlaying.positionMs)} / ${formatTime(nowPlaying.durationMs)}",
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(34.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { MusicBridge.previous() }, modifier = Modifier.size(52.dp)) {
                        Icon(
                            Icons.Rounded.SkipPrevious,
                            "上一首",
                            tint = TextPrimary,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                    IconButton(
                        onClick = { MusicBridge.playPause() },
                        modifier = Modifier.size(68.dp).clip(CircleShape).background(ElectricCyan),
                    ) {
                        Icon(
                            if (nowPlaying.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            "播放/暂停",
                            tint = DeepSpace,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                    IconButton(onClick = { MusicBridge.next() }, modifier = Modifier.size(52.dp)) {
                        Icon(
                            Icons.Rounded.SkipNext,
                            "下一首",
                            tint = TextPrimary,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            } else {
                Spacer(Modifier.weight(1f))
                Text(
                    nowPlaying.reason.ifBlank { "打开手机音乐 App 播放一首" },
                    color = TextSecondary,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "需要授予「通知使用权」才能读取 QQ音乐 / 汽水音乐 的播放状态（点卡片去系统设置）",
                    color = TextSecondary.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                )
            }
        }
    }
}
@Composable
private fun DockRail(
    modifier: Modifier,
    showApps: Boolean,
    onToggleApps: () -> Unit,
    onMapClick: () -> Unit,
) {
    GlassCard(modifier) {
        Column(
            Modifier.fillMaxSize().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DockRailItem(Icons.Rounded.Map, "地图", active = !showApps, onClick = onMapClick)
                DockRailItem(Icons.Rounded.MusicNote, "音乐", onClick = {})
                DockRailItem(Icons.Rounded.Phone, "电话", onClick = {})
                DockRailItem(Icons.Rounded.Settings, "设置", onClick = {})
            }
            DockRailItem(
                Icons.Rounded.Apps,
                if (showApps) "首页" else "更多",
                active = showApps,
                onClick = onToggleApps,
            )
        }
    }
}

private data class CarApp(
    val icon: ImageVector,
    val label: String,
    val onClick: (() -> Unit)? = null,
)

@Composable
private fun AppsPage(modifier: Modifier, onMapClick: () -> Unit) {
    val apps = listOf(
        CarApp(Icons.Rounded.Map, "地图", onMapClick),
        CarApp(Icons.Rounded.MusicNote, "音乐"),
        CarApp(Icons.Rounded.Phone, "电话"),
        CarApp(Icons.Rounded.Settings, "设置"),
        CarApp(Icons.Rounded.Navigation, "高德", onMapClick),
        CarApp(Icons.Rounded.LibraryMusic, "QQ音乐"),
        CarApp(Icons.Rounded.Radio, "收音机"),
        CarApp(Icons.Rounded.Apps, "更多"),
    )
    GlassCard(modifier) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("应用", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("D1 自绘 · 点击打开", color = TextSecondary, fontSize = 12.sp)
            }
            Spacer(Modifier.height(18.dp))
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                apps.chunked(4).forEach { rowApps ->
                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        rowApps.forEach { app ->
                            AppTile(app, Modifier.weight(1f).fillMaxHeight())
                        }
                        repeat(4 - rowApps.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppTile(app: CarApp, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0x99101826))
            .border(1.dp, StrokeGlass, RoundedCornerShape(20.dp))
            .clickable { app.onClick?.invoke() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(ElectricCyan.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                app.icon,
                app.label,
                tint = ElectricCyan,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(app.label, color = TextPrimary, fontSize = 13.sp)
    }
}
@Composable
private fun DockRailItem(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            label,
            tint = if (active) ElectricCyan else TextSecondary,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(label, color = if (active) ElectricCyan else TextSecondary, fontSize = 10.sp)
    }
}

@Composable
private fun SearchPill(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(Color(0xCC0B1622))
            .border(1.dp, StrokeGlass, RoundedCornerShape(23.dp))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = TextSecondary, fontSize = 14.sp)
    }
}

@Composable
private fun StatusChip(text: String, accent: Boolean = false) {
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (accent) MintGreen.copy(alpha = 0.10f) else Color(0x99101826))
            .border(
                1.dp,
                if (accent) MintGreen.copy(alpha = 0.35f) else StrokeGlass,
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, color = if (accent) MintGreen else TextSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun Pill(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x99101826))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(text, color = ElectricCyan.copy(alpha = 0.85f), fontSize = 11.sp)
    }
}

@Composable
private fun GlassCard(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(PanelGlass)
            .border(1.dp, StrokeGlass, RoundedCornerShape(22.dp)),
        content = content,
    )
}

private fun DrawScope.drawMapBackdrop(pulse: Float = 1f) {
    drawRect(Color(0xFF08131C))
    val road = Color(0xFF15303B)
    for (i in 1..8) {
        drawLine(road, Offset(size.width * i / 9f, 0f), Offset(size.width * i / 9f, size.height), 2f)
    }
    for (i in 1..5) {
        drawLine(road, Offset(0f, size.height * i / 6f), Offset(size.width, size.height * i / 6f), 2f)
    }
    drawLine(
        Color(0xFF1C4557),
        Offset(0f, size.height * 0.74f),
        Offset(size.width, size.height * 0.30f),
        10f,
    )
    drawLine(
        Color(0xFF1C4557),
        Offset(size.width * 0.16f, size.height),
        Offset(size.width * 0.55f, 0f),
        6f,
    )
    drawLine(
        ElectricCyan.copy(alpha = 0.5f),
        Offset(0f, size.height * 0.74f),
        Offset(size.width * 0.56f, size.height * 0.47f),
        5f,
    )
    drawCircle(
        ElectricCyan.copy(alpha = 0.10f + 0.12f * pulse),
        radius = 20f + 22f * pulse,
        center = Offset(size.width * 0.56f, size.height * 0.47f),
    )
    drawCircle(
        ElectricCyan,
        radius = 11f,
        center = Offset(size.width * 0.56f, size.height * 0.47f),
    )
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1_000
    return "%d:%02d".format(total / 60, total % 60)
}

private fun weekdayCn(time: LocalDateTime): String = when (time.dayOfWeek.value) {
    1 -> "周一"
    2 -> "周二"
    3 -> "周三"
    4 -> "周四"
    5 -> "周五"
    6 -> "周六"
    else -> "周日"
}

