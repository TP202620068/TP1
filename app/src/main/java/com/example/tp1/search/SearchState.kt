package com.example.tp1.search

import com.example.tp1.browser.BrowserProblem
import com.example.tp1.browser.DocumentReference
import com.example.tp1.browser.documentReferences
import com.example.tp1.indexing.RetrievalMatch

internal enum class SearchPhase { Idle, Searching, Checking }
internal enum class SearchProblem { Busy, QueryLimit, Retrieval, Changed, Interrupted }
internal data class SearchRow(val document: DocumentReference, val excerpt: String)

internal data class SearchState(
    val draft: String = "",
    val submitted: String? = null,
    val request: Long = 0,
    val phase: SearchPhase = SearchPhase.Idle,
    val rows: List<SearchRow> = emptyList(),
    val inputInvalid: Boolean = false,
    val problem: SearchProblem? = null,
    val opening: Boolean = false,
    val openProblem: BrowserProblem? = null
) {
    val showingResults: Boolean get() = submitted != null
    val canSubmit: Boolean get() = draft.isNotBlank() && !inputInvalid
}

internal fun searchRows(matches: List<RetrievalMatch>): List<SearchRow> = documentReferences(matches).map { document ->
    val best = matches.first { match -> document.alternatives.any {
        it.sourceUri == match.sourceUri && it.documentUri == match.documentUri
    } }
    SearchRow(document, searchExcerpt(best.text))
}

internal fun searchExcerpt(text: String): String {
    val plain = text.replace(Regex("\\s+"), " ").trim()
    val count = plain.codePointCount(0, plain.length)
    return if (count <= 280) plain else plain.substring(0, plain.offsetByCodePoints(0, 280)) + "…"
}
