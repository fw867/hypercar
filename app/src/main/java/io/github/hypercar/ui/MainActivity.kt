package io.github.hypercar.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hypercar.core.LinkState
import io.github.hypercar.core.SessionSnapshot
import io.github.hypercar.perm.AppPermissions
import io.github.hypercar.perm.PermGroup
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.ui.screens.AboutScreen
import io.github.hypercar.ui.screens.DiagnosticsScreen
import io.github.hypercar.ui.screens.HomeScreen
import io.github.hypercar.ui.screens.SettingsScreen
import io.github.hypercar.ui.theme.AmberWarning
import io.github.hypercar.ui.theme.CoralError
import io.github.hypercar.ui.theme.DeepSpace
import io.github.hypercar.ui.theme.ElectricCyan
import io.github.hypercar.ui.theme.HyperCarBackground
import io.github.hypercar.ui.theme.HyperCarTheme
import io.github.hypercar.ui.theme.MintGreen
import io.github.hypercar.ui.theme.SurfaceInset
import io.github.hypercar.ui.theme.SurfaceNavBar
import io.github.hypercar.update.UpdateCenter
import io.github.hypercar.update.UpdateUiState
import io.github.hypercar.ui.theme.TextPrimary
import kotlinx.coroutines.delay
import io.github.hypercar.ui.theme.TextSecondary

