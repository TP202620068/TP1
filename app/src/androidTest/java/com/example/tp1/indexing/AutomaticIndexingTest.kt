package com.example.tp1.indexing

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tp1.browser.DocumentBrowser
import com.example.tp1.chat.ChatConnection
import com.example.tp1.chat.ChatPhase
import com.example.tp1.chat.ChatViewModel
import com.example.tp1.extraction.ExtractedDocument
import com.example.tp1.extraction.DocumentExtractor
import com.example.tp1.extraction.ExtractionWork
import com.example.tp1.extraction.MarkdownChunk
import com.example.tp1.extraction.hex
import com.example.tp1.inference.ManualInference
import com.example.tp1.inference.Model
import com.example.tp1.inference.ModelManager
import com.example.tp1.inference.FinishReason
import com.example.tp1.inference.GenerationEvent
import com.example.tp1.sources.SourceKind
import com.example.tp1.sources.SourcesViewModel
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AutomaticIndexingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver
    private val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
    private val directory = File(context.cacheDir, "automatic-indexing-${UUID.randomUUID()}")
    private val application = object : Application() {
        init { attachBaseContext(context) }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(directory, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(directory, "cache").apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = File(directory, "no-backup").apply { mkdirs() }
    }
    private val owners = ViewModelStore()
    private lateinit var model: SourcesViewModel
    private lateinit var work: IndexingCoordinator
    private var opens = 0
    private var extracts = 0
    private var realExtraction = false
    private var beforeEmbed: suspend () -> Unit = {}
    private val selected = mutableSetOf<String>()

    private fun fixture(method: String, arg: String? = null, extras: Bundle? = null): Bundle {
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
        return try { checkNotNull(resolver.call("content://com.example.tp1.test.documents".toUri(), method, arg, extras)) }
        finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
    }

    private fun document(id: String): String = fixture("fixture-retrieval", "r-auto-$id", Bundle().apply {
        putByteArray("bytes", ("{\\rtf1\\ansi " + "Warranty coverage lasts two years. Keep the receipt.\\par ".repeat(2) + "}").toByteArray())
        putString("name", "$id.rtf")
    }).getString("uri")!!

    private fun folder(id: String, children: List<String>, root: Boolean = false, state: String = "ready"): String =
        fixture("fixture-index-folder", "t-auto-$id", Bundle().apply {
            putStringArray("children", children.toTypedArray()); putBoolean("root", root); putString("state", state)
        }).getString("uri")!!

    private suspend fun newOwner() {
        withContext(Dispatchers.Main) {
            model = SourcesViewModel(application, SavedStateHandle())
            owners.put("sources", model)
        }
        withTimeout(10_000) { model.state.first { !it.restoring && !it.busy } }
    }

    private suspend fun add(uris: List<String>, kind: SourceKind = SourceKind.Document, offered: Int = flags) {
        selected += uris.filter { it.startsWith("content://com.example.tp1.test.documents/") }
        withContext(Dispatchers.Main) { model.add(uris, kind, offered) }
        withTimeout(10_000) { model.state.first { !it.busy } }
    }

    private suspend fun finished(uri: String) = withTimeout(30_000) {
        work.state.first { it.sources[uri]?.let { source -> !source.discovering && source.pending == 0 } == true }
    }

    @Before fun setup() = runBlocking {
        IndexingCoordinator.replaceForTests(null)?.close()
        val browser = DocumentBrowser(resolver)
        work = IndexingCoordinator(CoroutineScope(SupervisorJob() + Dispatchers.IO), browser::root,
            { source, folder -> browser.children(source, folder) },
            { source, entry, check ->
                DocumentIndexer(application, checkAdmission = check, extract = { selected, document ->
                    extracts++
                    if (realExtraction) DocumentExtractor(application).extract(selected, document)
                    else {
                        val text = "Controlled document ${document.metadata.name}"
                        ExtractedDocument(text, document.metadata.name, MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).hex())
                    }
                }, openEmbeddings = {
                    opens++
                    IndexEmbeddings({ text -> listOf(MarkdownChunk(text, 0, text.toByteArray().size, 0, text.toByteArray().size, text.length, 8)) },
                        { beforeEmbed(); FloatArray(512).apply { this[0] = 1f } }, {})
                }).index(source, entry, refresh = false)
            }, { source -> ExtractionWork.requireSavedRead(application, source.record) })
        IndexingCoordinator.replaceForTests(work)
        newOwner()
    }

    @After fun cleanup() = runBlocking {
        withContext(Dispatchers.Main) { owners.clear() }
        IndexingCoordinator.replaceForTests(null)?.close()
        selected.forEach { uri ->
            try { resolver.releasePersistableUriPermission(uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: Exception) { }
        }
        directory.deleteRecursively()
        Unit
    }

    @Test fun acceptedMembersOfMultiSelectionAutomaticallyPublishAndRejectedMembersDoNot() = runBlocking {
        val first = document("first")
        val second = document("second")
        add(listOf(first, "file:///not-authorized", second, first))
        finished(first); finished(second)
        assertEquals(2, model.state.value.sources.size)
        assertEquals(2, extracts)
        assertEquals(2, opens)
        val browser = DocumentBrowser(resolver)
        IndexStore(application).use { store ->
            model.state.value.sources.forEach { source -> assertNotNull(store.read(source, browser.root(source))) }
        }
        assertNotNull(model.state.value.message)
    }

    @Test fun providerDatabaseFailureDoesNotBlockHealthySourcesOrLeaveARemovalBlocker() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        beforeEmbed = { entered.complete(Unit); release.await() }
        val gate = document("provider-gate")
        add(listOf(gate))
        withTimeout(5_000) { entered.await() }
        val broken = document("provider-broken")
        val healthy = document("provider-healthy")
        add(listOf(broken, healthy))
        fixture("fixture-retrieval-control", "r-auto-provider-broken", Bundle().apply { putString("state", "sqlite") })
        beforeEmbed = {}
        release.complete(Unit)
        val failed = withTimeout(5_000) {
            work.state.first { it.blocked != null || it.sources[broken]?.let { source -> !source.discovering && source.pending == 0 } == true }
        }
        assertNull(failed.blocked)
        assertEquals(IndexingProblem.Document, failed.sources.getValue(broken).failures.single().problem)
        finished(healthy)
        assertEquals(1, work.state.value.sources.getValue(healthy).completed)
        withContext(Dispatchers.Main) { model.remove(broken).join(); model.refresh().join() }
        assertNull(work.state.value.blocked)
        assertFalse(broken in work.state.value.sources)
        assertEquals(2, extracts)
    }

    @Test fun localStagingFailureBlocksWithoutDrainingTheQueueAndRetryRecovers() = runBlocking {
        realExtraction = true
        val staging = ExtractionWork.operations(application)
        assertFalse(staging.exists())
        staging.writeText("Block this test's extraction directory")
        val first = document("storage-first")
        val second = document("storage-second")
        add(listOf(first, second))
        val blocked = withTimeout(5_000) {
            work.state.first { it.blocked != null || it.sources[first]?.let { source -> !source.discovering && source.pending == 0 } == true }
        }
        assertEquals(IndexingProblem.Storage, blocked.blocked)
        assertEquals(2, blocked.sources.values.sumOf { it.pending })
        assertTrue(blocked.sources.values.all { it.failures.isEmpty() })
        assertEquals(1, extracts)
        assertEquals(0, opens)
        IndexStore(application).use { store ->
            model.state.value.sources.forEach { source -> assertNull(store.read(source, DocumentBrowser(resolver).root(source))) }
        }
        assertTrue(staging.delete())
        withContext(Dispatchers.Main) { model.retryIndexing(first).join() }
        finished(first); finished(second)
        assertNull(work.state.value.blocked)
        assertTrue(work.state.value.sources.values.all { it.completed == 1 && it.failures.isEmpty() })
        assertEquals(3, extracts)
        assertEquals(2, opens)
    }

    @Test fun providerCopyFailureIsNotMisclassifiedAsLocalStorageFailure() = runBlocking {
        realExtraction = true
        val broken = document("copy-broken")
        val healthy = document("copy-healthy")
        fixture("fixture-retrieval-control", "r-auto-copy-broken", Bundle().apply { putString("state", "read-failure") })
        add(listOf(broken, healthy))
        finished(broken); finished(healthy)
        assertNull(work.state.value.blocked)
        assertEquals(IndexingProblem.Document, work.state.value.sources.getValue(broken).failures.single().problem)
        assertEquals(1, work.state.value.sources.getValue(healthy).completed)
        assertEquals(2, extracts)
        assertEquals(1, opens)
    }

    @Test fun revocationInsideIndexingRejectsPublicationAndReadableRestoreReadmits() = runBlocking {
        realExtraction = true
        beforeEmbed = {
            fixture("fixture-retrieval-revoke", "r-auto-revoked")
            throw SecurityException("Revoked during embedding")
        }
        val uri = document("revoked")
        add(listOf(uri))
        val lost = withTimeout(5_000) { work.state.first { it.sources[uri]?.failures?.isNotEmpty() == true } }
            .sources.getValue(uri)
        assertEquals(IndexingProblem.Access, lost.failures.single().problem)
        assertEquals(0, lost.pending)
        assertFalse(lost.running)
        document("revoked")
        resolver.takePersistableUriPermission(uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val source = model.state.value.sources.single()
        val entry = DocumentBrowser(resolver).root(source)
        IndexStore(application).use { assertNull(it.read(source, entry)) }
        beforeEmbed = {}
        withContext(Dispatchers.Main) { model.refresh().join() }
        finished(uri)
        IndexStore(application).use { assertNotNull(it.read(source, entry)) }
        assertEquals(2, extracts)
        assertEquals(2, opens)
    }

    @Test fun deniedAndEmptySelectionsDoNotAdmitWork() = runBlocking {
        add(emptyList())
        add(listOf(document("denied")), offered = 0)
        assertTrue(work.state.value.sources.isEmpty())
        assertTrue(model.state.value.sources.isEmpty())
        assertEquals(0, opens)
    }

    @Test fun failedPersistenceDoesNotAdmitEvenAReadGrantedFile() = runBlocking {
        val pending = File(application.filesDir, "sources.json.new").apply { mkdirs() }
        File(pending, "blocker").writeText("keep")
        add(listOf(document("save-failed")))
        assertFalse(model.state.value.storageReady)
        assertTrue(work.state.value.sources.isEmpty())
        assertEquals(0, extracts)
    }

    @Test fun providerTreeKeepsAnchorsDeduplicatesCyclesAndReportsIncompleteBranches() = runBlocking {
        document("tree-a"); document("tree-b")
        folder("nested", listOf("r-auto-tree-a", "r-auto-tree-b", "t-auto-root"))
        folder("pending", emptyList(), state = "pending")
        folder("broken", emptyList(), state = "failure")
        val tree = folder("root", listOf("r-auto-tree-a", "t-auto-nested", "t-auto-nested", "t-auto-pending", "t-auto-broken"), root = true)
        add(listOf(tree), SourceKind.Tree)
        val result = finished(tree).sources.getValue(tree)
        assertEquals(2, result.completed)
        assertTrue(result.incomplete)
        assertEquals(IndexingProblem.Discovery, result.failures.single().problem)
        assertEquals(1, model.state.value.sources.size)
        val source = model.state.value.sources.single()
        val leaf = DocumentsContract.buildDocumentUriUsingTree(tree.toUri(), "r-auto-tree-b").toString()
        val entry = DocumentBrowser(resolver).children(source,
            com.example.tp1.browser.BrowserFolder(DocumentsContract.buildDocumentUriUsingTree(tree.toUri(), "t-auto-nested").toString(), "nested"))
            .entries.single { it.uri == leaf }
        IndexStore(application).use { assertNotNull(it.read(source, entry)) }
        assertEquals(2, extracts)
    }

    @Test fun ownerDestructionResumeAndFreshProcessCoordinatorReuseSavedSnapshots() = runBlocking {
        val uri = document("recovery")
        add(listOf(uri)); finished(uri)
        val source = model.state.value.sources.single()
        val entry = DocumentBrowser(resolver).root(source)
        val generation = IndexStore(application).use { it.read(source, entry)!!.generation }
        repeat(3) { withContext(Dispatchers.Main) { model.refresh().join() } }
        withContext(Dispatchers.Main) { owners.clear() }
        newOwner(); finished(uri)
        assertEquals(1, extracts)
        work.close()
        IndexingCoordinator.replaceForTests(null)
        work = IndexingCoordinator.get(application)
        withContext(Dispatchers.Main) { owners.clear() }
        newOwner(); finished(uri)
        assertEquals(generation, IndexStore(application).use { it.read(source, entry)!!.generation })
        assertEquals(1, opens)
    }

    @Test fun cancelledOldRestoreCannotCancelANewerOwnersSelection() = runBlocking {
        for (unavailable in listOf(false, true)) {
            val id = if (unavailable) "old-restore-unavailable" else "old-restore"
            val first = document(id)
            add(listOf(first)); finished(first)
            fixture("fixture-retrieval-control", "r-auto-$id", Bundle().apply { putInt("pauseAt", 1) })
            val oldRefresh = withContext(Dispatchers.Main) { model.refresh() }
            try {
                withTimeout(5_000) {
                    while (!fixture("fixture-retrieval-info", "r-auto-$id").getBoolean("paused")) delay(10)
                }
                withContext(Dispatchers.Main) { owners.clear() }
                newOwner()
                val second = document("new-owner-$unavailable")
                add(listOf(second)); finished(second)
                if (unavailable) fixture("fixture-retrieval-control", "r-auto-$id", Bundle().apply { putString("state", "failure") })
                fixture("fixture-retrieval-release")
                oldRefresh.join()
                assertEquals(model.state.value.sources.map { it.record.uri }.toSet(), work.state.value.sources.keys)
                assertEquals(1, work.state.value.sources.getValue(second).completed)
                assertNull(work.state.value.blocked)
            } finally { fixture("fixture-retrieval-release") }
        }
        assertEquals(4, extracts)
    }

    @Test fun removalDuringEmbeddingCancelsPublicationAndReaddBuildsFreshWork() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        beforeEmbed = { entered.complete(Unit); release.await() }
        val uri = document("removed")
        add(listOf(uri))
        withTimeout(10_000) { entered.await() }
        withContext(Dispatchers.Main) { model.remove(uri).join() }
        assertFalse(uri in work.state.value.sources)
        beforeEmbed = {}
        document("removed")
        add(listOf(uri))
        release.complete(Unit)
        finished(uri)
        val source = model.state.value.sources.single()
        IndexStore(application).use { assertNotNull(it.read(source, DocumentBrowser(resolver).root(source))) }
        assertEquals(2, extracts)
    }

    @Test fun activeReplyMakesAutomaticIndexingWaitWithoutStoppingChat() = runBlocking {
        val answer = CompletableDeferred<Unit>()
        var cancelled = false
        val chat = withContext(Dispatchers.Main) {
            ChatViewModel(application, SavedStateHandle(), {}, {
                ChatConnection({ _, _ -> emptyList() }, { flow {
                    answer.await()
                    emit(GenerationEvent.Text("Kept answer"))
                    emit(GenerationEvent.Finished(FinishReason.EOG, 2, 0, null))
                 } }, { cancelled = true }, { CompletableDeferred(Unit) }, { 100 })
             }, retrieve = { RetrievalResult.Complete(listOf(com.example.tp1.chat.ChatGroundingTest.match())) }, checkContext = {}).also {
                owners.put("chat", it); it.setForeground(true); it.setSelected(true)
            }
        }
        withTimeout(5_000) { chat.state.first { it.preparation == com.example.tp1.chat.ChatPreparation.Ready } }
        withContext(Dispatchers.Main) { chat.editDraft("Question"); chat.send() }
        withTimeout(5_000) { chat.state.first { it.phase == ChatPhase.Generating } }
        val uri = document("busy")
        add(listOf(uri))
        withTimeout(5_000) { work.state.first { it.sources[uri]?.waitingForChat == true } }
        assertFalse(cancelled)
        assertEquals(0, extracts)
        assertEquals(ChatPhase.Generating, chat.state.value.phase)
        answer.complete(Unit)
        finished(uri)
        assertEquals("Kept answer", chat.state.value.turns.single().answer)
        assertEquals(1, opens)
    }

    @Test fun failedRemovalStillCancelsOldWorkAndAReadableRestoreCanRecover() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val uri = document("failed-removal")
        beforeEmbed = { entered.complete(Unit); CompletableDeferred<Unit>().await() }
        add(listOf(uri))
        withTimeout(5_000) { entered.await() }
        val pending = File(application.filesDir, "sources.json.new").apply { mkdirs() }
        File(pending, "blocker").writeText("keep")
        withContext(Dispatchers.Main) { model.remove(uri).join() }
        assertFalse(model.state.value.storageReady)
        assertFalse(uri in work.state.value.sources)
        val source = model.state.value.sources.single()
        IndexStore(application).use { assertNull(it.read(source, DocumentBrowser(resolver).root(source))) }
        pending.deleteRecursively()
        beforeEmbed = {}
        withContext(Dispatchers.Main) { model.refresh().join() }
        finished(uri)
        assertEquals(2, extracts)
    }

    @Test fun unknownRecordsSurviveAndCorruptRestoreNeverMeansAnEmptySuccessfulCrawl() = runBlocking {
        val uri = document("records")
        add(listOf(uri)); finished(uri)
        val file = File(application.filesDir, "sources.json")
        val records = org.json.JSONArray(file.readText()).put(org.json.JSONObject().put("kind", "Future").put("uri", "keep"))
        file.writeText(records.toString())
        withContext(Dispatchers.Main) { model.refresh().join() }
        assertEquals("Future", org.json.JSONArray(file.readText()).getJSONObject(1).getString("kind"))
        file.writeText("broken JSON")
        withContext(Dispatchers.Main) { model.refresh().join() }
        assertFalse(model.state.value.storageReady)
        assertEquals(IndexingProblem.Storage, work.state.value.blocked)
        assertEquals(1, work.state.value.sources.size)
        assertEquals("broken JSON", file.readText())
        file.writeText(records.toString())
        withContext(Dispatchers.Main) { model.refresh().join() }
        assertNull(work.state.value.blocked)
        assertEquals(1, extracts)
    }

    @Test @ManualInference fun productionSelectionAutomaticallyIndexesRealProviderFilesAndRecoversFromSavedMembership() = runBlocking {
        withContext(Dispatchers.Main) { owners.clear() }
        IndexingCoordinator.replaceForTests(null)?.close()
        val originalModels = ModelManager(context)
        check(originalModels.isAvailable(Model.JinaV5NanoRetrieval)) { "Provision the pinned Jina weights first" }
        val localModel = ModelManager(application).location(Model.JinaV5NanoRetrieval)
        localModel.parentFile!!.mkdirs()
        // Android denies app hard links; copy only into this test's disposable storage, never acquire weights.
        originalModels.location(Model.JinaV5NanoRetrieval).copyTo(localModel)
        check(localModel.setReadOnly())
        work = IndexingCoordinator.get(application)
        newOwner()
        val direct = document("real-direct")
        document("real-nested")
        folder("real-nested", listOf("r-auto-real-nested"))
        val tree = folder("real-root", listOf("t-auto-real-nested"), root = true)
        add(listOf(direct))
        add(listOf(tree), SourceKind.Tree)
        withTimeout(120_000) {
            work.state.first { state ->
                check(state.blocked == null) { "Production indexing blocked: ${state.blocked}" }
                state.sources.size == 2 && state.sources.values.all { !it.discovering && it.pending == 0 }
            }
        }
        assertTrue(work.state.value.sources.values.all { it.completed == 1 && it.failures.isEmpty() })
        val browser = DocumentBrowser(resolver)
        val snapshots = model.state.value.sources.map { source ->
            val entry = if (source.record.kind == SourceKind.Document) browser.root(source)
            else browser.children(source, com.example.tp1.browser.BrowserFolder(
                DocumentsContract.buildDocumentUriUsingTree(tree.toUri(), "t-auto-real-nested").toString(), "nested")).entries.single()
            Triple(source, entry, IndexStore(application).use { it.read(source, entry)!! })
        }
        snapshots.forEach { (_, _, snapshot) ->
            assertTrue(snapshot.chunks.isNotEmpty())
            assertTrue(snapshot.chunks.joinToString(" ") { it.text }.contains("Warranty coverage lasts two years"))
            assertTrue(snapshot.chunks.all { it.vector.size == 512 && it.inputTokens in 1..1024 })
        }
        withContext(Dispatchers.Main) { owners.clear() }
        IndexingCoordinator.replaceForTests(null)?.close()
        work = IndexingCoordinator.get(application)
        newOwner()
        withTimeout(30_000) { work.state.first { it.sources.size == 2 && it.sources.values.all { source -> !source.discovering && source.pending == 0 } } }
        snapshots.forEach { (source, entry, snapshot) ->
            assertEquals(snapshot.generation, IndexStore(application).use { it.read(source, entry)!!.generation })
        }
    }
}
