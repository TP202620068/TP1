package com.example.tp1.chat

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tp1.inference.NativeWorkHandoff
import com.example.tp1.inference.ChatMessage
import com.example.tp1.inference.ChatRole
import com.example.tp1.inference.GenerationEvent
import com.example.tp1.inference.FinishReason
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
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
class ChatLifecycleTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val store = ViewModelStore()
    private lateinit var chat: ChatViewModel
    private var sleptMillis = 0L
    private var opened = 0
    private var closed = 0

    @Before fun replaceMain() { Dispatchers.setMain(dispatcher) }

    @After fun clearOwner() {
        try {
            store.clear()
            scheduler.runCurrent()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun connection(close: CompletableDeferred<Unit> = CompletableDeferred(Unit)) = ChatConnection(
        prepare = { _, _ -> emptyList() }, stream = { emptyFlow() }, cancel = {},
        closeAsync = { closed++; close })

    private fun start(open: suspend () -> ChatConnection = { connection() }) {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        chat = ChatViewModel(application, SavedStateHandle(), {}, { opened++; open() },
            elapsedRealtime = { scheduler.currentTime + sleptMillis })
        store.put("chat", chat)
        chat.setForeground(true)
        chat.setSelected(true)
        scheduler.runCurrent()
    }

    @Test fun briefReturnCancelsUnloadAndNextBackgroundGetsFullTwoMinutes() = runTest(dispatcher) {
        start()
        chat.setForeground(false)
        runCurrent()
        advanceTimeBy(119_999)
        chat.setForeground(true)
        runCurrent()
        advanceTimeBy(120_001)
        runCurrent()
        assertEquals(0, closed)
        assertEquals(1, opened)
        assertEquals(ChatPreparation.Ready, chat.state.value.preparation)

        chat.setForeground(false)
        runCurrent()
        advanceTimeBy(119_999)
        runCurrent()
        assertEquals(0, closed)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, closed)
        assertEquals(ChatPreparation.Unprepared, chat.state.value.preparation)
        chat.setForeground(true)
        runCurrent()
        assertEquals(2, opened)
        assertEquals(ChatPreparation.Ready, chat.state.value.preparation)
    }

    @Test fun overdueResumeUnloadsEvenIfSleepPreventedTimerFromRunning() = runTest(dispatcher) {
        start()
        chat.setForeground(false)
        runCurrent()
        sleptMillis = 120_001
        chat.setForeground(true)
        runCurrent()
        assertEquals(1, closed)
        assertEquals(2, opened)
        assertEquals(ChatPreparation.Ready, chat.state.value.preparation)
    }

    @Test fun returnDuringExpiredModelCleanupWaitsBeforeReloading() = runTest(dispatcher) {
        val closing = CompletableDeferred<Unit>()
        start { connection(closing) }
        chat.setForeground(false)
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(ChatPreparation.Closing, chat.state.value.preparation)
        chat.setForeground(true)
        runCurrent()
        assertEquals(1, opened)
        closing.complete(Unit)
        runCurrent()
        assertEquals(1, closed)
        assertEquals(2, opened)
        assertEquals(ChatPreparation.Ready, chat.state.value.preparation)
    }

    @Test fun clearingOwnerClosesImmediatelyAndCancelsPendingUnload() = runTest(dispatcher) {
        start()
        chat.setForeground(false)
        runCurrent()
        store.clear()
        runCurrent()
        assertEquals(1, closed)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, closed)
        assertEquals(1, opened)
    }

    @Test fun pendingLoadCanFinishDuringBackgroundGracePeriod() = runTest(dispatcher) {
        val loaded = CompletableDeferred<ChatConnection>()
        start { loaded.await() }
        assertEquals(ChatPreparation.Loading, chat.state.value.preparation)
        chat.setForeground(false)
        runCurrent()
        loaded.complete(connection())
        runCurrent()
        assertEquals(ChatPreparation.Ready, chat.state.value.preparation)
        chat.setForeground(true)
        runCurrent()
        assertEquals(0, closed)
        assertEquals(1, opened)
    }

    @Test fun idleRetainedChatYieldsWithoutLosingDraftAndCannotReopenUntilIndexingFinishes() = runTest(dispatcher) {
        start()
        chat.editDraft("Keep this draft")
        chat.setSelected(false)
        val ticket = Any()
        try {
            assertTrue(NativeWorkHandoff.acquire(ticket))
            assertEquals(1, closed)
            assertEquals(ChatPreparation.Unprepared, chat.state.value.preparation)
            chat.setSelected(true)
            chat.setForeground(false)
            chat.setForeground(true)
            chat.prepare()
            runCurrent()
            assertEquals(1, opened)
            assertEquals("Keep this draft", chat.state.value.draft)
        } finally { NativeWorkHandoff.release(ticket) }
        runCurrent()
        assertEquals(2, opened)
        assertEquals("Keep this draft", chat.state.value.draft)
    }

    @Test fun activeReplyReturnsBusyWithoutInterruptingConversation() = runTest(dispatcher) {
        start { ChatConnection(prepare = { text, _ -> listOf(ChatMessage(ChatRole.User, text)) }, stream = { flow {
            emit(GenerationEvent.Text("Partial reply"))
            awaitCancellation()
        } }, cancel = {}, closeAsync = { closed++; CompletableDeferred(Unit) }) }
        chat.editDraft("Question")
        chat.send()
        runCurrent()
        chat.editDraft("Next draft")
        val before = chat.state.value
        val ticket = Any()
        try {
            assertFalse(NativeWorkHandoff.acquire(ticket))
            assertEquals(before, chat.state.value)
            assertEquals(0, closed)
        } finally { NativeWorkHandoff.release(ticket) }
        chat.stop()
        runCurrent()
    }

    @Test fun handoffWaitsForNativeCloseEvenWhenAcquisitionIsCancelled() = runTest(dispatcher) {
        val closing = CompletableDeferred<Unit>()
        start { connection(closing) }
        val ticket = Any()
        val pending = async { try { NativeWorkHandoff.acquire(ticket) } finally { NativeWorkHandoff.release(ticket) } }
        runCurrent()
        assertTrue(NativeWorkHandoff.blocked)
        pending.cancel()
        runCurrent()
        assertFalse(pending.isCompleted)
        chat.prepare()
        assertEquals(1, opened)
        closing.complete(Unit)
        pending.join()
        runCurrent()
        assertFalse(NativeWorkHandoff.blocked)
        assertEquals(2, opened)
    }

    @Test fun failedCloseReleasesHandoffAndKeepsConversationAndDraft() = runTest(dispatcher) {
        val closing = CompletableDeferred<Unit>()
        start { connection(closing) }
        chat.editDraft("Retained")
        chat.setSelected(false)
        val ticket = Any()
        val pending = async {
            try { NativeWorkHandoff.acquire(ticket); fail("Close error ignored") }
            catch (_: IOException) { }
            finally { NativeWorkHandoff.release(ticket) }
        }
        runCurrent()
        closing.completeExceptionally(IOException("Synthetic close failure"))
        pending.await()
        runCurrent()
        assertFalse(NativeWorkHandoff.blocked)
        assertEquals("Retained", chat.state.value.draft)
        assertTrue(chat.state.value.turns.isEmpty())
        val retry = Any()
        try {
            assertTrue(NativeWorkHandoff.acquire(retry))
            assertEquals(1, opened)
            assertEquals(1, closed)
        } finally { NativeWorkHandoff.release(retry) }
        chat.setSelected(true)
        runCurrent()
        assertEquals(2, opened)
        assertEquals("Retained", chat.state.value.draft)
    }

    @Test fun failedBackgroundCloseDoesNotPoisonIndexingHandoff() = runTest(dispatcher) {
        val closing = CompletableDeferred<Unit>()
        start { connection(closing) }
        chat.setForeground(false)
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()
        closing.completeExceptionally(IOException("Synthetic close failure"))
        runCurrent()
        assertEquals(ChatPreparation.Unprepared, chat.state.value.preparation)
        val ticket = Any()
        try {
            assertTrue(NativeWorkHandoff.acquire(ticket))
            assertEquals(1, opened)
            assertEquals(1, closed)
        } finally { NativeWorkHandoff.release(ticket) }
    }

    @Test fun successfulConversationSurvivesHandoffAndLoadingChatCanYield() = runTest(dispatcher) {
        start { ChatConnection(prepare = { text, _ -> listOf(ChatMessage(ChatRole.User, text)) }, stream = { flow {
            emit(GenerationEvent.Text("Answer")); emit(GenerationEvent.Finished(FinishReason.EOG, 1))
        } }, cancel = {}, closeAsync = { closed++; CompletableDeferred(Unit) }) }
        chat.editDraft("Question")
        chat.send()
        runCurrent()
        val turns = chat.state.value.turns
        val ticket = Any()
        try { assertTrue(NativeWorkHandoff.acquire(ticket)); assertEquals(turns, chat.state.value.turns) }
        finally { NativeWorkHandoff.release(ticket) }
        runCurrent()
        assertEquals(turns, chat.state.value.turns)
        store.clear()
        runCurrent()

        val loading = CompletableDeferred<ChatConnection>()
        start { loading.await() }
        assertEquals(ChatPreparation.Loading, chat.state.value.preparation)
        try { assertTrue(NativeWorkHandoff.acquire(ticket)) }
        finally { NativeWorkHandoff.release(ticket) }
        runCurrent()
        loading.complete(connection())
        runCurrent()
        assertEquals(ChatPreparation.Ready, chat.state.value.preparation)
    }

    @Test fun destroyedChatRemainsInHandoffUntilItsNativeCloseCompletes() = runTest(dispatcher) {
        val closing = CompletableDeferred<Unit>()
        start { connection(closing) }
        store.clear()
        val ticket = Any()
        val pending = async { NativeWorkHandoff.acquire(ticket) }
        try {
            runCurrent()
            assertFalse(pending.isCompleted)
            assertTrue(NativeWorkHandoff.blocked)
            closing.complete(Unit)
            assertTrue(pending.await())
        } finally {
            closing.complete(Unit)
            pending.cancelAndJoin()
            NativeWorkHandoff.release(ticket)
        }
        runCurrent()
        assertEquals(1, opened)
        assertEquals(1, closed)
    }

    @Test fun handoffRetainsLateLoadBarrierWhenScheduledReleaseIsCancelled() = runTest(dispatcher) {
        val loaded = CompletableDeferred<ChatConnection>()
        val closing = CompletableDeferred<Unit>()
        start { withContext(NonCancellable) { loaded.await() } }
        val ticket = Any()
        val pending = withContext(Dispatchers.Main.immediate) {
            chat.setForeground(false)
            sleptMillis = 120_001
            chat.setForeground(true)
            // Enter before the newly scheduled release coroutine gets its first dispatch.
            this@runTest.async(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
                NativeWorkHandoff.acquire(ticket)
            }
        }
        try {
            runCurrent()
            assertFalse(pending.isCompleted)
            loaded.complete(connection(closing))
            runCurrent()
            assertFalse(pending.isCompleted)
            closing.complete(Unit)
            assertTrue(pending.await())
        } finally {
            loaded.complete(connection(closing))
            closing.complete(Unit)
            pending.cancelAndJoin()
            NativeWorkHandoff.release(ticket)
        }
        runCurrent()
        assertFalse(NativeWorkHandoff.blocked)
    }
}
