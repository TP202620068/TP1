package com.example.tp1.chat

import com.example.tp1.indexing.RetrievalMatch
import com.example.tp1.inference.chatBytes
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class ContextPassage(val text: String, val chunks: List<RetrievalMatch>)

/** Source bytes, not repeated words, determine overlap. Originals remain whole when a merge cannot fit. */
internal suspend fun contextPassages(
    matches: List<RetrievalMatch>, countTokens: suspend (String) -> Int
): List<ContextPassage> {
    val passages = mutableListOf<ContextPassage>()
    for (group in matches.groupBy { it.documentId to it.generation }.values) {
        var current: ContextPassage? = null
        for (chunk in group.sortedBy { it.ordinal }) {
            currentCoroutineContext().ensureActive()
            val bytes = chatBytes(chunk.text)
            if (chunk.startByte < 0 || chunk.endByte.toLong() - chunk.startByte != bytes.size.toLong()) {
                throw IOException("Invalid context source range")
            }
            val previous = current
            if (previous != null && previous.chunks.last().ordinal + 1 == chunk.ordinal) {
                val start = previous.chunks.minOf { it.startByte }
                val end = previous.chunks.maxOf { it.endByte }
                if (chunk.startByte <= end && chunk.endByte >= start) {
                    val existing = chatBytes(previous.text)
                    val overlapStart = maxOf(start, chunk.startByte)
                    val overlapEnd = minOf(end, chunk.endByte)
                    for (offset in overlapStart until overlapEnd) {
                        if (existing[offset - start] != bytes[offset - chunk.startByte]) {
                            throw IOException("Inconsistent context overlap")
                        }
                    }
                    val unionStart = minOf(start, chunk.startByte)
                    val unionEnd = maxOf(end, chunk.endByte)
                    val union = ByteArray(unionEnd - unionStart)
                    existing.copyInto(union, start - unionStart)
                    bytes.copyInto(union, chunk.startByte - unionStart)
                    if (union.size + "<document>\n\n</document>\n".length <= 64 * 1024) {
                        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(union)).toString()
                        if (countTokens(text) <= 1024) {
                            current = ContextPassage(text, previous.chunks + chunk)
                            continue
                        }
                    }
                }
            }
            current?.let(passages::add)
            current = ContextPassage(chunk.text, listOf(chunk))
        }
        current?.let(passages::add)
    }
    val ranks = matches.withIndex().associate { (index, chunk) -> Triple(chunk.documentId, chunk.generation, chunk.ordinal) to index }
    return passages.sortedBy { passage -> passage.chunks.minOf { ranks.getValue(Triple(it.documentId, it.generation, it.ordinal)) } }
}
