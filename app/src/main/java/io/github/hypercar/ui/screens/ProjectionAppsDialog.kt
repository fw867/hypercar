package io.github.hypercar.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hypercar.carui.CarUiSettings
import io.github.hypercar.carui.ProjectionApps
import io.github.hypercar.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ProjectionAppsDialog(onClose: () -> Unit) {
    val selected = CarUiSettings.projectionPackages
    val context = LocalContext.current
    var search by rememberSaveable { mutableStateOf("") }
    val loaded by produceState<Result<List<ProjectionApps.Candidate>>?>(null, context) {
        value = withContext(Dispatchers.IO) { runCatching { ProjectionApps.candidates(context) } }
    }
    val candidates = loaded?.getOrNull()
    val unavailable = if (candidates == null) emptySet()
        else selected - candidates.map { it.packageName }.toSet()
    val query = search.trim()
    val visible = (candidates.orEmpty() + unavailable.sorted().map { ProjectionApps.Candidate(it, it) })
        .filter { it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
        .sortedBy { it.packageName !in selected }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("自定义投屏应用") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("仅对自绘车机 UI 生效。勾选后可在车机「更多应用」中打开。",
                    color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(),
                    label = { Text("搜索应用名或包名") }, singleLine = true)
                when {
                    loaded == null -> Text("正在读取已安装应用")
                    candidates == null -> Text("读取应用列表失败，请关闭后重试")
                    visible.isEmpty() -> Text("没有符合条件的应用")
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 350.dp)) {
                        items(visible, key = { it.packageName }) { app ->
                            val detail = if (app.packageName in unavailable) "应用暂不可用" else app.packageName
                            ProjectionAppOption(app.label, detail, app.packageName in selected) {
                                CarUiSettings.updateProjectionPackage(app.packageName, it)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("完成") } },
    )
}

@Composable
private fun ProjectionAppOption(label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked, onCheckedChange = null)
        Column(Modifier.weight(1f)) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, color = TextSecondary, style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
