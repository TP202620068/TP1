package com.example.tp1.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.tp1.R
import com.example.tp1.indexing.IndexingFailure
import com.example.tp1.indexing.IndexingProblem
import com.example.tp1.indexing.IndexingState
import com.example.tp1.indexing.SourceIndexingState
import com.example.tp1.ui.theme.TP1Theme

@Composable
internal fun IndexingStatus(
    state: IndexingState,
    onRetry: (String) -> Unit,
    modifier: Modifier = Modifier,
    sourceUri: String? = null
) {
    val summary = state.statusSummary(sourceUri) ?: return
    val stoppedWithError = summary.hasError && !summary.active
    val title = when {
        summary.blocked != null -> stringResource(R.string.indexing_blocked)
        summary.active && summary.total == 0 -> stringResource(R.string.indexing_discovering)
        summary.active -> stringResource(R.string.indexing_running, summary.completed, summary.total)
        summary.hasError -> stringResource(R.string.indexing_incomplete_title)
        else -> stringResource(R.string.indexing_complete, summary.completed)
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = if (stoppedWithError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (stoppedWithError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (summary.omitted > 0) Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    Text(stringResource(R.string.indexing_omitted, summary.omitted),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall)
                }
            }

            if (summary.waitingForChat) {
                Text(stringResource(R.string.indexing_waiting), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (summary.active) {
                val progressModifier = Modifier.fillMaxWidth().height(4.dp).semantics { contentDescription = title }
                if (summary.discovering || summary.total == 0) LinearProgressIndicator(modifier = progressModifier)
                else LinearProgressIndicator(progress = { summary.completed.toFloat() / summary.total }, modifier = progressModifier)
            }

            val detail = when {
                summary.blocked == IndexingProblem.Model -> stringResource(R.string.indexing_model_blocked)
                summary.blocked != null -> stringResource(R.string.indexing_storage_blocked)
                summary.failure != null -> {
                    val reason = stringResource(when (summary.failure.problem) {
                        IndexingProblem.Discovery -> R.string.indexing_incomplete
                        IndexingProblem.Access -> R.string.browser_access
                        else -> R.string.indexing_failed
                    })
                    if (summary.failure.name.isBlank()) reason
                    else stringResource(R.string.indexing_file_failure, summary.failure.name, reason)
                }
                summary.incomplete -> stringResource(R.string.indexing_incomplete)
                else -> null
            }
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall,
                color = if (stoppedWithError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.error)

            if (summary.retrySources.isNotEmpty()) {
                val retryDescription = stringResource(R.string.indexing_retry)
                TextButton(
                    onClick = { summary.retrySources.forEach(onRetry) },
                    modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)
                        .semantics { contentDescription = retryDescription }
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
}

@Preview(name = "Phone", device = Devices.PIXEL_7, showBackground = true)
@Preview(name = "Narrow, large text", widthDp = 320, fontScale = 2f, showBackground = true)
@Preview(name = "Tablet", device = Devices.TABLET, showBackground = true)
@Composable
private fun IndexingStatusPreview() {
    val source = SourceIndexingState(discovering = false, pending = 6, running = true,
        failures = listOf(IndexingFailure("image.png", IndexingProblem.Unsupported)))
    TP1Theme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            IndexingStatus(IndexingState(mapOf("source" to source)), {})
            IndexingStatus(IndexingState(mapOf("source" to source.copy(pending = 0, running = false, completed = 6))), {})
            IndexingStatus(IndexingState(mapOf("source" to source), IndexingProblem.Model), {})
        }
    }
}
