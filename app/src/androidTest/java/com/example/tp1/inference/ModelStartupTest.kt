package com.example.tp1.inference

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tp1.MainActivity
import com.example.tp1.OfflineIndexingRule
import com.example.tp1.PickerRobot
import com.example.tp1.chat.ChatPreparation
import com.example.tp1.chat.ChatViewModel
import com.example.tp1.extraction.ExtractionWork
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ModelStartupTest {
    private val entered = Model.entries.associateWith { CompletableDeferred<Unit>() }
    private val release = CompletableDeferred<Unit>()
    private val calls = ConcurrentHashMap<Model, AtomicInteger>()
    @get:Rule val offline = OfflineIndexingRule { model ->
        calls.computeIfAbsent(model) { AtomicInteger() }.incrementAndGet()
        entered.getValue(model).complete(Unit)
        release.await()
    }

    @Test fun launchAndRecreationAcquireBothWithoutLoadingNative() = runBlocking<Unit> {
        PickerRobot().resetSources()
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            withTimeout(5_000) { entered.values.forEach { it.await() } }
            activity.recreate()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(Model.entries.toSet(), calls.keys)
            assertTrue(calls.values.all { it.get() == 1 })
            assertFalse(ExtractionWork.permit.isLocked)
            assertFalse(NativeWorkHandoff.documents.isLocked)
            activity.onActivity {
                assertFalse(NativeWorkHandoff.blocked)
                assertEquals(ChatPreparation.Unprepared, ViewModelProvider(it)[ChatViewModel::class.java].state.value.preparation)
            }
            release.complete(Unit)
            activity.recreate()
            assertTrue(calls.values.all { it.get() == 1 })
        }
    }
}
