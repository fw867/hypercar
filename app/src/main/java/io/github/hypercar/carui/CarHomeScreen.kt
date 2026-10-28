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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    val music by MusicBridge.state.collectAsStateWithLifecycle()
    var time by remember { mutableStateOf(LocalTime.now()) }
    var apps by remember { mutableStateOf(false) }
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
    // Keep the encoder's display rendering even while the application page is idle.
    val pulse by rememberInfiniteTransition(label = "homeFrames").animateFloat(
        0.3f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "frame",
    )
    Box(Modifier.fillMaxSize().background(Color(0xFF05050C))) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.radialGradient(listOf(Color(0xFF25116A), Color.Transparent),
                center = Offset(size.width * .48f, size.height * 1.15f), radius = size.width * .6f))
            drawRect(Brush.radialGradient(listOf(Color(0xFF191647), Color.Transparent),
                center = Offset(size.width * .85f, 0f), radius = size.width * .45f))
            drawCircle(Color(0x01000000), pulse, Offset.Zero)
        }
        Row(Modifier.fillMaxSize().padding(end = 22.dp, top = 22.dp, bottom = 22.dp)) {
            Column(Modifier.width(110.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(time.format(DateTimeFormatter.ofPattern("HH:mm")), color = White,
                    fontSize = 25.sp, fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (charging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryStd,
                        "手机电量", tint = Muted, modifier = Modifier.size(17.dp))
                    Text("$battery%", color = Muted, fontSize = 11.sp)
                }
                Spacer(Modifier.weight(1f))
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    DockButton(Icons.Rounded.Navigation, "高德地图", Color(0xFF12B5D2)) { MapController.requestEnter() }
                    DockButton(Icons.Rounded.MusicNote, "音乐", Color(0xFF7B51EB)) {
                        launchMusic(context, music)
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { apps = !apps }, modifier = Modifier.size(52.dp)) {
                    Icon(if (apps) Icons.Rounded.Home else Icons.Rounded.Apps,
                        if (apps) "返回主页" else "应用", tint = White, modifier = Modifier.size(32.dp))
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (apps) {
                    AppsPage(Modifier.weight(1f), music)
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
                            .background(if ((index == 1) == apps) White else White.copy(alpha = .3f)))
                    }
                }
            }
        }
    }
}

@Composable
private fun DockButton(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Box(Modifier.size(52.dp).clip(RoundedCornerShape(15.dp))
        .background(Brush.linearGradient(listOf(color, color.copy(alpha = .65f))))
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = White, modifier = Modifier.size(32.dp))
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
                .background(Color(0xDD10141F)).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Navigation, null, tint = Color(0xFF60B5FF), modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(12.dp))
                Text(status, color = White, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun MusicCard(music: NowPlaying, modifier: Modifier) {
    val context = LocalContext.current
    Box(modifier.clip(CardShape).background(Brush.verticalGradient(
        listOf(Color(0xFF484265), Color(0xFF25203A), Color(0xFF171623))))) {
        music.albumArt?.let {
            Image(it.asImageBitmap(), "专辑封面", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            Color.Black.copy(alpha = .08f), Color.Black.copy(alpha = .26f),
            Color.Black.copy(alpha = .76f), Color.Black.copy(alpha = .84f)))))
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(White.copy(alpha = .92f)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.MusicNote, null, tint = Color(0xFF7461D9), modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(music.appLabel.ifBlank { "音乐" }, color = White.copy(alpha = .85f), fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                if (music.playing) Icon(Icons.Rounded.GraphicEq, "正在播放", tint = White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(music.title.ifBlank { "听点喜欢的" }, color = White, fontSize = 27.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Text(music.artist.ifBlank { if (music.available) "选择一首音乐，开始旅程" else "连接你的音乐" },
                color = White.copy(alpha = .65f), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(28.dp))
            if (music.available) {
                val progress = if (music.durationMs > 0) (music.positionMs.toFloat() / music.durationMs).coerceIn(0f, 1f) else 0f
                Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                    drawRoundRect(White.copy(alpha = .18f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f))
                    if (progress > 0) drawRoundRect(White.copy(alpha = .85f),
                        size = androidx.compose.ui.geometry.Size(size.width * progress, size.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f))
                }
                Row(Modifier.fillMaxWidth().padding(top = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(music.positionMs), color = White.copy(alpha = .5f), fontSize = 10.sp)
                    Text(formatTime(music.durationMs), color = White.copy(alpha = .5f), fontSize = 10.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    PlayerButton(Icons.Rounded.SkipPrevious, "上一首", 38) { MusicBridge.previous() }
                    PlayerButton(if (music.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (music.playing) "暂停" else "播放", 48) { MusicBridge.playPause() }
                    PlayerButton(Icons.Rounded.SkipNext, "下一首", 38) { MusicBridge.next() }
                }
            } else {
                Text("在手机上播放音乐，即可在这里控制", color = White.copy(alpha = .6f), fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { MusicBridge.openAccessSettings(context) }) {
                    Text("连接音乐", color = White, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun PlayerButton(icon: ImageVector, label: String, size: Int, click: () -> Unit) {
    IconButton(onClick = click, modifier = Modifier.size(58.dp)) {
        Icon(icon, label, tint = White, modifier = Modifier.size(size.dp))
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
private fun AppsPage(modifier: Modifier, music: NowPlaying) {
    val context = LocalContext.current
    Column(modifier.fillMaxWidth().clip(CardShape).background(Color(0xFF151426)).padding(28.dp)) {
        Text("应用", color = White, fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(42.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                DockButton(Icons.Rounded.Navigation, "高德地图", Color(0xFF12B5D2)) { MapController.requestEnter() }
                Spacer(Modifier.height(12.dp))
                Text("高德地图", color = White, fontSize = 14.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                DockButton(Icons.Rounded.MusicNote, "音乐", Color(0xFF7B51EB)) { launchMusic(context, music) }
                Spacer(Modifier.height(12.dp))
                Text(music.appLabel.ifBlank { "音乐" }, color = White, fontSize = 14.sp)
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
