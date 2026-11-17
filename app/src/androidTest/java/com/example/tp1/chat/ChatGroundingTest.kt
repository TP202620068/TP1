package com.example.tp1.chat

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tp1.indexing.RetrievalMatch
import com.example.tp1.indexing.RetrievalResult
import com.example.tp1.inference.ChatMessage
import com.example.tp1.inference.FinishReason
import com.example.tp1.inference.GenerationEvent
import com.example.tp1.inference.NativeWorkHandoff
import com.example.tp1.inference.budgetChatMessages
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatGroundingTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val store = ViewModelStore()
    private lateinit var chat: ChatViewModel
    private val requests = mutableListOf<List<ChatMessage>>()
    private val events = mutableListOf<String>()
    private var opened = 0
    private var closed = 0
    private var queries = 0
    private var matches = listOf(match())
    private var retrieve: suspend (String) -> RetrievalResult = {
        queries++
        val ticket = Any()
        try {
            check(NativeWorkHandoff.acquire(ticket, query = true))
            events += "query"
            RetrievalResult.Complete(matches)
        } finally { NativeWorkHandoff.release(ticket) }
    }
    private var validate: suspend () -> Unit = {}
    private var answer: () -> Flow<GenerationEvent> = { flow {
        emit(GenerationEvent.Text("answer"))
        emit(GenerationEvent.Finished(FinishReason.EOG, 1))
    } }

    @Before fun main() { Dispatchers.setMain(dispatcher) }
    @After fun clear() {
        try { store.clear(); scheduler.runCurrent() }
        finally { Dispatchers.resetMain() }
    }

    private fun start(open: suspend () -> ChatConnection = { connection() }) {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        chat = ChatViewModel(application, SavedStateHandle(), {}, {
            opened++; events += "open"; open()
        }, retrieve = { retrieve(it) }, checkContext = { events += "authorize"; validate() })
        store.put("chat", chat)
        chat.setForeground(true)
        chat.setSelected(true)
        scheduler.runCurrent()
    }

    private fun connection(close: CompletableDeferred<Unit> = CompletableDeferred(Unit)) = ChatConnection(
        prepare = { text, history -> events += "budget"; budgetChatMessages(CHAT_SYSTEM_PROMPT, history, text) { 100 } },
        stream = { requests += it; events += "stream"; answer() }, cancel = {},
        closeAsync = { closed++; events += "close"; close }, countPassageTokens = { 100 })

    @Test fun submissionYieldsIdleChatWithoutSelfCancellationAndAwaitsQueryCloseBeforeReopening() = runTest(dispatcher) {
        val queryClose = CompletableDeferred<Unit>()
        var captured = ""
        retrieve = { query ->
            queries++; captured = query
            val ticket = Any()
            try {
                check(NativeWorkHandoff.acquire(ticket, query = true))
                events += "query"
                withContext(NonCancellable) { queryClose.await(); events += "query closed" }
                RetrievalResult.Complete(matches)
            } finally { NativeWorkHandoff.release(ticket) }
        }
        start()
        chat.editDraft("  question  ")
        assertEquals(0, queries)
        chat.send()
        runCurrent()
        assertEquals(ChatPhase.Retrieving, chat.state.value.phase)
        assertEquals(1, closed)
        chat.prepare()
        chat.setSelected(false)
        chat.setSelected(true)
        chat.editDraft("next draft")
        runCurrent()
        assertEquals(1, opened)
        assertTrue(requests.isEmpty())
        assertEquals("question", captured)
        queryClose.complete(Unit)
        runCurrent()
        assertEquals(1, queries)
        assertEquals(2, opened)
        assertEquals("next draft", chat.state.value.draft)
        assertEquals("question", chat.state.value.turns.single().input)
        assertTrue(requests.single().last().text.contains("controlled evidence"))
        assertTrue(events.indexOf("query closed") < events.lastIndexOf("open"))
        assertTrue(events.indexOf("authorize") < events.indexOf("stream"))
        assertEquals(1, chat.state.value.turns.single().documents.size)
    }

    @Test fun stopDuringRetrievalKeepsStoppingUntilCleanupAndDoesNotCreateTurn() = runTest(dispatcher) {
        val cleanup = CompletableDeferred<Unit>()
        retrieve = {
            val ticket = Any()
            try {
                check(NativeWorkHandoff.acquire(ticket, query = true))
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { cleanup.await(); NativeWorkHandoff.release(ticket) }
            }
        }
        start()
        chat.editDraft("keep")
        chat.send(); runCurrent()
        chat.stop(); runCurrent()
        assertEquals(ChatPhase.Stopping, chat.state.value.phase)
        assertTrue(NativeWorkHandoff.blocked)
        assertFalse(chat.state.value.canSend)
        cleanup.complete(Unit); runCurrent()
        assertEquals(ChatPhase.Idle, chat.state.value.phase)
        assertFalse(NativeWorkHandoff.blocked)
        assertEquals("keep", chat.state.value.draft)
        assertTrue(chat.state.value.turns.isEmpty())
    }

    @Test fun busyOversizedMissingModelAndStorageFailuresNeverFallBackOrClearDraft() = runTest(dispatcher) {
        start()
        for (expected in listOf(ChatProblem.RetrievalBusy, ChatProblem.QueryLimit, ChatProblem.Retrieval, ChatProblem.Retrieval)) {
            retrieve = {
                when (expected) {
                    ChatProblem.RetrievalBusy -> RetrievalResult.Busy
                    ChatProblem.QueryLimit -> throw IllegalArgumentException("oversized")
                    else -> throw IOException("model or storage unavailable")
                }
            }
            chat.editDraft("keep")
            chat.send(); runCurrent()
            assertEquals(expected, chat.state.value.problem)
            assertEquals("keep", chat.state.value.draft)
            assertTrue(chat.state.value.turns.isEmpty())
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun accessLossAfterBudgetingAbortsAndFailedRetryPreservesAnswerAndDocuments() = runTest(dispatcher) {
        answer = { flow {
            emit(GenerationEvent.Text("partial"))
            emit(GenerationEvent.Finished(FinishReason.DEADLINE, 1))
        } }
        start()
        chat.editDraft("question"); chat.send(); runCurrent()
        val original = chat.state.value.turns.single()
        chat.editDraft("newer")
        validate = { throw SecurityException("removed") }
        chat.retry(); runCurrent()
        assertEquals(2, queries)
        assertEquals(ChatProblem.Access, chat.state.value.problem)
        assertEquals(original, chat.state.value.turns.single())
        assertEquals("newer", chat.state.value.draft)
        assertEquals(1, requests.size)
        validate = {}
        matches = emptyList()
        answer = { flow {
            emit(GenerationEvent.Text("replacement")); emit(GenerationEvent.Finished(FinishReason.EOG, 1))
        } }
        chat.retry(); runCurrent()
        assertEquals(3, queries)
        assertEquals(original.id, chat.state.value.turns.single().id)
        assertEquals(InstrumentationRegistry.getInstrumentation().targetContext.getString(
            com.example.tp1.R.string.chat_insufficient_evidence), chat.state.value.turns.single().answer)
        assertTrue(chat.state.value.turns.single().documents.isEmpty())
        assertEquals(1, requests.size)
    }

    @Test fun waitingSubmissionRunsAfterCurrentIndexJobAndProtectsAnswerFromNextJob() = runTest(dispatcher) {
        start()
        val indexDone = CompletableDeferred<Unit>()
        val indexing = async {
            NativeWorkHandoff.documents.withLock {
                val ticket = Any()
                try { check(NativeWorkHandoff.acquire(ticket)); indexDone.await() }
                finally { NativeWorkHandoff.release(ticket) }
            }
        }
        runCurrent()
        assertEquals(ChatPreparation.Unprepared, chat.state.value.preparation)
        val retrieveNow = retrieve
        retrieve = { query -> NativeWorkHandoff.documents.withLock { retrieveNow(query) } }
        answer = { flow { emit(GenerationEvent.Text("partial")); awaitCancellation() } }
        chat.editDraft("question"); chat.send(); runCurrent()
        assertEquals(ChatPhase.Retrieving, chat.state.value.phase)
        assertTrue(requests.isEmpty())
        indexDone.complete(Unit)
        indexing.await(); runCurrent()
        assertEquals(1, queries)
        assertEquals(ChatPhase.Generating, chat.state.value.phase)
        val nextIndex = Any()
        try { assertFalse(NativeWorkHandoff.acquire(nextIndex)) }
        finally { NativeWorkHandoff.release(nextIndex) }
        assertEquals("partial", chat.state.value.turns.single().answer)
        chat.stop(); runCurrent()
    }

    @Test fun oldDocumentsAndRawHistorySurviveLaterTurnsWithoutReplayingEvidence() = runTest(dispatcher) {
        start()
        chat.editDraft("first"); chat.send(); runCurrent()
        val first = chat.state.value.turns.single()
        matches = emptyList()
        chat.editDraft("second"); chat.send(); runCurrent()
        assertEquals(first, chat.state.value.turns.first())
        assertEquals(1, requests.size)
        assertTrue(chat.state.value.turns.last().documents.isEmpty())
        matches = listOf(match())
        chat.editDraft("third"); chat.send(); runCurrent()
        assertEquals(listOf("first", "answer"), requests.last().dropLast(1).map { it.text })
        assertTrue(requests.last().last().text.endsWith("third"))
    }

    @Test fun lowScoresRefuseWithoutReopeningBudgetingStreamingOrSources() = runTest(dispatcher) {
        matches = listOf(match().copy(score = 0.2))
        start()
        events.clear()
        chat.editDraft("question"); chat.send(); runCurrent()
        assertEquals(1, opened)
        assertTrue(requests.isEmpty())
        assertFalse(events.contains("budget"))
        assertEquals(ChatPhase.Idle, chat.state.value.phase)
        val turn = chat.state.value.turns.single()
        assertTrue(turn.answer.isNotBlank())
        assertTrue(turn.documents.isEmpty())
        assertNull(turn.finish)
        assertFalse(turn.reusable)
        assertFalse(turn.failed)
        assertEquals("", chat.state.value.draft)
    }

    @Test fun budgetRemovingAllEvidenceRefusesWithoutStreaming() = runTest(dispatcher) {
        start(open = { ChatConnection(
            prepare = { text, history ->
                if (text.contains("<document>")) throw IllegalArgumentException("No room")
                budgetChatMessages(CHAT_SYSTEM_PROMPT, history, text) { 100 }
            }, stream = { error("Must not generate without evidence") }, cancel = {},
            closeAsync = { CompletableDeferred(Unit) }, countPassageTokens = { 100 }) })
        chat.editDraft("question"); chat.send(); runCurrent()
        assertTrue(chat.state.value.turns.single().answer.isNotBlank())
        assertNull(chat.state.value.turns.single().finish)
        assertTrue(chat.state.value.turns.single().documents.isEmpty())
    }

    @Test fun neighboringEvidenceReachesPromptOnceAndKeepsOriginalReferences() = runTest(dispatcher) {
        val first = match(text = "á uno")
        val second = match(text = "uno dos").copy(ordinal = 1, startByte = 3, endByte = 10)
        matches = listOf(second, first)
        start()
        chat.editDraft("question"); chat.send(); runCurrent()
        val prompt = requests.single().last().text
        assertTrue(prompt.contains("á uno dos"))
        assertEquals(1, prompt.split("uno").size - 1)
        assertEquals(1, chat.state.value.turns.single().documents.size)
        assertTrue(events.contains("authorize"))
    }

    @Test fun backgroundAndOwnerClearCancelRetrievalAndWaitForLateModelCleanup() = runTest(dispatcher) {
        val loaded = CompletableDeferred<Unit>()
        val closing = CompletableDeferred<Unit>()
        start(open = {
            if (opened > 1) withContext(NonCancellable) { loaded.await() }
            connection(if (opened > 1) closing else CompletableDeferred(Unit))
        })
        chat.editDraft("keep"); chat.send(); runCurrent()
        assertEquals(ChatPreparation.Loading, chat.state.value.preparation)
        chat.setForeground(false)
        store.clear(); runCurrent()
        val ticket = Any()
        val next = async { NativeWorkHandoff.acquire(ticket) }
        runCurrent()
        assertFalse(next.isCompleted)
        loaded.complete(Unit); runCurrent()
        assertFalse(next.isCompleted)
        closing.complete(Unit); runCurrent()
        try { assertTrue(next.await()) }
        finally { NativeWorkHandoff.release(ticket) }
        assertTrue(chat.state.value.turns.isEmpty())
        assertTrue(requests.isEmpty())
    }

    companion object {
        internal fun match(id: Long = 1, document: String = "a", source: String = document, text: String = "controlled evidence") =
            RetrievalMatch(text, 1.0, id, "generation", 0, "$document.txt",
                "content://fixture/document/$source", "content://fixture/document/$document")
    }
}
