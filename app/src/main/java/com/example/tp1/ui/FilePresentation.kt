package com.example.tp1.ui

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.tp1.R
import com.example.tp1.browser.BrowserEntry
import com.example.tp1.sources.SelectedSource
import com.example.tp1.sources.SourceKind
import com.example.tp1.sources.SourceMetadata
import com.example.tp1.ui.components.FileKind
import com.example.tp1.ui.components.FileListItem
import java.util.Date

@Composable
internal fun SelectedSource.toFileListItem(): FileListItem = fileListItem(
    metadata, record.name, record.kind == SourceKind.Tree, available
)

@Composable
internal fun BrowserEntry.toFileListItem(): FileListItem =
    fileListItem(metadata, null, directory, true).copy(id = uri)

@Composable
private fun fileListItem(details: SourceMetadata?, fallback: String?, folder: Boolean, available: Boolean): FileListItem {
    val context = LocalContext.current
    val kind = when {
        folder -> FileKind.Folder
        details?.mimeType == "application/pdf" -> FileKind.Pdf
        details?.mimeType?.startsWith("image/") == true -> FileKind.Image
        else -> FileKind.Document
    }
    val information = if (!available) stringResource(R.string.source_unavailable) else listOfNotNull(
        details?.size?.takeIf { !folder }?.let { Formatter.formatShortFileSize(context, it) },
        details?.modified?.let { DateFormat.getMediumDateFormat(context).format(Date(it)) }
    ).joinToString(" · ").takeIf { it.isNotEmpty() }
    return FileListItem(details?.name ?: fallback ?: stringResource(R.string.unnamed_source), kind, information)
}
