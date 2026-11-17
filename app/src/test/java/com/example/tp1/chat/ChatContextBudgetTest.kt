package com.example.tp1.chat

import com.example.tp1.indexing.RetrievalMatch
import com.example.tp1.inference.ChatMessage
import com.example.tp1.inference.ChatRole
import com.example.tp1.inference.budgetChatMessages
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChatContextBudgetTest {
    private fun match(id: Long, text: String) = RetrievalMatch(text, 1.0, id, "generation", 0, "file", "source", "document")

    @Test fun excludesLowestRankedWholeChunksAndReportsOnlyInjectedReferences() = runBlocking<Unit> {
        val high = match(1, "HIGH")
        val low = match(2, "LOW")
        val result = prepareChatRequest("question", emptyList(), listOf(high, low), { 100 }) { text, history ->
            budgetChatMessages("system", history, text) { if (it.last().text.contains("LOW")) 3585 else 3584 }
        }
        assertEquals(listOf(high), result.included)
        assertTrue(result.messages.single().text.contains("HIGH"))
        assertFalse(result.messages.single().text.contains("LOW"))
        assertTrue(result.messages.single().text.endsWith("question"))
    }

    @Test fun evictsWholeHistoryPairsBeforeDroppingEvidenceAndCountsDelimiters() = runBlocking<Unit> {
        val history = listOf(ChatMessage(ChatRole.User, "old"), ChatMessage(ChatRole.Assistant, "answer"))
        val result = prepareChatRequest("question", history, listOf(match(1, "evidence")), { 100 }) { text, previous ->
            budgetChatMessages("system", previous, text) {
                if (text.contains("evidence")) {
                    assertTrue(text.contains("</document>"))
                    if (it.size > 1) 3585 else 3584
                } else 100
            }
        }
        assertEquals(1, result.messages.size)
        assertEquals(1, result.included.size)
    }

    @Test fun rejectsOversizedQuestionWithoutSilentlyDroppingIt() = runBlocking<Unit> {
        try {
            prepareChatRequest("question", emptyList(), listOf(match(1, "evidence")), { 100 }) { text, history ->
                budgetChatMessages("system", history, text) { 3585 }
            }
            fail("Oversized question accepted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun byteLimitDropsWholeChunkRatherThanTruncatingQuestionOrChunk() = runBlocking<Unit> {
        val result = prepareChatRequest("original  question", emptyList(), listOf(match(1, "x".repeat(65_530))), { 100 }) { text, history ->
            budgetChatMessages("system", history, text) { 100 }
        }
        assertTrue(result.included.isEmpty())
        assertTrue(result.messages.isEmpty())
    }

    @Test fun emptyEvidenceNeverPreparesQuestionOnlyGeneration() = runBlocking<Unit> {
        val result = prepareChatRequest("question", emptyList(), emptyList(), { error("No counting") }) { _, _ ->
            error("No generation preparation")
        }
        assertTrue(result.messages.isEmpty())
        assertTrue(result.included.isEmpty())
    }
}
