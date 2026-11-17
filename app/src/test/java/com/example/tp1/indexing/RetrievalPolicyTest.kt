package com.example.tp1.indexing

import org.junit.Assert.*
import org.junit.Test

class RetrievalPolicyTest {
    private fun matches(vararg scores: Double) = scores.mapIndexed { index, score ->
        RetrievalMatch("text", score, index.toLong(), "g", 0, "file", "source", "document")
    }

    @Test fun floorIsAbsoluteAndIncludesEquality() {
        assertTrue(selectRagMatches(matches(0.54, 0.2, -0.1)).isEmpty())
        assertEquals(listOf(RAG_MIN_SCORE), selectRagMatches(matches(RAG_MIN_SCORE, RAG_MIN_SCORE - 0.001)).map { it.score })
        assertEquals(0.45, SEARCH_MIN_SCORE, 0.0)
        assertTrue(SEARCH_MIN_SCORE < RAG_MIN_SCORE)
    }

    @Test fun clearDropStopsAtFiveButFlatScoresKeepTen() {
        val ranked = matches(0.95, 0.94, 0.93, 0.92, 0.91, 0.65, 0.64, 0.63, 0.62, 0.61)
        assertEquals(ranked.take(5), selectRagMatches(ranked.reversed()))
        assertEquals(10, selectRagMatches(matches(*DoubleArray(15) { 0.8 })).size)
    }

    @Test fun calibratedMultiDocumentExampleDoesNotLoseSecondSourceToSmallFirstGap() {
        val ranked = matches(0.6804, 0.6076, 0.5876, 0.5771, 0.5658)
        assertEquals(ranked, selectRagMatches(ranked))
    }

    @Test fun emptySingleTwoAndSmoothDeclineAreDefined() {
        assertTrue(selectRagMatches(emptyList()).isEmpty())
        assertEquals(1, selectRagMatches(matches(0.6)).size)
        assertEquals(1, selectRagMatches(matches(0.9, 0.6)).size)
        assertEquals(10, selectRagMatches(matches(*DoubleArray(10) { 0.95 - it * 0.04 })).size)
        assertThrows(IllegalArgumentException::class.java) { selectRagMatches(matches(Double.NaN)) }
    }

    @Test fun fileRankingHasNoGlobalChunkCapOrDocumentLengthBonus() {
        fun vector(score: Float) = FloatArray(512).apply {
            this[0] = score; this[1] = kotlin.math.sqrt(1f - score * score)
        }
        val ranking = FileRanking(vector(1f))
        repeat(100) { ranking.offer(1, "g", it, vector(0.6f)) }
        for (id in 2L..80L) ranking.offer(id, "g", 0, vector(0.8f))
        ranking.offer(81, "g", 0, vector(0.9f))
        assertEquals(81, ranking.ranked().size)
        assertEquals(81L, ranking.ranked().first().documentId)
        assertEquals(1L, ranking.ranked().last().documentId)
        assertEquals(0, ranking.ranked().last().ordinal)
    }
}
