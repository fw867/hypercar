package io.github.hypercar.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hypercar.ui.theme.ElectricCyan
import io.github.hypercar.ui.theme.TextPrimary
import io.github.hypercar.ui.theme.TextSecondary

/**
 * 「标签 — 值」行：左侧次级色标签、右侧主色值；传 [onClick] 时整行可点；[emphasizeClick] 控制强调色和点击反馈。
 * 设置页与关于页共用。
 */
@Composable
fun InfoRow(
    label: String,
    value: String,
    emphasizeClick: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(when {
                onClick == null -> Modifier
                emphasizeClick -> Modifier.clickable(onClick = onClick)
                else -> Modifier.clickable(interactionSource = null, indication = null, onClick = onClick)
            })
            .heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = if (onClick != null && emphasizeClick) ElectricCyan else TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
