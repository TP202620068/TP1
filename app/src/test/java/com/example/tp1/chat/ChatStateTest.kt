package com.example.tp1.chat

import com.example.tp1.inference.ChatMessage
import com.example.tp1.inference.ChatRole
import com.example.tp1.inference.FinishReason
import com.example.tp1.inference.budgetChatMessages
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChatStateTest {
    private fun pair(text: String) = listOf(ChatMessage(ChatRole.User, text), ChatMessage(ChatRole.Assistant, "answer"))

    @Test fun sendRequiresReadyIdleAndValidUtf8WithinSystemByteBudget() {
        val ready = ChatState(draft = "Hola 😀", preparation = ChatPreparation.Ready)
        assertTrue(ready.canSend)
        assertFalse(ready.copy(problem = ChatProblem.InvalidInput).canSend)
        for (preparation in ChatPreparation.entries.filter { it != ChatPreparation.Ready }) {
            assertFalse(ready.copy(preparation = preparation).canSend)
        }
        for (phase in ChatPhase.entries.filter { it != ChatPhase.Idle }) assertFalse(ready.copy(phase = phase).canSend)
        for (draft in listOf("", " \n", "bad\u0000input", "\uD800", "é".repeat(32768))) {
            assertFalse(ready.copy(draft = draft).canSend)
        }
    }

    @Test fun onlyUsableCompletedTurnsBecomeHistory() {
        val turns = FinishReason.entries.mapIndexed { index, finish -> ChatTurn(index.toLong(), "user", "answer", finish = finish) }
        val extra = listOf(ChatTurn(8, "user", "partial", running = true),
            ChatTurn(9, "user", "bad\u0000answer", finish = FinishReason.EOG),
            ChatTurn(10, "user", "", finish = FinishReason.EOG),
            ChatTurn(11, "user", "answer", finish = FinishReason.EOG, failed = true))
        assertEquals(pair("user") + pair("user"), (turns + extra).replayMessages())
        assertTrue(turns.first { it.finish == FinishReason.CANCELLED }.canRetry)
        assertFalse(turns.first { it.finish == FinishReason.LENGTH }.canRetry)
    }

    @Test fun exactFormattedCountsEvictOldestWholePairsAndKeepLatestInput() = runBlocking<Unit> {
        val history = pair("oldest") + pair("recent")
        val seen = mutableListOf<List<ChatMessage>>()
        val result = budgetChatMessages("system", history, "latest 😀") { messages ->
            seen += messages
            if (messages.size > 3) 3585 else 3584
        }
        assertEquals(pair("recent") + ChatMessage(ChatRole.User, "latest 😀"), result)
        assertEquals(listOf(5, 3), seen.map { it.size })
    }

    @Test fun oversizedLatestInputIsRejectedRatherThanTruncated() = runBlocking<Unit> {
        var calls = 0
        try {
            budgetChatMessages("system", pair("old"), "latest") { calls++; 3585 }
            fail("Oversized latest input accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals(2, calls)
    }

    @Test fun byteBudgetEvictsBeforeCallingTokenizerAndIncludesSystem() = runBlocking<Unit> {
        val history = pair("a".repeat(65_500)) + pair("recent")
        var calls = 0
        val result = budgetChatMessages("system".repeat(20), history, "latest") {
            calls++
            assertEquals(3, it.size)
            100
        }
        assertEquals(1, calls)
        assertEquals("recent", result.first().text)
        try {
            budgetChatMessages("system", emptyList(), "é".repeat(32768)) { error("Must reject before JNI") }
            fail("Combined byte bound ignored")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun invalidInputsAndBrokenRoleOrderAreRejected() = runBlocking<Unit> {
        for (input in listOf(" ", "bad\u0000input", "\uD800")) {
            try {
                budgetChatMessages("system", emptyList(), input) { error("Invalid input reached tokenizer") }
                fail("Invalid input accepted")
            } catch (_: IllegalArgumentException) { }
            catch (_: java.nio.charset.CharacterCodingException) { }
        }
        try {
            budgetChatMessages("system", pair("old").reversed(), "latest") { 1 }
            fail("Invalid role order accepted")
        } catch (_: IllegalArgumentException) { }
    }
}
