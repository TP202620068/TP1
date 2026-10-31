package com.example.tp1.chat

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
}
