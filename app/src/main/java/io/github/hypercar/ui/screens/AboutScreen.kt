package io.github.hypercar.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hypercar.R
import io.github.hypercar.ui.components.CapsuleButton
import io.github.hypercar.ui.components.GlassPanel
import io.github.hypercar.ui.components.InfoRow
import io.github.hypercar.ui.components.SectionLabel
import io.github.hypercar.ui.theme.CoralError
import io.github.hypercar.ui.theme.MintGreen
import io.github.hypercar.ui.theme.TextPrimary
import io.github.hypercar.ui.theme.TextSecondary
import io.github.hypercar.update.UpdateCenter
import io.github.hypercar.update.UpdateUiState
import kotlinx.coroutines.launch

/**
 * 「关于」页（独立 Tab，位于诊断之后）：版本信息、作者、仓库、以及更新检查。
 *
 * 更新检查逻辑在 [UpdateCenter]，这里只负责呈现：空闲 / 检查中 / 已最新 / 有新版本 / 失败。
 * 启动时的自动检查与弹窗仍在 MainActivity（与页面无关）。
 */
@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by UpdateCenter.state.collectAsStateWithLifecycle()
    val checking = state is UpdateUiState.Checking

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---- 品牌头 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_glyph),
                contentDescription = null,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(18.dp)),
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = "HyperCar",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = TextPrimary,
                )
                Text(
                    text = "本机自绘 · 零转码 · 宝马 AirPlay 网关",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }

        // ---- 版本 ----
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(text = "版本")
            GlassPanel(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                InfoRow(label = "当前版本", value = UpdateCenter.currentVersion)
                InfoRow(label = "versionCode", value = UpdateCenter.currentBuild.toString())
                InfoRow(label = "核心层", value = "Rust · arm64-v8a")
            }
        }

        // ---- 关于 ----
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(text = "关于")
            GlassPanel(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                InfoRow(label = "作者", value = "fw867")
                InfoRow(
                    label = "开源仓库",
                    value = "github.com/fw867/hypercar",
                    onClick = { openUrl(context, "https://github.com/fw867/hypercar") },
                )
                InfoRow(label = "更新来源", value = "GitHub Releases")
            }
        }

        // ---- 更新 ----
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(text = "更新")
            GlassPanel(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                when (val s = state) {
                    is UpdateUiState.Available -> {
                        Text(
                            text = "发现新版本 ${s.info.version}（当前 ${UpdateCenter.currentVersion}）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MintGreen,
                        )
                        if (s.info.notes.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = s.info.notes.take(400),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        CapsuleButton(
                            text = if (s.info.apkUrl != null) "下载新版本" else "打开 Release 页面",
                            onClick = { openUrl(context, s.info.apkUrl ?: s.info.pageUrl) },
                            modifier = Modifier.fillMaxWidth(),
                            filled = true,
                            containerColor = MintGreen,
                        )
                    }

                    is UpdateUiState.UpToDate -> Text(
                        text = "已是最新版本（${s.latest}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )

                    is UpdateUiState.Failed -> Text(
                        text = "检查失败：${s.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = CoralError,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )

                    UpdateUiState.Checking -> Text(
                        text = "正在检查…",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )

                    UpdateUiState.Idle -> Text(
                        text = "更新来自 GitHub Releases；每次推送由 CI 自动构建正式签名包。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                CapsuleButton(
                    text = if (checking) "检查中…" else "检查更新",
                    onClick = { scope.launch { UpdateCenter.check(context) } },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !checking,
                    filled = false,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
