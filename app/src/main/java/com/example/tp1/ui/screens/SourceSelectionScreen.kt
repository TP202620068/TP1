package com.example.tp1.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.tp1.R
import com.example.tp1.sources.SelectedSource
import com.example.tp1.sources.SourceKind
import com.example.tp1.ui.components.FileRow
import com.example.tp1.ui.components.SectionHeading
import com.example.tp1.ui.toFileListItem

@Composable
internal fun SourceSelectionScreen(
    sources: List<SelectedSource>,
    busy: Boolean,
    storageReady: Boolean,
    canClose: Boolean,
    message: String?,
    onPick: (SourceKind) -> Unit,
    onRemove: (String) -> Unit,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars)
                .background(MaterialTheme.colorScheme.background))
        },
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().consumeWindowInsets(insets),
            contentPadding = PaddingValues(
                start = 16.dp + insets.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                end = 16.dp + insets.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                top = insets.calculateTopPadding() + 24.dp,
                bottom = insets.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.source_selection_title),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.semantics { heading() }
                    )
                    Text(
                        stringResource(R.string.source_selection_description),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onPick(SourceKind.Tree) },
                        enabled = storageReady && !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                        Text(stringResource(R.string.select_folders), Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(
                        onClick = { onPick(SourceKind.Document) },
                        enabled = storageReady && !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Description, contentDescription = null)
                        Text(stringResource(R.string.select_files), Modifier.padding(start = 8.dp))
                    }
                }
            }
            message?.let { text ->
                item {
                    Column {
                        Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        if (!storageReady) {
                            TextButton(onClick = onRetry, enabled = !busy) { Text(stringResource(R.string.retry)) }
                        }
                    }
                }
            }
            if (sources.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.no_selected_files),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            } else {
                item { SectionHeading(R.string.selected_sources, sources.size) }
                items(sources, key = { it.record.uri }) { source ->
                    val item = source.toFileListItem()
                    FileRow(item) {
                        IconButton(onClick = { onRemove(source.record.uri) }, enabled = storageReady && !busy) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.remove_selected_source, item.name)
                            )
                        }
                    }
                }
                item {
                    Text(
                        stringResource(R.string.removal_keeps_originals),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (storageReady && sources.any { !it.available }) {
                item {
                    TextButton(onClick = onRetry, enabled = !busy) { Text(stringResource(R.string.retry)) }
                }
            }
            item {
                Button(onClick = onClose, enabled = canClose, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.done_selection))
                }
            }
        }
    }
}
