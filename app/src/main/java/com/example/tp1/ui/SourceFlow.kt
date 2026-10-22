package com.example.tp1.ui

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.tp1.R
import com.example.tp1.sources.SelectedSource
import com.example.tp1.sources.SourceKind
import com.example.tp1.sources.SourcesViewModel
import com.example.tp1.ui.components.FileKind
import com.example.tp1.ui.components.FileListItem
import com.example.tp1.ui.screens.SourceSelectionScreen
import java.util.Date

@Composable
internal fun SourceFlow(viewModel: SourcesViewModel, onPick: (SourceKind) -> Unit) {
    val state by viewModel.state.collectAsState()
    val shellState = rememberSaveableStateHolder()
    Surface(color = MaterialTheme.colorScheme.background) {
        when {
            state.restoring -> Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.selecting -> {
                BackHandler(onBack = viewModel::closeSelection)
                SourceSelectionScreen(
                    sources = state.sources,
                    busy = state.busy,
                    storageReady = state.storageReady,
                    canClose = state.canCloseSelection,
                    message = state.message?.let { stringResource(it) },
                    onPick = onPick,
                    onRemove = viewModel::remove,
                    onClose = viewModel::closeSelection,
                    onRetry = { viewModel.refresh() }
                )
            }
            else -> shellState.SaveableStateProvider("shell") {
                val items = state.sources.filter { it.available }.map { it.toFileListItem() }
                TP1App(
                    files = items,
                    onAddSource = viewModel::selectSources,
                    sourceMessage = state.message?.let { stringResource(it) }
                )
            }
        }
    }
}

@Composable
internal fun SelectedSource.toFileListItem(): FileListItem {
    val context = LocalContext.current
    val details = metadata
    val kind = when {
        record.kind == SourceKind.Tree -> FileKind.Folder
        details?.mimeType == "application/pdf" -> FileKind.Pdf
        details?.mimeType?.startsWith("image/") == true -> FileKind.Image
        else -> FileKind.Document
    }
    val information = if (!available) stringResource(R.string.source_unavailable) else listOfNotNull(
        details?.size?.takeIf { kind != FileKind.Folder }?.let { Formatter.formatShortFileSize(context, it) },
        details?.modified?.let { DateFormat.getMediumDateFormat(context).format(Date(it)) }
    ).joinToString(" · ").takeIf { it.isNotEmpty() }
    return FileListItem(
        name = details?.name ?: record.name ?: stringResource(R.string.unnamed_source),
        kind = kind,
        metadata = information
    )
}
