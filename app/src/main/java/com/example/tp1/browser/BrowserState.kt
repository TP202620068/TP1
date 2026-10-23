package com.example.tp1.browser

import com.example.tp1.sources.SourceMetadata

internal data class BrowserEntry(
    val sourceUri: String,
    val uri: String,
    val metadata: SourceMetadata,
    val directory: Boolean
)

internal data class BrowserFolder(val uri: String, val name: String)

internal enum class BrowserProblem { Missing, Access, Provider, NoViewer }

internal data class BrowserState(
    val sourceUri: String? = null,
    val path: List<BrowserFolder> = emptyList(),
    val entries: List<BrowserEntry> = emptyList(),
    val loading: Boolean = false,
    val pending: Boolean = false,
    val problem: BrowserProblem? = null,
    val message: BrowserProblem? = null,
    val opening: Boolean = false,
    val request: Long = 0
) {
    val atRoots: Boolean get() = sourceUri == null

    fun enter(entry: BrowserEntry): BrowserState {
        if (!entry.directory || entry !in entries || (!atRoots && entry.sourceUri != sourceUri)) return this
        return copy(
            sourceUri = entry.sourceUri,
            path = path + BrowserFolder(entry.uri, entry.metadata.name),
            entries = emptyList(), problem = null, message = null, pending = false,
            request = request + 1
        )
    }

    fun ancestor(index: Int): BrowserState {
        if (index !in -1 until path.lastIndex) return this
        return copy(
            sourceUri = sourceUri.takeIf { index >= 0 }, path = path.take(index + 1),
            entries = emptyList(), loading = false, pending = false, problem = null, message = null,
            request = request + 1
        )
    }

    fun reconcile(availableTrees: Set<String>): BrowserState =
        if (sourceUri != null && sourceUri !in availableTrees) {
            copy(sourceUri = null, path = emptyList(), entries = emptyList(), loading = false,
                pending = false, problem = null, message = BrowserProblem.Access, request = request + 1)
        } else this

    fun beginLoad(): BrowserState = copy(
        loading = true, pending = false, problem = null, message = null,
        request = request + 1
    )

    fun complete(
        token: Long, entries: List<BrowserEntry>, problem: BrowserProblem? = null, pending: Boolean = false
    ): BrowserState = if (token != request || atRoots) this else copy(
        entries = entries, loading = false, pending = pending, problem = problem
    )
}