/** 单 Activity：底部三 Tab（主页 / 设置 / 诊断），state-based 切换，无需 navigation-compose。 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 沉浸式：edge-to-edge + 透明状态栏，深空背景直铺到系统栏后面
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContent {
            HyperCarTheme {
                MainContent()
            }
        }
    }
}

private enum class AppTab(val title: String, val icon: ImageVector) {
    Home("主页", Icons.Rounded.Home),
    Settings("设置", Icons.Rounded.Settings),
    Diagnostics("诊断", Icons.Rounded.BugReport),
    About("关于", Icons.Rounded.Info),
}

@Composable
private fun MainContent() {
    val snapshot by HyperCarRepo.snapshots.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val tabs = AppTab.entries
    val current = tabs[tabIndex.coerceIn(tabs.indices)]

    // 权限快照：permVersion 变更即重算（申请结果回来后、或从系统设置返回时）
    var permVersion by remember { mutableIntStateOf(0) }
    var resumedOnce by remember { mutableStateOf(false) }
    val permissions = remember(permVersion) { AppPermissions.summary(context) }
    val locationServiceOn = remember(permVersion) { AppPermissions.isLocationServiceOn(context) }

    // 从系统设置页返回（用户手动开权限/自启动/电池）后刷新清单
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permVersion++
                resumedOnce = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permVersion++
        // 降级模式的连接预检也依赖这些权限，结果回来后刷新 Repo 快照
        HyperCarRepo.onDegradedPermissionResult()
    }

    // 首启自动申请一次必需权限（只一次）：HyperOS 对侧载应用默认拒绝通知等，
    // 不主动申请用户根本不知道要授权。必须等 Activity 真正 RESUMED 再弹，
    // 否则系统会直接丢弃请求。
    LaunchedEffect(resumedOnce) {
        if (!resumedOnce || AppPermissions.wasPrompted(context)) return@LaunchedEffect
        AppPermissions.markPrompted(context)
        val all = AppPermissions.missingRequired(context)
            .flatMap { AppPermissions.perms(it).toList() }
            .distinct()
        if (all.isEmpty()) return@LaunchedEffect
        delay(300)
        permissionLauncher.launch(all.toTypedArray())
    }

    fun requestGroup(group: PermGroup) {
        val perms = AppPermissions.perms(group)
        if (perms.isNotEmpty()) permissionLauncher.launch(perms)
    }

    // ---- 自动更新：启动时按 6 小时节流检查一次；有新版弹一次（可"稍后"）----
    val updateState by UpdateCenter.state.collectAsStateWithLifecycle()
    var updateDialogClosed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (UpdateCenter.shouldAutoCheck(context)) UpdateCenter.check(context)
    }
    val newRelease = (updateState as? UpdateUiState.Available)?.info
    if (newRelease != null && !updateDialogClosed &&
        !UpdateCenter.isDismissed(context, newRelease.tag)
    ) {
        AlertDialog(
            onDismissRequest = { updateDialogClosed = true },
            containerColor = SurfaceInset,
            title = {
                Text(
                    text = "发现新版本 ${newRelease.version}",
                    color = TextPrimary,
                )
            },
            text = {
                Text(
                    text = buildString {
                        append("当前版本 ${UpdateCenter.currentVersion}。")
                        if (newRelease.notes.isNotBlank()) {
                            append("\n\n")
                            append(newRelease.notes.take(600))
                        }
                    },
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    updateDialogClosed = true
                    val url = newRelease.apkUrl ?: newRelease.pageUrl
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }) { Text("去下载", color = ElectricCyan) }
            },
            dismissButton = {
                TextButton(onClick = {
                    updateDialogClosed = true
                    UpdateCenter.dismiss(context, newRelease.tag)
                }) { Text("稍后", color = TextSecondary) }
            },
        )
    }

    Scaffold(
        containerColor = DeepSpace,
        topBar = { StatusStrip(link = snapshot.link) },
        bottomBar = {
            NavigationBar(
                containerColor = SurfaceNavBar,
                tonalElevation = 0.dp,
            ) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = tabIndex == index,
                        onClick = { tabIndex = index },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.title,
                                modifier = Modifier.size(22.dp),
                            )
                        },
                        label = {
                            Text(
                                text = tab.title,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = ElectricCyan,
                            selectedTextColor = ElectricCyan,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = ElectricCyan.copy(alpha = 0.14f),
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            HyperCarBackground()
            Crossfade(
                targetState = current,
                animationSpec = tween(300),
                label = "tab-crossfade",
            ) { tab ->
                when (tab) {
                    AppTab.Home -> HomeScreen(
                        snapshot = snapshot,
                        onStart = { HyperCarRepo.connect(context) },
                        onStop = { HyperCarRepo.disconnect() },
                        onOpenDiagnostics = { tabIndex = AppTab.Diagnostics.ordinal },
                        onRequestDegradedPermission = {
                            permissionLauncher.launch(HyperCarRepo.degradedPermissionsToRequest())
                        },
                        permissions = permissions,
                        locationServiceOn = locationServiceOn,
                        onRequestPermission = { requestGroup(it) },
                        onRequestAllPermissions = {
                            val all = AppPermissions.missingRequired(context)
                                .flatMap { AppPermissions.perms(it).toList() }
                                .distinct()
                            if (all.isNotEmpty()) permissionLauncher.launch(all.toTypedArray())
                        },
                        onOpenOverlay = { AppPermissions.openOverlaySettings(context) },
                        onBatteryOptimization = {
                            AppPermissions.requestIgnoreBatteryOptimizations(context)
                        },
                        onAutostart = {
                            if (!AppPermissions.openAutostartSettings(context)) {
                                AppPermissions.openAppDetails(context)
                            }
                        },
                        onOpenShizukuSettings = { tabIndex = AppTab.Settings.ordinal },
                    )

                    AppTab.Settings -> SettingsScreen(
                            linkConnected = snapshot.link is io.github.hypercar.core.LinkState.Connected ||
                                snapshot.link is io.github.hypercar.core.LinkState.Negotiating,)

                    AppTab.Diagnostics -> DiagnosticsScreen(
                        logs = snapshot.recentLogs,
                        recording = snapshot.logRecording,
                        lastExportPath = snapshot.lastExportPath,
                        onToggleRecording = { HyperCarRepo.setLogRecording(it) },
                        onClearLogs = { HyperCarRepo.clearLogs() },
                        onExport = { HyperCarRepo.exportLogs() },
                    )

                    AppTab.About -> AboutScreen()
                }
            }
        }
    }
}

/**
 * 顶部窄条：连接态色点 + HyperCar 字标 + 车名缩写。
 */
@Composable
private fun StatusStrip(link: LinkState, modifier: Modifier = Modifier) {
    val dotColor = when (link) {
        is LinkState.Connected -> MintGreen
        is LinkState.Negotiating -> ElectricCyan
        is LinkState.Failed -> CoralError
        is LinkState.Stopping -> AmberWarning
        is LinkState.Idle -> TextSecondary
    }
    val carAbbreviation = (link as? LinkState.Connected)?.carName
        ?.uppercase()
        ?.take(4)
        ?: when (link) {
            is LinkState.Negotiating -> "协商中"
            is LinkState.Failed -> "连接失败"
            is LinkState.Stopping -> "断开中"
            else -> "待机"
        }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(DeepSpace)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, CircleShape),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "HyperCar",
            style = MaterialTheme.typography.titleSmall.copy(
                letterSpacing = 2.sp,
                fontFamily = FontFamily.Monospace,
            ),
            color = TextPrimary,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = carAbbreviation,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}


