package io.github.hypercar.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.hypercar.headunit.HeadUnitPicker
import io.github.hypercar.headunit.ElectricVehicleProfile
import io.github.hypercar.headunit.HeadUnitProfile
import io.github.hypercar.headunit.HeadUnitProfiles
import io.github.hypercar.headunit.RemoteProfiles
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.settings.ProjectionTarget
import io.github.hypercar.settings.SettingsRevision
import io.github.hypercar.settings.SettingsStore
import io.github.hypercar.ui.components.CarBrandBadge
import io.github.hypercar.ui.components.GlassPanel
import io.github.hypercar.ui.components.SectionLabel
import io.github.hypercar.ui.theme.AmberWarning
import io.github.hypercar.ui.theme.ElectricCyan
import io.github.hypercar.ui.theme.TextPrimary
import io.github.hypercar.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
internal fun HeadUnitPickerScreen(
    connected: Boolean, modifier: Modifier = Modifier,
    onOpenElectricVehicle: (ElectricVehicleProfile) -> Unit, onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val catalog = remember(SettingsRevision.value) { HeadUnitProfiles.catalog() }
    val selectedId = SettingsStore.headUnitProfileId
    val target = remember(SettingsRevision.value) { SettingsStore.projectionTarget }
    val current = catalog.select(selectedId)
    val groups = remember(catalog) { HeadUnitPicker.groups(catalog.profiles) }
    var groupId by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var pendingId by rememberSaveable { mutableStateOf<String?>(null) }
    var targetMac by rememberSaveable { mutableStateOf<String?>(null) }
    var targetName by rememberSaveable { mutableStateOf<String?>(null) }
    var keepTarget by rememberSaveable { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var selectionError by rememberSaveable { mutableStateOf<String?>(null) }
    val group = groups.firstOrNull { it.id == groupId }
    val searching = query.isNotBlank()
    val electricVehicles = if (searching) remember(query) { HeadUnitPicker.electricVehicles(query) }
        else group?.electricVehicles.orEmpty()
    val candidates = when {
        searching -> remember(catalog, query) { HeadUnitPicker.search(catalog.profiles, query) }
        group != null -> group.profiles
        else -> emptyList()
    }

    fun back() {
        when {
            searching -> query = ""
            groupId != null -> groupId = null
            else -> onClose()
        }
    }

    fun choose(profile: HeadUnitProfile) {
        if (profile.id == selectedId) {
            if (target != ProjectionTarget.CarPlay) HyperCarRepo.setProjectionTarget(context, ProjectionTarget.CarPlay)
            onClose()
            return
        }
        targetMac = SettingsStore.targetCarMac
        targetName = SettingsStore.targetCarName
        keepTarget = true
        selectionError = null
        pendingId = profile.id
    }

    BackHandler { back() }
    Column(
        modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = ::back) { Text("‹ 返回", color = ElectricCyan) }
            Text("选择车机档案", color = TextPrimary, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f))
            TextButton(enabled = !refreshing, onClick = {
                refreshing = true
                message = null
                scope.launch {
                    try {
                        val count = RemoteProfiles.refresh(context)
                        SettingsRevision.bump()
                        message = "已更新，当前可用 $count 个远程档案"
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        message = "更新失败：${failure.message ?: "网络不可用"}"
                    } finally {
                        refreshing = false
                    }
                }
            }) { Text(if (refreshing) "更新中" else "更新档案") }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("搜索品牌、车型或车机系统") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "清空搜索") }
                }
            },
            singleLine = true,
        )
        if (message != null) {
            Text(message.orEmpty(), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        val blockedMessage = if (connected) "断开连接后可切换档案" else current.blockReason
        key(searching, groupId) {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                if (blockedMessage != null || (!searching && groupId == null)) {
                    item("current") {
                        GlassPanel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
                            val currentLabel = if (target == ProjectionTarget.CarPlay) "当前档案" else "已保存的 CarPlay 档案"
                            Text("$currentLabel：${current.label}", color = TextPrimary, style = MaterialTheme.typography.titleSmall)
                            if (blockedMessage != null) {
                                Text(blockedMessage, color = AmberWarning, style = MaterialTheme.typography.bodySmall)
                            }
                            if (target != ProjectionTarget.CarPlay) {
                                TextButton(enabled = !connected, onClick = {
                                    HyperCarRepo.setProjectionTarget(context, ProjectionTarget.CarPlay)
                                }) { Text("使用 CarPlay 车机") }
                            }
                        }
                    }
                }
                if (searching || group != null) {
                    item("heading") { SectionLabel(if (searching) "搜索结果" else group?.label.orEmpty()) }
                    if (candidates.isEmpty() && electricVehicles.isEmpty()) {
                        item("empty") {
                            Text("没有找到匹配的档案", color = TextSecondary, modifier = Modifier.padding(8.dp))
                        }
                    }
                    items(candidates, key = { it.id }) { profile ->
                        val extra = (listOf(profile.listing.platformName) + profile.listing.vehicleModels)
                            .filterNot { it.isBlank() || profile.model.contains(it, ignoreCase = true) }.distinct()
                        val detail = (listOf(profile.model).filter { it.isNotBlank() } + extra).joinToString(" · ")
                        PickerRow(profile.label, detail, profile.id, profile.label,
                            selected = target == ProjectionTarget.CarPlay && profile.id == selectedId,
                            enabled = !connected, notes = profile.notes) { choose(profile) }
                    }
                    items(electricVehicles, key = { "electric:${it.id}" }) { profile ->
                        PickerRow(profile.label, profile.model, profile.id, profile.label,
                            selected = target == profile.target) { onOpenElectricVehicle(profile) }
                    }
                } else {
                    item("heading") { SectionLabel("按品牌浏览") }
                    items(groups, key = { it.id }) { entry ->
                        val badge = entry.profiles.firstOrNull()
                        if (badge == null) {
                            PickerRow(entry.label, entry.electricVehicles.joinToString(" · ") { it.label },
                                entry.id, entry.label, selected = entry.electricVehicles.any { it.target == target }) {
                                groupId = entry.id
                            }
                        } else {
                            val detail = entry.profiles.take(3).joinToString(" · ") {
                                it.listing.platformName.ifBlank { it.model }
                            }
                            PickerRow(entry.label, detail, badge.id, badge.label,
                                selected = target == ProjectionTarget.CarPlay &&
                                    entry.profiles.any { it.id == selectedId }) { groupId = entry.id }
                        }
                    }
                }
            }
        }
    }

    pendingId?.let { id ->
        val pending = catalog.select(id)
        val problem = when {
            connected -> "连接中，请断开后再切换档案"
            pending.blockReason != null -> pending.blockReason
            else -> selectionError
        }
        AlertDialog(
            onDismissRequest = { if (!saving) pendingId = null },
            title = { Text(pending.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    pending.profile?.let { Text(it.notes, color = TextSecondary) }
                    if (targetMac != null) {
                        Column(Modifier.selectableGroup()) {
                            TargetChoice("沿用目标车机：${targetName?.ifBlank { null } ?: targetMac}", keepTarget) {
                                keepTarget = true
                            }
                            TargetChoice("清空目标，稍后重新选择", !keepTarget) { keepTarget = false }
                        }
                    }
                    if (problem != null) Text(problem, color = AmberWarning)
                }
            },
            dismissButton = { TextButton(enabled = !saving, onClick = { pendingId = null }) { Text("取消") } },
            confirmButton = {
                TextButton(enabled = !connected && !saving && pending.profile != null, onClick = {
                    saving = true
                    scope.launch {
                        try {
                            val result = HyperCarRepo.selectHeadUnitProfile(id, targetMac, keepTarget)
                            if (result == null) {
                                if (target != ProjectionTarget.CarPlay)
                                    HyperCarRepo.setProjectionTarget(context, ProjectionTarget.CarPlay)
                                onClose()
                            } else {
                                selectionError = result
                                val currentTarget = SettingsStore.targetCarMac
                                if (targetMac != currentTarget) {
                                    targetMac = currentTarget
                                    targetName = SettingsStore.targetCarName
                                    keepTarget = true
                                }
                            }
                        } finally {
                            saving = false
                        }
                    }
                }) { Text("使用此档案") }
            },
        )
    }
}

@Composable
private fun PickerRow(title: String, detail: String, badgeId: String, badgeLabel: String, selected: Boolean,
                      enabled: Boolean = true, notes: String = "", onClick: () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CarBrandBadge(badgeId, badgeLabel, size = 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = if (selected) ElectricCyan else TextPrimary)
                if (detail.isNotBlank()) Text(detail, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                if (notes.isNotBlank()) Text(notes, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            if (selected) Icon(Icons.Rounded.Check, "已选择", tint = ElectricCyan)
            else Text("›", color = TextSecondary)
        }
    }
}

@Composable
private fun TargetChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected, onClick = null)
        Text(label)
    }
}
