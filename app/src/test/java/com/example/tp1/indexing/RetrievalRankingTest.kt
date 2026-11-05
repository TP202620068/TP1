package com.example.tp1.indexing

import java.io.IOException
import java.util.Random
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class RetrievalRankingTest {
    private fun vector(first: Float = 1f, second: Float = 0f) = FloatArray(512).apply { this[0] = first; this[1] = second }

    @Test fun cosineMeaningOrderingAndNegativeScores() {
        val ranking = RetrievalRanking(vector(), 5)
        ranking.offer(3, "g", 0, vector(-1f))
        ranking.offer(2, "g", 0, vector(0f, 1f))
        ranking.offer(1, "g", 0, vector())
        assertEquals(listOf(1.0, 0.0, -1.0), ranking.ranked().map { it.score })
        assertEquals(listOf(1L, 2L, 3L), ranking.ranked().map { it.documentId })
    }

    @Test fun tiesAreStableAcrossInputOrderAndHeapNeverExceedsK() {
        val rows = listOf(3L to 0, 1L to 2, 1L to 0, 2L to 0, 1L to 1)
        for (input in listOf(rows, rows.reversed(), rows.shuffled(kotlin.random.Random(42)))) {
            val ranking = RetrievalRanking(vector(), 3)
            for ((id, ordinal) in input) {
                ranking.offer(id, "generation", ordinal, vector())
                assertTrue(ranking.size <= 3)
            }
            assertEquals(listOf(1L to 0, 1L to 1, 1L to 2), ranking.ranked().map { it.documentId to it.ordinal })
        }
    }

    @Test fun exactStreamingTopKMatchesIndependentFullSort() {
        val random = Random(29)
        val rows = (1L..1000L).map { id ->
            val raw = FloatArray(512) { random.nextFloat() - 0.5f }
            val norm = sqrt(raw.sumOf { it.toDouble() * it })
            id to FloatArray(512) { (raw[it] / norm).toFloat() }
        }
        val query = rows.first().second
        val ranking = RetrievalRanking(query, 17)
        for ((id, value) in rows.reversed()) {
            ranking.offer(id, "g", 0, value)
            assertTrue(ranking.size <= 17)
        }
        val expected = rows.map { (id, value) -> id to query.indices.sumOf { query[it].toDouble() * value[it] } }
            .sortedWith(compareByDescending<Pair<Long, Double>> { it.second }.thenBy { it.first }).take(17)
        assertEquals(expected, ranking.ranked().map { it.documentId to it.score })
    }

    @Test fun emptyFewerAndKBounds() {
        assertTrue(RetrievalRanking(vector(), 1).ranked().isEmpty())
        val ranking = RetrievalRanking(vector(), MAX_RETRIEVAL_K)
        ranking.offer(1, "g", 0, vector())
        assertEquals(1, ranking.ranked().size)
        for (k in listOf(Int.MIN_VALUE, -1, 0, MAX_RETRIEVAL_K + 1, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { RetrievalRanking(vector(), k) }
        }
    }

    @Test fun invalidQueryAndCandidateVectorsFail() {
        val invalid = listOf(FloatArray(511), FloatArray(513), vector(0f), vector(2f), vector(Float.NaN), vector(Float.POSITIVE_INFINITY))
        for (value in invalid) {
            assertThrows(IOException::class.java) { RetrievalRanking(value, 1) }
            assertThrows(IOException::class.java) { RetrievalRanking(vector(), 1).offer(1, "g", 0, value) }
        }
    }
}
