package com.example.tp1.indexing

import java.util.PriorityQueue

internal const val MAX_RETRIEVAL_K = 50

internal data class ScoredChunk(val documentId: Long, val generation: String, val ordinal: Int, val score: Double)

/** Exact cosine ranking of unit vectors; only row identities and scores survive each offer. */
internal class RetrievalRanking(private val query: FloatArray, private val k: Int) {
    private val order = compareByDescending<ScoredChunk> { it.score }.thenBy { it.documentId }.thenBy { it.ordinal }
    private val heap: PriorityQueue<ScoredChunk>
    val size: Int get() = heap.size

    init {
        require(k in 1..MAX_RETRIEVAL_K) { "k must be between 1 and $MAX_RETRIEVAL_K" }
        validateVector(query)
        heap = PriorityQueue(k, order.reversed())
    }

    fun offer(documentId: Long, generation: String, ordinal: Int, vector: FloatArray) {
        validateVector(vector)
        val score = query.indices.sumOf { query[it].toDouble() * vector[it] }
        val candidate = ScoredChunk(documentId, generation, ordinal, score)
        if (heap.size < k) heap.add(candidate)
        else if (order.compare(candidate, heap.peek()) < 0) {
            heap.remove()
            heap.add(candidate)
        }
    }

    fun ranked(): List<ScoredChunk> = heap.sortedWith(order)
}
