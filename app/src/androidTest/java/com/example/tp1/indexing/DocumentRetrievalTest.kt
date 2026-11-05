package com.example.tp1.indexing

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.DocumentsContract
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tp1.browser.BrowserEntry
import com.example.tp1.browser.DocumentBrowser
import com.example.tp1.chat.ChatConnection
import com.example.tp1.chat.ChatPhase
import com.example.tp1.chat.ChatPreparation
import com.example.tp1.chat.ChatViewModel
import com.example.tp1.extraction.ExtractionWork
import com.example.tp1.extraction.ExtractedDocument
import com.example.tp1.extraction.MarkdownChunk
import com.example.tp1.inference.ChatMessage
import com.example.tp1.inference.ChatRole
import com.example.tp1.inference.EmbeddingSession
import com.example.tp1.inference.FinishReason
import com.example.tp1.inference.GenerationEvent
import com.example.tp1.inference.ManualInference
import com.example.tp1.inference.Model
import com.example.tp1.inference.ModelManager
import com.example.tp1.inference.NativeWorkHandoff
import com.example.tp1.sources.SelectedSource
import com.example.tp1.sources.SourceKind
import com.example.tp1.sources.SourceStore
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Real SQLite/provider tests. Fake vectors below make no real-model numerical claim. */
class DocumentRetrievalTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as Application
    private val directory = File(application.noBackupFilesDir, "retrieval-test-${UUID.randomUUID()}")
    private val context = object : ContextWrapper(application) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory
    }
    private val resolver = context.contentResolver
    private val sources = SourceStore(context)
    private val provider = Uri.parse("content://com.example.tp1.test.documents")
    private val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
    private val selected = mutableSetOf<String>()
    private val owners = ViewModelStore()
    private val hash = ModelManager(context).profile(Model.JinaV5NanoRetrieval).artifact.sha256
    private var opens = 0
    private var closes = 0
    private val queries = mutableListOf<String>()

    private fun fixture(method: String, arg: String? = null, extras: Bundle? = null): Bundle {
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
        return try { checkNotNull(resolver.call(provider, method, arg, extras)) }
        finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
    }

    private fun select(id: String = "r-${UUID.randomUUID()}", text: String = "Fixture text"): SelectedSource {
        val uri = fixture("fixture-retrieval", id, Bundle().apply {
            putByteArray("bytes", "{\\rtf1\\ansi $text}".toByteArray()); putString("name", "$id.rtf")
        }).getString("uri")!!
        selected += uri
        return sources.add(sources.restore().sources, listOf(uri), SourceKind.Document, flags).sources.single { it.record.uri == uri }
    }

    private fun id(source: SelectedSource) = DocumentsContract.getDocumentId(Uri.parse(source.record.uri))
    private fun control(source: SelectedSource, state: String = "ready", missingAfter: Int = Int.MAX_VALUE, pauseAt: Int = 0) =
        fixture("fixture-retrieval-control", id(source), Bundle().apply {
            putString("state", state); putInt("missingAfter", missingAfter); putInt("pauseAt", pauseAt)
        })

    @After fun cleanup() = runBlocking<Unit> {
        fixture("fixture-retrieval-release")
        withContext(Dispatchers.Main) { owners.clear() }
        for (uri in selected) {
            if (!DocumentsContract.isTreeUri(Uri.parse(uri))) {
                fixture("fixture-retrieval-control", DocumentsContract.getDocumentId(Uri.parse(uri)), Bundle())
            }
            sources.remove(sources.restore().sources, uri)
        }
        assertTrue(!directory.exists() || directory.deleteRecursively())
    }

    private fun vector(first: Float = 1f, second: Float = 0f) = FloatArray(512).apply { this[0] = first; this[1] = second }
    private fun chunk(text: String) = MarkdownChunk(text, 0, text.toByteArray().size, 0, text.toByteArray().size, text.length, 1)

    private fun seed(source: SelectedSource, chunks: List<Pair<String, FloatArray>>, model: String = hash,
                     entry: BrowserEntry = DocumentBrowser(resolver).root(source)): IndexStore.Candidate = IndexStore(context).use { store ->
        val candidate = store.stage(store.request(source, entry))
        for ((ordinal, value) in chunks.withIndex()) store.append(candidate, ordinal, chunk(value.first), value.second, 32)
        store.publish(candidate, IndexIdentity(entry.metadata.name, "a".repeat(64), model, 32), chunks.size, kotlin.coroutines.EmptyCoroutineContext)
        store.discard(candidate)
        candidate
    }

    private fun retriever(beforeEmbed: suspend () -> Unit = {}, close: suspend () -> Unit = {}, value: FloatArray = vector()) =
        DocumentRetriever(context, openEmbeddings = {
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            assertFalse(Thread.holdsLock(ExtractionWork.sourceLock))
            opens++
            RetrievalEmbeddings({ query -> queries += query; beforeEmbed(); value }, {
                assertFalse(Thread.holdsLock(ExtractionWork.sourceLock)); closes++; close()
            })
        })

    private suspend fun matches(retriever: DocumentRetriever = retriever(), k: Int = 5) =
        (retriever.retrieve("  warranty question?  ", k) as RetrievalResult.Complete).matches

    private suspend inline fun <reified T : Throwable> failure(block: suspend () -> Any?): T {
        try { block() } catch (error: Throwable) { if (error is T) return error else throw error }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    private fun sql(statement: String, arguments: Array<out Any> = emptyArray()) {
        SQLiteDatabase.openDatabase(IndexStore.databaseFile(context).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL(statement, arguments)
        }
    }

    private suspend fun awaitPaused(source: SelectedSource) = withTimeout(5_000) {
        while (!fixture("fixture-retrieval-info", id(source)).getBoolean("paused")) delay(5)
    }

    @Test fun retrievesExactTopKTextAndReferencesAfterReopenWithoutOpeningContents() = runBlocking<Unit> {
        val a = select()
        val b = select()
        val first = seed(a, listOf("orthogonal" to vector(0f, 1f), "negative" to vector(-1f), "tied first" to vector()))
        val second = seed(b, listOf("tied second" to vector()))
        val result = matches(k = 50)
        assertEquals(listOf("tied first", "tied second", "orthogonal", "negative"), result.map { it.text })
        assertEquals(listOf(1.0, 1.0, 0.0, -1.0), result.map { it.score })
        assertEquals(listOf(first.documentId, second.documentId, first.documentId, first.documentId), result.map { it.documentId })
        assertEquals(listOf(first.generation, second.generation, first.generation, first.generation), result.map { it.generation })
        assertEquals(listOf(2, 0, 0, 1), result.map { it.ordinal })
        assertEquals(a.record.uri, result.first().sourceUri)
        assertEquals(a.record.uri, result.first().documentUri)
        assertEquals(a.metadata!!.name, result.first().displayName)
        assertEquals(result.take(1), matches(k = 1))
        assertEquals(listOf("  warranty question?  ", "  warranty question?  "), queries)
        assertEquals(2, opens); assertEquals(opens, closes)
        for (source in listOf(a, b)) assertEquals(0, fixture("fixture-retrieval-info", id(source)).getInt("opens"))
    }

    @Test fun validatesInputBeforeOpeningAndDoesNotAcceptInvalidQueryVectors() = runBlocking<Unit> {
        val retrieval = retriever()
        for (query in listOf("", " \n", "bad\u0000text", "bad\uD800", "x".repeat(65536))) {
            failure<IllegalArgumentException> { retrieval.retrieve(query) }
        }
        for (k in listOf(0, -1, 51, Int.MAX_VALUE)) failure<IllegalArgumentException> { retrieval.retrieve("query", k) }
        assertEquals(0, opens)
        failure<IOException> { retriever(value = vector(Float.NaN)).retrieve("query") }
        assertEquals(opens, closes)
        assertTrue(matches().isEmpty())
    }

    @Test fun missingLocalWeightsFailEvenWhenThereAreNoIndexedDocuments() = runBlocking<Unit> {
        failure<IOException> { DocumentRetriever(context).retrieve("query") }
        assertTrue(matches().isEmpty())
    }

    @Test fun zeroChunkWrongModelInvalidatedAndStagedRowsAreNotCandidates() = runBlocking<Unit> {
        val empty = select()
        val wrong = select()
        val invalidated = select()
        seed(empty, emptyList())
        seed(wrong, listOf("wrong model" to vector()), "b".repeat(64))
        val old = seed(invalidated, listOf("invalidated" to vector()))
        sql("UPDATE documents SET invalidated=1 WHERE id=?", arrayOf(old.documentId))
        IndexStore(context).use { store ->
            val pending = store.stage(store.request(empty, DocumentBrowser(resolver).root(empty)))
            store.append(pending, 0, chunk("staged"), vector(), 32)
            assertTrue(matches().isEmpty())
            // Retrieval must neither supersede this request nor clean up a live staged build.
            store.publish(pending, IndexIdentity("published", "a".repeat(64), hash, 32), 1, currentCoroutineContext())
            store.discard(pending)
            assertEquals("staged", matches().single().text)
        }
    }

    @Test fun corruptNonWinningVectorsIncompleteBuildsAndOrdinalGapsFailExplicitly() = runBlocking<Unit> {
        val source = select()
        for (corruption in listOf("vector", "count", "ordinal", "text")) {
            val build = seed(source, listOf("best" to vector(), "other" to vector(-1f)))
            when (corruption) {
                "vector" -> sql("UPDATE chunks SET vector=? WHERE document_id=? AND ordinal=1",
                    arrayOf<Any>(ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN).array(), build.documentId))
                "count" -> sql("UPDATE documents SET chunk_count=3 WHERE id=?", arrayOf(build.documentId))
                "ordinal" -> sql("UPDATE chunks SET ordinal=3 WHERE document_id=? AND ordinal=1", arrayOf(build.documentId))
                else -> sql("UPDATE chunks SET text=' ' WHERE document_id=? AND ordinal=1", arrayOf(build.documentId))
            }
            failure<IOException> { matches(k = 1) }
        }
    }

    @Test fun forbiddenHighScoresCannotCrowdAllowedChunksOutOfTopK() = runBlocking<Unit> {
        val allowed = select()
        val forbidden = select()
        seed(allowed, listOf("allowed" to vector(0f, 1f)))
        seed(forbidden, listOf("forbidden" to vector()))
        fixture("fixture-retrieval-revoke", id(forbidden))
        assertEquals("allowed", matches(k = 1).single().text)
        assertTrue(sources.readForRetrieval().any { it.record.uri == forbidden.record.uri && !it.available })
    }

    @Test fun retainedGrantWithoutSavedMembershipDoesNotAuthorizeCachedChunks() = runBlocking<Unit> {
        val source = select()
        seed(source, listOf("not independently authorized" to vector()))
        val file = File(context.filesDir, "sources.json")
        val original = file.readBytes()
        try {
            val saved = JSONArray(String(original, Charsets.UTF_8))
            val next = JSONArray()
            for (i in 0 until saved.length()) {
                if (saved.optJSONObject(i)?.optString("uri") != source.record.uri) next.put(saved.get(i))
            }
            file.writeText(next.toString())
            assertTrue(resolver.persistedUriPermissions.any { it.uri.toString() == source.record.uri && it.isReadPermission })
            assertTrue(matches().isEmpty())
        } finally { file.writeBytes(original) }
    }

    @Test fun missingDocumentsDirectoriesAndUnanchoredUrisAreExcludedBeforeRanking() = runBlocking<Unit> {
        val allowed = select()
        val denied = select()
        seed(allowed, listOf("allowed" to vector(0f, 1f)))
        val build = seed(denied, listOf("denied" to vector()))
        control(denied, "missing")
        assertEquals("allowed", matches(k = 1).single().text)
        control(denied, "directory")
        assertEquals("allowed", matches(k = 1).single().text)
        control(denied)
        sql("UPDATE documents SET document_uri=? WHERE id=?", arrayOf<Any>(allowed.record.uri, build.documentId))
        assertEquals("allowed", matches(k = 1).single().text)
    }

    @Test fun unexpectedProviderFailuresAndNullOrErrorCursorsAreNotEmptySuccess() = runBlocking<Unit> {
        val source = select()
        seed(source, listOf("saved" to vector()))
        for (state in listOf("failure", "null", "error")) {
            control(source, state)
            failure<Exception> { matches() }
            assertFalse(sources.restore().sources.single { it.record.uri == source.record.uri }.available)
        }
        control(source)
        assertEquals("saved", matches().single().text)
    }

    @Test fun unknownSourceRecordsSurviveAndWholeFileCorruptionPropagates() = runBlocking<Unit> {
        val source = select()
        seed(source, listOf("known" to vector()))
        val file = File(context.filesDir, "sources.json")
        val original = file.readBytes()
        try {
            val text = JSONArray(String(original, Charsets.UTF_8)).put(JSONObject().put("future", "retrieval-fixture")).toString()
            file.writeText(text)
            assertEquals("known", matches().single().text)
            assertEquals(text, file.readText())
            file.writeText("broken JSON")
            failure<org.json.JSONException> { matches() }
            assertEquals("broken JSON", file.readText())
        } finally { file.writeBytes(original) }
    }

    @Test fun independentOverlappingSourcesRemainIndependent() = runBlocking<Unit> {
        val document = select()
        val treeUri = fixture("fixture-authorize").getString("uri")!!
        selected += treeUri
        val tree = sources.add(sources.restore().sources, listOf(treeUri), SourceKind.Tree, flags).sources.single { it.record.uri == treeUri }
        val child = DocumentBrowser(resolver).root(document).copy(sourceUri = treeUri,
            uri = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(treeUri), id(document)).toString())
        seed(document, listOf("same" to vector()))
        seed(tree, listOf("same" to vector()), entry = child)
        val result = matches()
        assertEquals(2, result.size)
        assertEquals(2, result.map { it.documentId }.distinct().size)
        sources.remove(sources.restore().sources, document.record.uri)
        assertEquals(treeUri, matches().single().sourceUri)
    }

    @Test fun removalReaddDuringNativeCloseCannotResurrectCachedText() = runBlocking<Unit> {
        val source = select()
        seed(source, listOf("old" to vector()))
        val retrieval = retriever(close = {
            sources.remove(sources.restore().sources, source.record.uri)
            select(id(source))
        })
        assertTrue(matches(retrieval).isEmpty())
        assertTrue(matches().isEmpty())
    }

    @Test fun failedDeletionAfterRemovalFailsClosedAfterReadd() = runBlocking<Unit> {
        val source = select()
        seed(source, listOf("old" to vector()))
        sql("CREATE TRIGGER prevent_delete BEFORE DELETE ON documents BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        try {
            sources.remove(sources.restore().sources, source.record.uri)
            failure<SQLiteException> { matches() }
        } finally { sql("DROP TRIGGER prevent_delete") }
        select(id(source))
        assertTrue(matches().isEmpty())
    }

    @Test fun scanSerializesReplacementAndDoesNotSupersedeItsTicket() = runBlocking<Unit> {
        val source = select()
        val entry = DocumentBrowser(resolver).root(source)
        val old = seed(source, listOf("old one" to vector(), "old two" to vector()))
        IndexStore(context).use { store ->
            val next = store.stage(store.request(source, entry))
            store.append(next, 0, chunk("new one"), vector(), 32)
            store.append(next, 1, chunk("new two"), vector(), 32)
            control(source, pauseAt = 2)
            val searching = async(Dispatchers.IO) { matches() }
            var publishing: kotlinx.coroutines.Deferred<Unit>? = null
            try {
                awaitPaused(source)
                val entered = CompletableDeferred<Unit>()
                publishing = async(Dispatchers.IO) {
                    entered.complete(Unit)
                    store.publish(next, IndexIdentity("new", "a".repeat(64), hash, 32), 2, currentCoroutineContext())
                }
                withTimeout(5_000) { entered.await() }
                delay(50)
                assertFalse(publishing.isCompleted)
                fixture("fixture-retrieval-release")
                val result = searching.await()
                assertEquals(listOf("old one", "old two"), result.map { it.text })
                assertTrue(result.all { it.generation == old.generation })
                publishing.await()
                store.discard(next)
                assertEquals(listOf("new one", "new two"), matches().map { it.text })
            } finally {
                fixture("fixture-retrieval-release")
                searching.cancelAndJoin(); publishing?.cancelAndJoin()
                store.discard(next)
            }
        }
    }

    @Test fun accessChangingAfterScoringAbortsInsteadOfFilteringTopK() = runBlocking<Unit> {
        val source = select()
        seed(source, listOf("cached" to vector()))
        control(source, missingAfter = 2)
        failure<IOException> { matches(k = 1) }
        control(source, pauseAt = 3)
        val pending = async(Dispatchers.IO) { runCatching { matches() } }
        try {
            awaitPaused(source)
            fixture("fixture-retrieval-revoke", id(source))
            fixture("fixture-retrieval-release")
            assertTrue(pending.await().exceptionOrNull() is IOException)
        } finally { fixture("fixture-retrieval-release"); pending.cancelAndJoin() }
    }

    @Test fun cancellationDuringScanReturnsNoPartialSuccessAndKeepsIndex() = runBlocking<Unit> {
        val source = select()
        val old = seed(source, listOf("unchanged" to vector()))
        control(source, pauseAt = 2)
        val pending = async(Dispatchers.IO) { matches() }
        try {
            awaitPaused(source)
            pending.cancel()
            fixture("fixture-retrieval-release")
            failure<CancellationException> { pending.await() }
        } finally { fixture("fixture-retrieval-release"); pending.cancelAndJoin() }
        assertEquals(old.generation, matches().single().generation)
    }

    @Test fun slowNativeCloseBlocksHandoffUntilCancellationCleanupCompletes() = runBlocking<Unit> {
        val closing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pending = async(Dispatchers.IO) { matches(retriever(close = { closing.complete(Unit); release.await() })) }
        try {
            withTimeout(5_000) { closing.await() }
            pending.cancel()
            assertFalse(pending.isCompleted)
            withContext(Dispatchers.Main) { assertTrue(NativeWorkHandoff.blocked) }
            val acquired = NativeWorkHandoff.documents.tryLock()
            if (acquired) NativeWorkHandoff.documents.unlock()
            assertFalse(acquired)
        } finally { release.complete(Unit); pending.cancelAndJoin() }
        withContext(Dispatchers.Main) { assertFalse(NativeWorkHandoff.blocked) }
        assertTrue(matches().isEmpty())
    }

    @Test fun cancelledEmbeddingClosesAndSubsequentIndexingAndRetrievalCanRun() = runBlocking<Unit> {
        val source = select()
        val old = seed(source, listOf("old" to vector()))
        val entered = CompletableDeferred<Unit>()
        val pending = async(Dispatchers.IO) { matches(retriever(beforeEmbed = { entered.complete(Unit); awaitCancellation() })) }
        try { withTimeout(5_000) { entered.await() } }
        finally { pending.cancelAndJoin() }
        assertEquals(1, closes)
        assertEquals(old.generation, matches().single().generation)
        val indexer = DocumentIndexer(context,
            extract = { _, _ -> ExtractedDocument("next", "fixture", "a".repeat(64)) },
            openEmbeddings = { IndexEmbeddings({ listOf(chunk("next")) }, { vector() }, {}) })
        assertTrue(indexer.index(source, DocumentBrowser(resolver).root(source), refresh = true) is IndexResult.Complete)
        assertEquals("next", matches().single().text)
    }

    @Test fun loadEmbedAndCloseFailuresReleaseOwnershipAndPreservePrimaryFailure() = runBlocking<Unit> {
        failure<IOException> { DocumentRetriever(context, openEmbeddings = { throw IOException("load failed") }).retrieve("query") }
        val primary = IOException("embed failed")
        val cleanup = IOException("close failed")
        val actual = failure<IOException> { matches(retriever(beforeEmbed = { throw primary }, close = { throw cleanup })) }
        assertSame(primary, actual)
        assertEquals(listOf(cleanup), actual.suppressed.toList())
        failure<IOException> { matches(retriever(close = { throw IOException("close only") })) }
        assertTrue(matches().isEmpty())
        withContext(Dispatchers.Main) { assertFalse(NativeWorkHandoff.blocked) }
    }

    private suspend fun chat(): ChatViewModel = withContext(Dispatchers.Main) {
        ChatViewModel(application, SavedStateHandle(), {}, {
            ChatConnection({ text, _ -> listOf(ChatMessage(ChatRole.User, text)) }, { flow {
                emit(GenerationEvent.Text("Partial answer")); awaitCancellation()
            } }, {}, { CompletableDeferred(Unit) })
        }).also { owners.put("chat", it); it.setForeground(true); it.setSelected(true) }
    }

    @Test fun activeChatIsNotInterruptedAndStoppedTranscriptAndDraftSurviveRetrieval() = runBlocking<Unit> {
        val chat = chat()
        withTimeout(5_000) { while (chat.state.value.preparation != ChatPreparation.Ready) delay(5) }
        withContext(Dispatchers.Main) { chat.editDraft("Question"); chat.send() }
        withTimeout(5_000) { while (chat.state.value.turns.firstOrNull()?.answer != "Partial answer") delay(5) }
        val before = chat.state.value
        assertEquals(RetrievalResult.Busy, retriever().retrieve("query"))
        assertEquals(before, chat.state.value)
        assertEquals(0, opens)
        withContext(Dispatchers.Main) { chat.stop() }
        withTimeout(5_000) { while (chat.state.value.phase != ChatPhase.Idle) delay(5) }
        withContext(Dispatchers.Main) { chat.editDraft("Next draft") }
        val stopped = chat.state.value.turns
        assertEquals(FinishReason.CANCELLED, stopped.single().finish)
        assertTrue(matches().isEmpty())
        withTimeout(5_000) { while (chat.state.value.preparation != ChatPreparation.Ready) delay(5) }
        assertEquals(stopped, chat.state.value.turns)
        assertEquals("Next draft", chat.state.value.draft)
    }

    @Test @ManualInference fun productionIndexReopenAndPinnedQueryRankRelevantDocumentWithIndependentScores() = runBlocking<Unit> {
        val relevant = select(text = "The laptop warranty lasts two years. Keep the receipt for warranty repairs.")
        val unrelated = select(text = "Tomatoes grow best in sunny gardens. Water the plants regularly during summer.")
        val models = ModelManager(application)
        for (source in listOf(relevant, unrelated)) {
            assertTrue(DocumentIndexer(context, models).index(source, DocumentBrowser(resolver).root(source)) is IndexResult.Complete)
        }
        val stored = IndexStore(context).use { store -> listOf(relevant, unrelated).flatMap { source ->
            val snapshot = checkNotNull(store.read(source, DocumentBrowser(resolver).root(source)))
            snapshot.chunks.map { Triple(snapshot.documentId, it.ordinal, it.vector) }
        } }
        val query = "How long is the laptop warranty?"
        val result = (DocumentRetriever(context, models).retrieve(query, 10) as RetrievalResult.Complete).matches
        assertEquals(stored.size, result.size)
        assertEquals(relevant.record.uri, result.first().sourceUri)
        assertTrue(result.first().score > result.first { it.sourceUri == unrelated.record.uri }.score)
        val session = EmbeddingSession.open(models)
        try {
            val vector = session.embedQuery(query)
            for (match in result) {
                val document = stored.single { it.first == match.documentId && it.second == match.ordinal }.third
                val expected = vector.indices.sumOf { vector[it].toDouble() * document[it] }
                assertEquals(expected, match.score, 1e-6)
            }
        } finally { session.close() }
        failure<IllegalArgumentException> { DocumentRetriever(context, models).retrieve("a" + " a".repeat(2000)) }
        assertTrue(ExtractionWork.permit.tryLock())
        ExtractionWork.permit.unlock()
    }
}
