package com.example.tp1.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.tp1.R
import com.example.tp1.indexing.IndexingProblem
import com.example.tp1.indexing.IndexingState

@Composable
internal fun IndexingStatus(
    state: IndexingState,
    onRetry: (String) -> Unit,
    modifier: Modifier = Modifier,
    sourceUri: String? = null
) {
    val sources = state.sources.filterKeys { sourceUri == null || sourceUri == it }
    if (sources.isEmpty()) return
    val values = sources.values
    val failures = values.flatMap { it.failures }
    val phase = when {
        state.blocked == IndexingProblem.Model -> R.string.indexing_model_blocked
        state.blocked != null -> R.string.indexing_storage_blocked
        values.any { it.running } -> R.string.indexing_running
        values.any { it.waitingForChat } -> R.string.indexing_waiting
        values.any { it.discovering } -> R.string.indexing_discovering
        values.any { it.pending > 0 } -> R.string.indexing_pending
        values.any { it.incomplete } -> R.string.indexing_incomplete
        failures.isNotEmpty() -> R.string.indexing_failed
        else -> R.string.indexing_complete
    }
    Column(modifier) {
        Text(stringResource(phase), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.indexing_counts, values.sumOf { it.completed }, values.sumOf { it.pending }, failures.size),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        failures.firstOrNull()?.let { failure ->
            val reason = when (failure.problem) {
                IndexingProblem.NeedsOcr -> R.string.indexing_needs_ocr
                IndexingProblem.Unsupported -> R.string.indexing_unsupported
                IndexingProblem.Discovery -> R.string.indexing_incomplete
                IndexingProblem.Access -> R.string.source_unavailable
                else -> R.string.indexing_failed
            }
            Text(stringResource(R.string.indexing_file_failure, failure.name, stringResource(reason)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (state.blocked != null || values.any { it.incomplete || it.failures.isNotEmpty() }) {
            TextButton(onClick = {
                sources.filterValues { state.blocked != null || it.incomplete || it.failures.isNotEmpty() }
                    .keys.forEach(onRetry)
            }) { Text(stringResource(R.string.indexing_retry)) }
        }
    }
}
