package com.example.tp1.indexing

internal const val RAG_CHUNKS = 10
// shortcut: floors are calibrated on six PDFs only; recalibrate on a broader corpus or model change, see RAG_SELECTION.md.
internal const val RAG_MIN_SCORE = 0.55
internal const val SEARCH_MIN_SCORE = 0.45
private const val ELBOW_GAP = 0.10

/** A floor rejects weak matches; an elbow only removes a distinctly weaker tail. Neither proves answerability. */
internal fun selectRagMatches(matches: List<RetrievalMatch>): List<RetrievalMatch> {
    require(matches.all { it.score.isFinite() })
    val candidates = matches.sortedWith(compareByDescending<RetrievalMatch> { it.score }
        .thenBy { it.documentId }.thenBy { it.ordinal }).take(RAG_CHUNKS).filter { it.score >= RAG_MIN_SCORE }
    val gaps = candidates.zipWithNext { a, b -> a.score - b.score }
    for (index in gaps.indices) {
        val others = gaps.filterIndexed { other, _ -> other != index }.sorted()
        val median = when {
            others.isEmpty() -> 0.0
            others.size % 2 == 1 -> others[others.size / 2]
            else -> (others[others.size / 2 - 1] + others[others.size / 2]) / 2
        }
        if (gaps[index] >= ELBOW_GAP && gaps[index] >= 3 * median) return candidates.take(index + 1)
    }
    return candidates
}
