package com.example.tp1.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.tp1.MainActivity
import com.example.tp1.PickerRobot
import com.example.tp1.awaitSources
import com.example.tp1.browser.BrowserFolder
import com.example.tp1.browser.DocumentBrowser
import com.example.tp1.indexing.DocumentRetriever
import com.example.tp1.indexing.IndexStore
import com.example.tp1.indexing.IndexingCoordinator
import com.example.tp1.indexing.RetrievalMatch
import com.example.tp1.indexing.RetrievalResult
import com.example.tp1.inference.ChatMessage
import com.example.tp1.inference.InferenceSession
import com.example.tp1.inference.ManualInference
import com.example.tp1.inference.Model
import com.example.tp1.inference.ModelManager
import com.example.tp1.sources.SourcesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

/** Production automatic indexing, query embedding, tokenizer, prefill, streaming and external viewer. */
@ManualInference
class ChatGroundedModelUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val picker = PickerRobot()
    @Volatile private var submitted: List<ChatMessage>? = null
    @Volatile private var retrieved: List<RetrievalMatch> = emptyList()
    @Volatile private var queryCount = 0
    @Volatile private var streamed = false
    private val callback = ActivityLifecycleCallback { activity, stage ->
        if (activity is MainActivity && stage == Stage.CREATED) {
            ViewModelProvider(activity, viewModelFactory {
                initializer {
                    val models = ModelManager(activity.application)
                    ChatViewModel(activity.application, createSavedStateHandle(),
                        downloadModel = { check(models.isAvailable(Model.Test)) { "Provision the Chat model first" } },
                        openModel = {
                            val session = InferenceSession.open(models, Model.Test, CHAT_SYSTEM_PROMPT)
                            ChatConnection(session::prepare, { messages ->
                                submitted = messages
                                session.stream(messages).onEach { event ->
                                    if (event is com.example.tp1.inference.GenerationEvent.Text && event.value.isNotBlank()) streamed = true
                                }
                            }, session::cancel, session::closeAsync, session::countPassageTokens)
                        }, retrieve = { query ->
                            queryCount++
                            DocumentRetriever(activity.application).retrieve(query, k = 10).also {
                                if (it is RetrievalResult.Complete) retrieved = it.matches
                            }
                        })
                }
            })[ChatViewModel::class.java]
        }
    }
    @get:Rule(order = 0) val productionOwner = object : ExternalResource() {
        override fun before() {
            runBlocking {
                val models = ModelManager(context)
                check(models.isAvailable(Model.Test) && models.isAvailable(Model.JinaV5NanoRetrieval)) {
                    "Provision both pinned local models first"
                }
                IndexingCoordinator.replaceForTests(null)?.close()
            }
            instrumentation.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback) }
        }
        override fun after() {
            instrumentation.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback) }
            runBlocking { IndexingCoordinator.replaceForTests(null)?.close() }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @After fun cleanup() = picker.cleanUp()

    @Test fun automaticallyIndexedDocumentsReachRealPromptAndFooterAndOpenWithReadOnlyGrant() {
        picker.resetSources()
        picker.createFile("warranty.rtf", "nested", "application/rtf",
            "{\\rtf1\\ansi TP1 grounded viewer proof. Warranty coverage lasts two years. Keep the receipt for repairs.}".toByteArray())
        picker.createFile("gardening.rtf", mime = "application/rtf",
            bytes = "{\\rtf1\\ansi Tomatoes need sunlight and regular watering.}".toByteArray())
        compose.activityRule.scenario.recreate()
        compose.awaitSources()
        compose.onNodeWithText("Añadir").performClick()
        compose.onNodeWithText("Elegir carpeta").performClick()
        picker.pickFolder()
        compose.awaitSources()
        val indexing = IndexingCoordinator.get(context)
        compose.waitUntil(120_000) {
            val state = indexing.state.value
            check(state.blocked == null) { "Automatic indexing blocked: ${state.blocked}" }
            state.sources.values.any { !it.discovering && it.pending == 0 && it.completed == 2 }
        }
        assertTrue(indexing.state.value.sources.values.all { it.failures.isEmpty() })
        val source = ViewModelProvider(compose.activity)[SourcesViewModel::class.java].state.value.sources.single()
        val snapshots = runBlocking {
            withContext(Dispatchers.IO) {
                val browser = DocumentBrowser(context.contentResolver)
                val root = browser.root(source)
                val entries = browser.children(source, BrowserFolder(root.uri, root.metadata.name)).entries
                val files = entries.filterNot { it.directory } + entries.filter { it.directory }.flatMap {
                    browser.children(source, BrowserFolder(it.uri, it.metadata.name)).entries
                }
                IndexStore(context).use { store -> files.map { checkNotNull(store.read(source, it)) } }
            }
        }
        assertEquals(2, snapshots.size)
        assertTrue(snapshots.all { it.chunks.isNotEmpty() })
        compose.onNodeWithText("Listo").performClick()
        compose.onNode(hasText("Chat") and hasClickAction()).performClick()
        val chat = ViewModelProvider(compose.activity)[ChatViewModel::class.java]
        compose.waitUntil(60_000) { chat.state.value.preparation == ChatPreparation.Ready }
        compose.onNodeWithContentDescription("Tu pregunta").performTextInput("How long is the warranty coverage?")
        compose.onNodeWithContentDescription("Enviar pregunta").performClick()
        compose.waitUntil(180_000) { chat.state.value.phase == ChatPhase.Idle && chat.state.value.turns.isNotEmpty() }
        val turn = chat.state.value.turns.single()
        assertTrue("Real streamed output is required", streamed && turn.answer.isNotBlank())
        assertFalse(turn.failed)
        assertEquals(1, queryCount)
        val messages = checkNotNull(submitted)
        val injected = retrieved.filter { messages.last().text.contains(it.text) }
        assertTrue(injected.isNotEmpty())
        assertTrue(injected.all { match -> snapshots.any { snapshot ->
            snapshot.documentId == match.documentId && snapshot.generation == match.generation && snapshot.chunks.any { it.text == match.text }
        } })
        assertEquals(PreparedChatRequest(messages, injected).documents, turn.documents)
        assertEquals("How long is the warranty coverage?", turn.input)
        compose.onNodeWithContentDescription("Fuentes: ${turn.documents.size}").performClick()
        compose.onNodeWithText("warranty.rtf").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("warranty.rtf").assertIsDisplayed()
        compose.onNodeWithText("warranty.rtf").performClick()
        picker.awaitPackage { it == instrumentation.context.packageName }
        val proof = "TP1 grounded viewer proof: read OK; write denied; root denied; sibling denied; MIME OK; ClipData OK; uid=" +
            instrumentation.context.applicationInfo.uid
        compose.waitUntil(10_000) { picker.visibleText().contains(proof) }
    }
}
