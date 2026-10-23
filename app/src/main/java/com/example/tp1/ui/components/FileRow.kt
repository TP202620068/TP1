package com.example.tp1.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal enum class FileKind { Folder, Pdf, Image, Document }

internal data class FileListItem(
    val name: String,
    val kind: FileKind,
    val metadata: String? = null,
    val id: String? = null
)

@Composable
internal fun FileRow(
    item: FileListItem,
    modifier: Modifier = Modifier,
    trailingContent: @Composable () -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    val icon = when (item.kind) {
        FileKind.Folder -> Icons.Rounded.Folder
        FileKind.Pdf -> Icons.Rounded.PictureAsPdf
        FileKind.Image -> Icons.Rounded.Image
        FileKind.Document -> Icons.Rounded.Description
    }
    val container = when (item.kind) {
        FileKind.Pdf -> colors.errorContainer
        FileKind.Image -> colors.secondaryContainer
        else -> colors.primaryContainer
    }
    val tint = when (item.kind) {
        FileKind.Pdf -> colors.onErrorContainer
        FileKind.Image -> colors.onSecondaryContainer
        else -> colors.onPrimaryContainer
    }

    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 64.dp)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = MaterialTheme.shapes.small,
                color = container,
                contentColor = tint
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis
                )
                item.metadata?.let { metadata ->
                    Text(
                        text = metadata,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            trailingContent()
        }
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.35f))
    }
}
