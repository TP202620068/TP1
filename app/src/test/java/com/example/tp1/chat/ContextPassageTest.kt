package com.example.tp1.chat

import com.example.tp1.indexing.RetrievalMatch
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ContextPassageTest {
    private fun chunk(ordinal: Int, text: String, start: Int, document: Long = 1, generation: String = "g") =
        RetrievalMatch(text, 1.0, document, generation, ordinal, "file", "source", "document",
            startByte = start, endByte = start + text.toByteArray().size)

    @Test fun unicodeThreeWayOverlapIsRemovedInSourceOrderWithAllReferences() = runBlocking<Unit> {
        val a = chunk(0, "á😀 uno", 0)
        val b = chunk(1, "uno dos", "á😀 ".toByteArray().size)
        val c = chunk(2, "dos fin", "á😀 uno ".toByteArray().size)
        val result = contextPassages(listOf(b, a, c)) { 100 }
        assertEquals("á😀 uno dos fin", result.single().text)
        assertEquals(listOf(a, b, c), result.single().chunks)
    }

    @Test fun containmentAndBackwardGrowingContextDoNotAssumeMonotonicRanges() = runBlocking<Unit> {
        val result = contextPassages(listOf(chunk(0, "abcd", 2), chunk(1, "xxabcdyy", 0), chunk(2, "bc", 3))) { 100 }
        assertEquals("xxabcdyy", result.single().text)
        assertEquals(3, result.single().chunks.size)
    }

    @Test fun touchingRangesMergeButGapsUnselectedOrdinalsAndDifferentSnapshotsDoNot() = runBlocking<Unit> {
        assertEquals("ab", contextPassages(listOf(chunk(0, "a", 0), chunk(1, "b", 1))) { 100 }.single().text)
        for (other in listOf(chunk(1, "b", 2), chunk(2, "b", 1), chunk(1, "b", 1, document = 2),
            chunk(1, "b", 1, generation = "new"))) {
            assertEquals(2, contextPassages(listOf(chunk(0, "a", 0), other)) { 100 }.size)
        }
    }

    @Test fun mergeBudgetIsExactAndOversizedRunsStayWhole() = runBlocking<Unit> {
        val chunks = listOf(chunk(0, "ab", 0), chunk(1, "bc", 1))
        assertEquals("abc", contextPassages(chunks) { 1024 }.single().text)
        assertEquals(listOf("ab", "bc"), contextPassages(chunks) { 1025 }.map { it.text })
    }

    @Test fun passagesKeepHighestConstituentPriorityRatherThanDocumentOrSourceOrder() = runBlocking<Unit> {
        val high = chunk(1, "bc", 1)
        val other = chunk(0, "other", 0, document = 2)
        val low = chunk(0, "ab", 0)
        val result = contextPassages(listOf(high, other, low)) { 100 }
        assertEquals(listOf("abc", "other"), result.map { it.text })
    }

    @Test fun inconsistentOverlapsAndInvalidRangesFailRatherThanLosingText() {
        assertThrows(IOException::class.java) { runBlocking {
            contextPassages(listOf(chunk(0, "ab", 0), chunk(1, "ZZ", 1))) { 100 }
        } }
        assertThrows(IOException::class.java) { runBlocking {
            contextPassages(listOf(chunk(0, "ab", 0).copy(endByte = 3))) { 100 }
        } }
    }
}
