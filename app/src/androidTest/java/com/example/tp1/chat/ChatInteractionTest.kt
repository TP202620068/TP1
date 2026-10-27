package com.example.tp1.chat

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tp1.inference.ChatMessage
import com.example.tp1.inference.FinishReason
import com.example.tp1.inference.GenerationEvent
import com.example.tp1.inference.budgetChatMessages
import com.example.tp1.ui.AppTab
import com.example.tp1.ui.TP1App
import com.example.tp1.ui.theme.TP1Theme
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChatInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    private lateinit var chat: ChatViewModel
    private val requests = mutableListOf<List<ChatMessage>>()
    private val streams = ArrayDeque<Flow<GenerationEvent>>()
    private var closed = 0
    private var stopped = 0

    private fun connection(prepare: suspend (String, List<ChatMessage>) -> List<ChatMessage> = { text, history ->
        budgetChatMessages(CHAT_SYSTEM_PROMPT, history, text) { 100 }
    }) = ChatConnection(prepare, { messages -> requests += messages; streams.removeFirst() },
        cancel = { stopped++ }, closeAsync = { closed++; CompletableDeferred(Unit) })

    private fun show(download: suspend () -> Unit = {}, open: suspend () -> ChatConnection = { connection() },
                     savedState: SavedStateHandle = SavedStateHandle()) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            chat = ChatViewModel(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application,
                savedState, download, open)
            store.put("chat", chat)
            chat.setForeground(true)
        }
        compose.setContent {
            val state by chat.state.collectAsState()
            TP1Theme {
                TP1App(initialTab = AppTab.Chat, chat = state, onChatSelected = chat::setSelected,
                    onChatDraftChange = chat::editDraft, onChatPrepare = chat::prepare,
                    onChatSend = chat::send, onChatStop = chat::stop, onChatRetry = chat::retry)
            }
        }
    }

    private fun prepare() {
        compose.onNodeWithText("Preparar chat").performClick()
        compose.waitUntil(5_000) { chat.state.value.preparation == ChatPreparation.Ready }
    }

    private fun send(text: String) {
        compose.onNodeWithContentDescription("Tu pregunta").performTextInput(text)
        compose.onNodeWithContentDescription("Enviar pregunta").performClick()
    }

    private fun complete(text: String) = flow {
        emit(GenerationEvent.Text(text))
        emit(GenerationEvent.Finished(FinishReason.EOG, 2))
    }

    @After fun clearOwner() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
    }

    @Test fun oversizedPastePreservesDraftAndSavedStateUntilAnAcceptedEdit() {
        val savedState = SavedStateHandle(mapOf("chatDraft" to "Conservar 😀"))
        show(savedState = savedState)
        compose.onNodeWithContentDescription("Tu pregunta").performTextInput("x".repeat(1024 * 1024))
        prepare()
        compose.runOnIdle {
            assertEquals(ChatProblem.InvalidInput, chat.state.value.problem)
            assertEquals("Conservar 😀", chat.state.value.draft)
        }
        compose.onNodeWithText("El mensaje contiene caracteres no válidos o es demasiado largo.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Enviar pregunta").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals("Conservar 😀", chat.state.value.draft)
            assertEquals("Conservar 😀", savedState.get<String>("chatDraft"))
            chat.send()
            assertTrue(requests.isEmpty())
        }
        compose.onNodeWithContentDescription("Tu pregunta").performTextReplacement("Corregido 😀")
        compose.onNodeWithContentDescription("Enviar pregunta").assertIsEnabled()
        compose.onNodeWithText("El mensaje contiene caracteres no válidos o es demasiado largo.").assertDoesNotExist()
        compose.runOnIdle { assertEquals("Corregido 😀", savedState.get<String>("chatDraft")) }
    }

    @Test fun draftLengthBoundaryIsAcceptedForEditsAndRestoration() {
        val boundary = "😀".repeat(32_768)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            val savedState = SavedStateHandle()
            chat = ChatViewModel(application, savedState, {}, { connection() })
            store.put("chat", chat)
            chat.editDraft(boundary)
            assertEquals(boundary, chat.state.value.draft)
            assertEquals(boundary, savedState.get<String>("chatDraft"))
            assertNull(chat.state.value.problem)
            chat.editDraft(boundary + "x")
            assertEquals(boundary, chat.state.value.draft)
            assertEquals(boundary, savedState.get<String>("chatDraft"))
            assertEquals(ChatProblem.InvalidInput, chat.state.value.problem)
            chat.editDraft(boundary)
            assertEquals(ChatProblem.InvalidInput, chat.state.value.problem)
            val restored = ChatViewModel(application, savedState, {}, { connection() })
            store.put("restored", restored)
            assertEquals(boundary, restored.state.value.draft)
            assertNull(restored.state.value.problem)
        }
    }

    @Test fun oversizedRestoredDraftIsClearedWithVisibleFeedback() {
        val savedState = SavedStateHandle(mapOf("chatDraft" to "x".repeat(1024 * 1024)))
        show(savedState = savedState)
        compose.onNodeWithText("El mensaje contiene caracteres no válidos o es demasiado largo.").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("", chat.state.value.draft)
            assertEquals("", savedState.get<String>("chatDraft"))
            assertEquals(ChatProblem.InvalidInput, chat.state.value.problem)
        }
        compose.onNodeWithContentDescription("Tu pregunta").performTextInput("Nuevo borrador")
        compose.onNodeWithText("El mensaje contiene caracteres no válidos o es demasiado largo.").assertDoesNotExist()
        compose.runOnIdle { assertEquals("Nuevo borrador", savedState.get<String>("chatDraft")) }
    }

    @Test fun streamsBeforeCompletionRejectsDuplicateSendAndReplaysPriorTurn() {
        val finish = CompletableDeferred<Unit>()
        streams += flow {
            emit(GenerationEvent.Text("Hola"))
            finish.await()
            emit(GenerationEvent.Text(" mundo"))
            emit(GenerationEvent.Finished(FinishReason.EOG, 2))
        }
        streams += complete("Segunda respuesta")
        show()
        compose.onNodeWithContentDescription("Enviar pregunta").assertIsNotEnabled()
        prepare()
        send("Primera pregunta")
        compose.onNodeWithText("Hola").assertIsDisplayed()
        compose.onNodeWithContentDescription("Detener respuesta").assertIsEnabled()
        compose.runOnIdle { chat.send(); assertTrue(chat.state.value.turns.single().running) }
        compose.onNodeWithContentDescription("Tu pregunta").performTextInput("Segunda pregunta")
        compose.runOnIdle { finish.complete(Unit) }
        compose.waitUntil(5_000) { chat.state.value.phase == ChatPhase.Idle }
        compose.onNodeWithText("Hola mundo").assertIsDisplayed()
        compose.onNodeWithContentDescription("Enviar pregunta").performClick()
        compose.waitUntil(5_000) { chat.state.value.turns.size == 2 && chat.state.value.phase == ChatPhase.Idle }
        compose.runOnIdle {
            assertEquals(2, requests.size)
            assertEquals(listOf("Primera pregunta", "Hola mundo", "Segunda pregunta"), requests.last().map { it.text })
        }
    }

    @Test fun stopWaitsForCleanupAndKeepsPartialTextOutOfRetriedHistory() {
        val cleanup = CompletableDeferred<Unit>()
        streams += flow {
            try { emit(GenerationEvent.Text("Respuesta parcial")); awaitCancellation() }
            finally { withContext(NonCancellable) { cleanup.await() } }
        }
        streams += complete("Respuesta completa")
        try {
            show()
            prepare()
            send("Pregunta original")
            compose.onNodeWithText("Respuesta parcial").assertIsDisplayed()
            compose.onNodeWithContentDescription("Detener respuesta").performClick()
            compose.onNodeWithContentDescription("Deteniendo respuesta…").assertIsNotEnabled()
            compose.runOnIdle { assertEquals(ChatPhase.Stopping, chat.state.value.phase); assertEquals(1, stopped) }
            cleanup.complete(Unit)
            compose.waitUntil(5_000) { chat.state.value.phase == ChatPhase.Idle }
            compose.onNodeWithText("Respuesta detenida.").assertIsDisplayed()
            compose.onNodeWithText("Reintentar").performClick()
            compose.waitUntil(5_000) { chat.state.value.turns.single().answer == "Respuesta completa" }
            compose.onAllNodesWithText("Pregunta original").assertCountEquals(1)
            compose.runOnIdle { assertEquals(listOf("Pregunta original"), requests.last().map { it.text }) }
        } finally { cleanup.complete(Unit) }
    }

    @Test fun preparationFailureAndContextFailurePreserveDraftForRetry() {
        var downloads = 0
        var reject = true
        streams += complete("Listo")
        show(download = { if (++downloads == 1) throw IOException("Fixture acquisition failure") },
            open = { connection { text, history ->
                if (reject) throw IllegalArgumentException("Fixture token limit")
                budgetChatMessages(CHAT_SYSTEM_PROMPT, history, text) { 100 }
            } })
        compose.onNodeWithContentDescription("Tu pregunta").performTextInput("Conservar borrador")
        compose.onNodeWithText("Preparar chat").performClick()
        compose.onNodeWithText("Reintentar").performClick()
        compose.waitUntil(5_000) { chat.state.value.preparation == ChatPreparation.Ready }
        compose.onNodeWithContentDescription("Enviar pregunta").performClick()
        compose.waitUntil(5_000) { chat.state.value.problem == ChatProblem.ContextLimit }
        compose.runOnIdle {
            assertEquals("Conservar borrador", chat.state.value.draft)
            assertTrue(chat.state.value.turns.isEmpty())
            reject = false
        }
        compose.onNodeWithContentDescription("Enviar pregunta").performClick()
        compose.waitUntil(5_000) { chat.state.value.turns.singleOrNull()?.answer == "Listo" }
    }

    @Test fun nativeFailureIsVisibleAndRetryReplacesTheSameTurn() {
        streams += flow {
            emit(GenerationEvent.Text("Parcial"))
            emit(GenerationEvent.Finished(FinishReason.NATIVE_FAILURE, 1, detail = "Internal fixture diagnostic"))
        }
        streams += complete("Recuperada")
        show()
        prepare()
        send("Pregunta")
        compose.waitUntil(5_000) { chat.state.value.turns.singleOrNull()?.failed == true }
        compose.onNodeWithText("Internal fixture diagnostic").assertDoesNotExist()
        compose.onNodeWithText("Reintentar").performClick()
        compose.waitUntil(5_000) { chat.state.value.turns.single().answer == "Recuperada" }
        compose.onAllNodesWithText("Pregunta").assertCountEquals(1)
    }

    @Test fun lateLoadAfterLeavingChatClosesWithoutPublishingReady() {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        try {
            show(open = {
                withContext(NonCancellable) { entered.complete(Unit); gate.await() }
                connection()
            })
            compose.onNodeWithContentDescription("Tu pregunta").performTextInput("Borrador retenido")
            compose.onNodeWithText("Preparar chat").performClick()
            compose.waitUntil(5_000) { entered.isCompleted }
            compose.onNode(hasText("Explorador") and hasClickAction()).performClick()
            gate.complete(Unit)
            compose.waitUntil(5_000) { chat.state.value.preparation == ChatPreparation.Unprepared }
            compose.runOnIdle { assertEquals(1, closed); assertEquals("Borrador retenido", chat.state.value.draft) }
            compose.onNode(hasText("Chat") and hasClickAction()).performClick()
            compose.onNodeWithText("Chat listo").assertDoesNotExist()
            compose.onNodeWithText("Preparar chat").assertIsDisplayed()
        } finally { gate.complete(Unit) }
    }

    @Test fun backgroundAndFinalOwnerClearReleaseResourcesWithoutLosingTranscript() {
        streams += complete("Respuesta guardada")
        show()
        prepare()
        send("Hola")
        compose.waitUntil(5_000) { chat.state.value.phase == ChatPhase.Idle && chat.state.value.turns.isNotEmpty() }
        compose.runOnIdle { chat.setForeground(false) }
        compose.waitUntil(5_000) { chat.state.value.preparation == ChatPreparation.Unprepared }
        compose.runOnIdle { assertEquals(1, closed); chat.setForeground(true) }
        prepare()
        compose.runOnIdle { store.clear(); assertEquals(2, closed); assertEquals("Respuesta guardada", chat.state.value.turns.single().answer) }
    }

    @Test fun backgroundDuringGenerationSuppressesLateOutputAndWaitsForCleanup() {
        val cleanup = CompletableDeferred<Unit>()
        streams += flow {
            emit(GenerationEvent.Text("Parcial visible"))
            withContext(NonCancellable) { cleanup.await() }
            emit(GenerationEvent.Text("No debe publicarse"))
            emit(GenerationEvent.Finished(FinishReason.EOG, 2))
        }
        try {
            show()
            prepare()
            send("Hola")
            compose.onNodeWithText("Parcial visible").assertIsDisplayed()
            compose.runOnIdle { chat.setForeground(false); chat.setSelected(false) }
            compose.runOnIdle {
                assertEquals(ChatPreparation.Closing, chat.state.value.preparation)
                assertEquals(ChatPhase.Stopping, chat.state.value.phase)
                chat.setForeground(true)
                chat.setSelected(true)
                chat.prepare()
                assertEquals(ChatPreparation.Closing, chat.state.value.preparation)
            }
            cleanup.complete(Unit)
            compose.waitUntil(5_000) { chat.state.value.preparation == ChatPreparation.Unprepared }
            compose.runOnIdle {
                assertEquals("Parcial visible", chat.state.value.turns.single().answer)
                assertEquals(FinishReason.CANCELLED, chat.state.value.turns.single().finish)
                assertTrue(chat.state.value.turns.replayMessages().isEmpty())
                assertEquals(1, closed)
            }
        } finally { cleanup.complete(Unit) }
    }

    @Test fun clearingOwnerDuringLoadClosesLateAcquisitionIndependentlyOfScope() {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        try {
            show(open = {
                withContext(NonCancellable) { entered.complete(Unit); gate.await() }
                connection()
            })
            compose.onNodeWithText("Preparar chat").performClick()
            compose.waitUntil(5_000) { entered.isCompleted }
            compose.runOnIdle { store.clear() }
            gate.complete(Unit)
            compose.waitUntil(5_000) { closed == 1 }
            compose.runOnIdle { assertNotEquals(ChatPreparation.Ready, chat.state.value.preparation) }
        } finally { gate.complete(Unit) }
    }

    @Test fun streamingDoesNotPullReaderAwayFromEarlierMessages() {
        val finish = CompletableDeferred<Unit>()
        streams += flow {
            emit(GenerationEvent.Text("Línea de respuesta\n".repeat(100)))
            finish.await()
            emit(GenerationEvent.Text("Última línea"))
            emit(GenerationEvent.Finished(FinishReason.LENGTH, 256))
        }
        show()
        prepare()
        send("Respuesta larga")
        compose.waitUntil(5_000) { chat.state.value.turns.singleOrNull()?.answer?.isNotEmpty() == true }
        val notice = compose.onNodeWithText("Chat local. La consulta de archivos todavía no está disponible.")
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        notice.assertIsDisplayed()
        compose.runOnIdle { finish.complete(Unit) }
        compose.waitUntil(5_000) { chat.state.value.phase == ChatPhase.Idle }
        notice.assertIsDisplayed()
        compose.onNodeWithContentDescription("Enviar pregunta").assertIsDisplayed()
    }
}
