package io.github.hypercar.carui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Real partial/final results only; no placeholder transcription or command execution. */
@Composable
fun CarSpeechPanel(modifier: Modifier = Modifier) {
    val state by CarSpeechValidation.state.collectAsState()
    if (state.message.isEmpty()) return
    Surface(modifier = modifier, shape = MaterialTheme.shapes.large, shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text("语音字幕验证", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { CarSpeechValidation.cancel() }) { Text(if (state.active) "取消" else "关闭") }
            }
            val context = androidx.compose.ui.platform.LocalContext.current
            if (state.final && !state.active) TextButton(onClick = { CarSpeechValidation.sendFinalText(context) }) {
                Text("交给手机小爱")
            }
            Text(state.message, style = MaterialTheme.typography.bodyMedium)
            if (state.text.isNotEmpty()) Text(state.text, style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 8.dp))
            if (state.bytesWritten > 0) Text("已提交车机音频 ${state.bytesWritten / 32} 毫秒；不代表识别器已采用",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
