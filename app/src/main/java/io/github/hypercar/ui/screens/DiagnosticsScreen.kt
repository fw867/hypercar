package io.github.hypercar.ui.screens

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hypercar.BuildConfig
import io.github.hypercar.session.GitHubFeedback
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.ui.components.CapsuleButton
import io.github.hypercar.ui.components.GlassPanel
import io.github.hypercar.ui.components.SectionLabel
import io.github.hypercar.ui.theme.AmberWarning
import io.github.hypercar.ui.theme.CoralError
import io.github.hypercar.ui.theme.ElectricCyan
import io.github.hypercar.ui.theme.MintGreen
import io.github.hypercar.ui.theme.MonoLogLine
import io.github.hypercar.ui.theme.StrokeGlass
import io.github.hypercar.ui.theme.TextPrimary
import io.github.hypercar.ui.theme.TextSecondary

/**
 * 诊断页：全量日志（反转显示：最新在上）+ 记录开关 + 复制 + 导出 + 清空 + 版本信息。
 * 无需 adb，链路日志由 Rust 核心层环形缓冲直推 UI；上车调试时可一键导出到 下载/HyperCar/。
 */
@Composable
fun DiagnosticsScreen(
    logs: List<String>,
    recording: Boolean,
    lastExportPath: String?,
    onToggleRecording: (Boolean) -> Unit,
    onClearLogs: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 轻提示（复制/导出反馈）：2s 自动消失
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) {
        if (toast != null) {
            kotlinx.coroutines.delay(2_000)
            toast = null
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(
            count = logs.size,
            recording = recording,
            onToggleRecording = onToggleRecording,
            onClearLogs = onClearLogs,
            onCopy = { toast = "已复制 ${logs.size} 行日志" },
            onExport = onExport,
            onFeedback = { toast = it },
            logs = logs,
        )
        AnimatedVisibility(
            visible = toast != null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Text(
                text = toast.orEmpty(),
                style = MonoLogLine,
                color = MintGreen,
            )
        }
        ExportPathHint(path = lastExportPath, onCopyPath = { toast = "路径已复制" })
        LogList(
            logs = logs,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        VersionCard()
    }
}

/* ---------- 头部（标题 + 记录开关 + 操作行） ---------- */

@Composable
private fun Header(
    count: Int,
    recording: Boolean,
    onToggleRecording: (Boolean) -> Unit,
    onClearLogs: () -> Unit,
    onCopy: () -> Unit,
    onExport: () -> Unit,
    /** 一键反馈的结果提示（成功/失败文案交给页面统一弹 toast）。 */
    onFeedback: (String) -> Unit,
    /** 诊断：把当前 display 列表打进日志（排查副屏/虚拟屏用）。 */
    /** 诊断：抓 CarLife 的 Activity/Surface/logcat 现场。 */
    logs: List<String>,
) {
    val clipboard = LocalClipboardManager.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                SectionLabel(text = "诊断日志")
                Text(
                    text = "共 $count 条 · " + if (recording) "记录中" else "已暂停",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (recording) TextSecondary else AmberWarning,
                )
            }
            // 记录开关：暂停后不再写入环形缓冲（既有内容保留），上车抓关键段落用
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(
                    imageVector = if (recording) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (recording) "暂停记录" else "恢复记录",
                    tint = if (recording) TextSecondary else ElectricCyan,
                    modifier = Modifier.width(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Switch(
                    checked = recording,
                    onCheckedChange = onToggleRecording,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ElectricCyan,
                        checkedTrackColor = ElectricCyan.copy(alpha = 0.25f),
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = StrokeGlass,
                    ),
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 四个按钮平分一行：**不放图标**——图标 + 两个汉字在 4 等分宽度里放不下，
            // 系统会把最后一个字吞掉（2026-10-25 截图："复制"只显示"复"）。
            CapsuleButton(
                text = "复制",
                onClick = {
                    if (logs.isNotEmpty()) {
                        clipboard.setText(AnnotatedString(logs.joinToString("\n")))
                    }
                    onCopy()
                },
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                filled = false,
                contentColor = TextPrimary,
                horizontalPadding = 8.dp,
            )
            CapsuleButton(
                text = "导出",
                onClick = onExport,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                filled = false,
                contentColor = ElectricCyan,
                borderColor = ElectricCyan.copy(alpha = 0.5f),
                horizontalPadding = 8.dp,
            )
            CapsuleButton(
                text = "清空",
                onClick = onClearLogs,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                filled = false,
                contentColor = CoralError,
                borderColor = CoralError.copy(alpha = 0.5f),
                horizontalPadding = 8.dp,
            )
            // 一键反馈：预填 GitHub Issue（标题/环境/日志尾部）+ 完整日志进剪贴板。
            // 不做"静默创建"：GitHub 写 Issue 需要 token，APK 里放 token 等于公开泄露；
            // 预填 URL + 浏览器打开是零配置方案，用户点一下 Submit 就成。
            // 注意 URL 预算只有 1.6k（实测 ~7.1k 就 500，见 GitHubFeedback.MAX_URL_CHARS）。
            val context = LocalContext.current
            CapsuleButton(
                text = "反馈",
                onClick = {
                    // 先把文件也导出一份：Issue 里可以直接拖附件，比纯文本更好读
                    HyperCarRepo.exportLogs()
                    val prepared = GitHubFeedback.prepare(context, logs)
                    clipboard.setText(AnnotatedString(prepared.clipboard))
                    val opened = GitHubFeedback.open(context, prepared.url)
                    onFeedback(
                        if (opened) {
                            "已复制完整日志并打开 GitHub（需已登录）：在正文末尾粘贴、点 Submit 即可" +
                                "（可再把导出的 .txt 拖进去）"
                        } else {
                            "完整日志已复制，但没有可打开链接的应用——" +
                                "请到 github.com/${GitHubFeedback.REPO}/issues 新建后粘贴"
                        },
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                filled = true,
                containerColor = ElectricCyan,
                contentColor = io.github.hypercar.ui.theme.DeepSpace,
                borderColor = ElectricCyan,
                horizontalPadding = 8.dp,
            )
        }
    }
}

/** 导出路径提示条：点击可复制路径（文件管理器/微信按此路径找文件）。 */
@Composable
private fun ExportPathHint(path: String?, onCopyPath: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AnimatedVisibility(visible = !path.isNullOrBlank()) {
        GlassPanel(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        clipboard.setText(AnnotatedString(path.orEmpty()))
                        onCopyPath()
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "已导出 ",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                Text(
                    text = path.orEmpty(),
                    style = MonoLogLine,
                    color = MintGreen,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/* ---------- 日志列表 ---------- */

@Composable
private fun LogList(logs: List<String>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.scrollToItem(0)
    }
    GlassPanel(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (logs.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "暂无日志",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "连接一次车机后，这里会显示完整链路轨迹",
                    style = MonoLogLine,
                    color = TextSecondary,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // 反转显示：最新日志在最上方
                items(logs.reversed()) { line ->
                    DiagnosticsLogRow(line = line)
                }
            }
        }
    }
}

/** 诊断页日志行：时间戳（灰小字）+ 级别块 + 正文，等宽 11sp。 */
@Composable
private fun DiagnosticsLogRow(line: String) {
    // Repo.log 写入的行带 "HH:mm:ss.SSS  " 前缀：拆出来单独灰显，正文再走级别解析
    val tsMatch = remember(line) { TimestampPrefixRegex.find(line) }
    val timestamp = tsMatch?.groupValues?.get(1)
    val body = if (tsMatch != null) line.substring(tsMatch.range.last + 1) else line
    val parsed = remember(body) { parseLogLine(body) }
    val levelColor = logLevelColor(parsed.level)
    Row(modifier = Modifier.fillMaxWidth()) {
        if (timestamp != null) {
            Text(
                text = timestamp,
                style = MonoLogLine.copy(fontSize = 9.sp),
                color = TextSecondary.copy(alpha = 0.55f),
                modifier = Modifier.width(58.dp),
            )
        }
        Text(
            text = levelTag(parsed.level),
            style = MonoLogLine,
            color = levelColor,
            modifier = Modifier.width(18.dp),
        )
        Text(
            text = parsed.text,
            style = MonoLogLine,
            color = if (parsed.level == 'E') CoralError.copy(alpha = 0.9f) else TextSecondary,
            maxLines = 4,
        )
    }
}

/** `12:34:56.789  ` 行前缀。 */
private val TimestampPrefixRegex = Regex("""^(\d{2}:\d{2}:\d{2}\.\d{3})\s+""")

/* ---------- 版本信息 ---------- */

@Composable
private fun VersionCard() {
    GlassPanel(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
    ) {
        SectionLabel(text = "版本信息")
        Spacer(modifier = Modifier.height(6.dp))
        InfoRow(label = "版本", value = BuildConfig.VERSION_NAME)
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = StrokeGlass.copy(alpha = 0.5f),
        )
        InfoRow(label = "包名", value = BuildConfig.APPLICATION_ID)
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = StrokeGlass.copy(alpha = 0.5f),
        )
        InfoRow(
            label = "系统",
            value = "Android ${Build.VERSION.RELEASE} · SDK ${Build.VERSION.SDK_INT}",
        )
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = StrokeGlass.copy(alpha = 0.5f),
        )
        InfoRow(label = "设备", value = "${Build.MANUFACTURER} ${Build.MODEL}")
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = value,
            style = MonoLogLine,
            color = TextPrimary,
        )
    }
}

