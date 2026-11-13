package com.example.tp1.ui.components

import com.example.tp1.indexing.IndexingFailure
import com.example.tp1.indexing.IndexingProblem
import com.example.tp1.indexing.IndexingState

internal data class IndexingStatusSummary(
    val completed: Int,
    val total: Int,
    val omitted: Int,
    val active: Boolean,
    val discovering: Boolean,
    val waitingForChat: Boolean,
    val blocked: IndexingProblem?,
    val incomplete: Boolean,
    val failure: IndexingFailure?,
    val retrySources: List<String>
) {
    val hasError: Boolean get() = blocked != null || incomplete || failure != null
}

internal fun IndexingState.statusSummary(sourceUri: String? = null): IndexingStatusSummary? {
    val sources = sources.filterKeys { sourceUri == null || sourceUri == it }
    if (sources.isEmpty()) return null
    val values = sources.values
    val failures = values.flatMap { it.failures }
    val errors = failures.filterNot { it.isOmission() }
    val completed = values.sumOf { it.completed }
    val active = blocked == null && values.any { it.running || it.discovering || it.waitingForChat || it.pending > 0 }
    return IndexingStatusSummary(
        completed = completed,
        total = completed + values.sumOf { it.pending } + errors.count { it.problem == IndexingProblem.Document },
        omitted = failures.size - errors.size,
        active = active,
        discovering = values.any { it.discovering },
        waitingForChat = active && values.none { it.running } && values.any { it.waitingForChat },
        blocked = blocked,
        incomplete = values.any { it.incomplete },
        failure = errors.firstOrNull { it.problem != IndexingProblem.Document } ?: errors.firstOrNull(),
        retrySources = if (active) emptyList() else sources.filterValues { source ->
            blocked != null || source.incomplete || source.failures.any { !it.isOmission() }
        }.keys.toList()
    )
}

private fun IndexingFailure.isOmission() = problem == IndexingProblem.Unsupported || problem == IndexingProblem.NeedsOcr
