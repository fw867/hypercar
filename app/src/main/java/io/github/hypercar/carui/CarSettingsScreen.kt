package io.github.hypercar.carui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CarSettingsPage(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val installed = remember(context) { MusicApps.installed(context) }
    var musicPicker by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(CarPanel)
            .verticalScroll(rememberScrollState())
            .padding(26.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(46.dp)) {
                Icon(Icons.Rounded.ArrowBack, "返回", tint = CarInk, modifier = Modifier.size(31.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text("设置", color = CarInk, fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(18.dp))

        SettingsGroup("导航") {
            SettingSwitch(
                title = "导航自动巡航",
                subtitle = "启动高德后自动进入巡航页",
                checked = CarUiSettings.autoCruise,
                onCheckedChange = { CarUiSettings.updateAutoCruise(it) },
            )
        }

        Spacer(Modifier.height(14.dp))
        SettingsGroup("音乐") {
            MusicDefaultRow(
                installed = installed,
                current = CarUiSettings.defaultMusicPackage,
                expanded = musicPicker,
                onToggle = { musicPicker = !musicPicker },
            )
            if (musicPicker) {
                Spacer(Modifier.height(8.dp))
                installed.forEach { app ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                CarUiSettings.updateDefaultMusicPackage(app.packageName)
                                MusicBridge.setDefaultPackage(app.packageName)
                                musicPicker = false
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (app.icon != null) {
                            Image(app.icon.asImageBitmap(), null, Modifier.size(32.dp).clip(CircleShape),
                                contentScale = ContentScale.Crop)
                        } else {
                            Icon(Icons.Rounded.MusicNote, null, tint = CarInk, modifier = Modifier.size(26.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(app.label, color = CarInk, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        if (app.packageName == CarUiSettings.defaultMusicPackage) {
                            Icon(Icons.Rounded.Check, "当前默认", tint = Color(0xFF22D3EE), modifier = Modifier.size(22.dp))
                        }
                    }
                }
                if (installed.isEmpty()) {
                    Text("未检测到已安装的音乐 App", color = CarMuted, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
            HorizontalDivider(color = CarMuted.copy(alpha = .18f))
            SettingSwitch(
                title = "连接自动播放",
                subtitle = "车机音频就绪后自动播放默认音乐 App",
                checked = CarUiSettings.autoPlayOnConnect,
                onCheckedChange = { CarUiSettings.updateAutoPlayOnConnect(it) },
            )
        }

        Spacer(Modifier.height(14.dp))
        SettingsGroup("主题") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemeButton("白天", Icons.Rounded.LightMode, CarUiSettings.themeMode == CarThemeMode.LIGHT,
                    Modifier.weight(1f)) { CarUiSettings.updateThemeMode(CarThemeMode.LIGHT) }
                ThemeButton("暗夜", Icons.Rounded.DarkMode, CarUiSettings.themeMode == CarThemeMode.DARK,
                    Modifier.weight(1f)) { CarUiSettings.updateThemeMode(CarThemeMode.DARK) }
                ThemeButton("自动", Icons.Rounded.DarkMode, CarUiSettings.themeMode == CarThemeMode.AUTO,
                    Modifier.weight(1f)) { CarUiSettings.updateThemeMode(CarThemeMode.AUTO) }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "自动：优先跟随车机日夜信号；未收到时跟随手机系统深色模式。",
                color = CarMuted, fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(14.dp))
        SettingsGroup("壁纸") {
            WallpaperPicker()
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun WallpaperPicker() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val mode = CarUiSettings.themeMode
    val split = mode == CarThemeMode.AUTO
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CarWallpaper.entries.forEach { wallpaper ->
            val selected = CarUiSettings.wallpaper == wallpaper
            val day = remember(wallpaper) { CarWallpapers.load(context, wallpaper, dark = false) }
            val night = remember(wallpaper) { CarWallpapers.load(context, wallpaper, dark = true) }
            Column(
                Modifier
                    .weight(1f)
                    .clickable { CarUiSettings.updateWallpaper(wallpaper) },
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) Color(0xFF2F80ED) else CarMuted.copy(alpha = .25f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        .background(CarMuted.copy(alpha = .12f)),
                ) {
                    if (split) {
                        // 自动模式：一张卡里同时展示白天/暗夜两半，和 CarLife 壁纸页一致。
                        Row(Modifier.fillMaxSize()) {
                            day?.let {
                                Image(it.asImageBitmap(), "${wallpaper.label}白天",
                                    Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop)
                            }
                            night?.let {
                                Image(it.asImageBitmap(), "${wallpaper.label}暗夜",
                                    Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop)
                            }
                        }
                    } else {
                        val preview = if (mode == CarThemeMode.LIGHT) day else night
                        preview?.let {
                            Image(it.asImageBitmap(), wallpaper.label, Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop)
                        }
                    }
                    if (selected) {
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp)
                                .clip(CircleShape).background(Color(0xFF2F80ED)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Check, "已选", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    wallpaper.label,
                    color = if (selected) Color(0xFF2F80ED) else CarMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CarCard).padding(14.dp)) {
        Text(title, color = CarMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = CarInk, fontSize = 16.sp)
            Text(subtitle, color = CarMuted, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF22D3EE),
                checkedTrackColor = Color(0xFF22D3EE).copy(alpha = .32f),
                uncheckedThumbColor = CarMuted,
                uncheckedTrackColor = CarMuted.copy(alpha = .25f),
            ),
        )
    }
}

@Composable
private fun MusicDefaultRow(
    installed: List<MusicApps.Entry>,
    current: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val label = installed.firstOrNull { it.packageName == current }?.label ?: "未设置"
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.MusicNote, null, tint = Color(0xFF8B6CFF), modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("默认音乐 App", color = CarInk, fontSize = 16.sp)
            Text(label, color = CarMuted, fontSize = 12.sp)
        }
        Text(if (expanded) "收起" else "选择", color = Color(0xFF22D3EE), fontSize = 13.sp)
    }
}

@Composable
private fun ThemeButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(0xFF22D3EE).copy(alpha = .22f) else CarMuted.copy(alpha = .10f))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = if (selected) Color(0xFF22D3EE) else CarMuted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = if (selected) Color(0xFF22D3EE) else CarInk, fontSize = 14.sp)
    }
}