/* ---------- 日志解析（供首页预览与诊断页共用） ---------- */

/** 解析出的日志：[level] 为 'E'/'W'/'I'/'D'，未知为 null；[text] 为去掉级别前缀的正文。 */
internal data class ParsedLogLine(val level: Char?, val text: String)

/**
 * 宽松解析日志级别：
 * - 行首 `E/xx: msg`、`E msg`、`[E] msg`
 * - 或行内出现 `E/`、`W/` 这类 logger 风格前缀（Rust log / android log 两种都兼容）
 */
internal fun parseLogLine(line: String): ParsedLogLine {
    val trimmed = line.trimStart()
    if (trimmed.isEmpty()) return ParsedLogLine(null, line)
    val head = trimmed.first().uppercaseChar()
    if (head in charArrayOf('E', 'W', 'I', 'D') &&
        (trimmed.length == 1 || !trimmed[1].isLetterOrDigit())
    ) {
        return ParsedLogLine(head, stripMarker(trimmed))
    }
    val match = LogLevelRegex.find(trimmed)
    if (match != null) {
        return ParsedLogLine(match.groupValues[1][0], trimmed.substring(match.range.last + 1).trimStart())
    }
    return ParsedLogLine(null, trimmed)
}

private val LogLevelRegex = Regex("""\[?([EWID])[/:]\s?""")

private fun stripMarker(text: String): String {
    val rest = text.drop(1)
    return when {
        rest.startsWith("/") || rest.startsWith(":") || rest.startsWith("]") -> rest.drop(1).trimStart()
        rest.startsWith(" ") -> rest.trimStart()
        else -> rest
    }
}

/** 级别 → 颜色：E 红 / W 琥珀 / I 青 / D 与未知用次级灰。 */
internal fun logLevelColor(level: Char?): Color = when (level) {
    'E' -> CoralError
    'W' -> AmberWarning
    'I' -> ElectricCyan.copy(alpha = 0.8f)
    'D' -> MintGreen.copy(alpha = 0.55f)
    else -> TextSecondary
}

/** 级别标签（等宽占位，避免不同级别宽度跳动）。 */
internal fun levelTag(level: Char?): String = when (level) {
    'E' -> "E "
    'W' -> "W "
    'I' -> "I "
    'D' -> "D "
    else -> "  "
}
