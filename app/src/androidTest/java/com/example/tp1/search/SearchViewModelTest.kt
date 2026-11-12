package com.example.tp1.search

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tp1.browser.BrowserProblem
import com.example.tp1.indexing.IndexingState
import com.example.tp1.indexing.RetrievalMatch
import com.example.tp1.indexing.RetrievalResult
import com.example.tp1.indexing.SourceIndexingState
import com.example.tp1.sources.SelectedSource
import com.example.tp1.sources.SourceKind
import com.example.tp1.sources.SourceMetadata
import com.example.tp1.sources.SourceRecord
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
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
class SearchViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val store = ViewModelStore()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private val queries = mutableListOf<String>()
    private var behavior: suspend (String) -> RetrievalResult = { RetrievalResult.Complete(listOf(match())) }
    private var validate: suspend (List<RetrievalMatch>) -> Unit = {}
    private var open: suspend () -> Intent = { Intent(Intent.ACTION_VIEW) }
    private lateinit var search: SearchViewModel
    private val application get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    @Before fun main() { Dispatchers.setMain(dispatcher) }
    @After fun clear() {
        try {
            gates.forEach { it.complete(Unit) }
            store.clear()
            scheduler.runCurrent()
        } finally { Dispatchers.resetMain() }
    }

    private fun gate() = CompletableDeferred<Unit>().also { gates += it }
    private fun start(saved: SavedStateHandle = SavedStateHandle()) {
        search = SearchViewModel(application, saved, retrieve = { queries += it; behavior(it) },
            checkContext = { validate(it) }, intentForDocument = { open() })
        store.put("search", search)
        search.setSources(emptyList(), ready = true)
        search.setForeground(true)
        search.setSelected(true)
    }

    private fun submit(text: String = "question") { search.editDraft(text); search.submit() }

    @Test fun typingDoesNotRetrieveAndSubmissionCapturesInputOnce() = runTest(dispatcher) {
        val finish = gate()
        behavior = { finish.await(); RetrievalResult.Complete(listOf(match())) }
        start()
        search.editDraft("  captured  ")
        runCurrent()
        assertTrue(queries.isEmpty())
        search.submit(); runCurrent()
        search.editDraft("newer draft")
        assertEquals("captured", search.state.value.submitted)
        assertEquals(SearchPhase.Searching, search.state.value.phase)
        finish.complete(Unit); runCurrent()
        assertEquals(listOf("captured"), queries)
        assertEquals("newer draft", search.state.value.draft)
        assertEquals("evidence", search.state.value.rows.single().excerpt)
    }

    @Test fun groupingPreservesBestPassageOrderOpaqueIdentityAndOverlappingAlternatives() {
        val first = match().copy(documentUri = "content://fixture/document/opaque%2Fid%3Aone")
        val alternative = first.copy(documentId = 9, text = "lower", sourceUri = "content://fixture/tree/root",
            documentUri = "content://fixture/tree/root/document/opaque%2Fid%3Aone", score = 0.5)
        val sameName = match("b").copy(displayName = first.displayName)
        val otherProvider = first.copy(documentUri = "content://other/document/opaque%2Fid%3Aone")
        val rows = searchRows(listOf(first, first.copy(ordinal = 1, text = "second"), sameName, alternative, otherProvider))
        assertEquals(3, rows.size)
        assertEquals(listOf("opaque/id:one", "b", "opaque/id:one"), rows.map { it.document.providerId })
        assertEquals("evidence", rows.first().excerpt)
        assertEquals(2, rows.first().document.alternatives.size)
        assertEquals(listOf("fixture", "fixture", "other"), rows.map { it.document.authority })
    }

    @Test fun rapidReplacementsWaitForCancelledNativeCleanupAndOnlyLatestStarts() = runTest(dispatcher) {
        val cleanup = gate()
        behavior = { query ->
            if (query == "first") try { awaitCancellation() }
            finally { withContext(NonCancellable) { cleanup.await() } }
            RetrievalResult.Complete(listOf(match(text = query)))
        }
        start(); submit("first"); runCurrent()
        submit("second"); runCurrent()
        submit("third"); runCurrent()
        assertEquals(listOf("first"), queries)
        cleanup.complete(Unit); runCurrent()
        assertEquals(listOf("first", "third"), queries)
        assertEquals("third", search.state.value.rows.single().excerpt)
    }

    @Test fun clearAndBackSuppressLateSuccessAndErrorsWithoutLosingNewDraft() = runTest(dispatcher) {
        for (error in listOf(false, true)) {
            val late = gate()
            behavior = { withContext(NonCancellable) {
                late.await()
                if (error) throw IOException("late")
                RetrievalResult.Complete(listOf(match()))
            } }
            start(); submit(); runCurrent()
            search.editDraft("keep")
            search.exitResults()
            assertEquals("keep", search.state.value.draft)
            late.complete(Unit); runCurrent()
            assertFalse(search.state.value.showingResults)
            assertTrue(search.state.value.rows.isEmpty())
            assertNull(search.state.value.problem)
            search.clear()
            assertEquals("", search.state.value.draft)
        }
    }

    @Test fun leavingExplorerOrBackgroundCancelsAndReturnNeverResubmits() = runTest(dispatcher) {
        behavior = { awaitCancellation() }
        start(); submit(); runCurrent()
        search.setSelected(false); runCurrent()
        assertEquals(SearchProblem.Interrupted, search.state.value.problem)
        search.setSelected(true); runCurrent()
        assertEquals(1, queries.size)
        search.retry(); runCurrent()
        search.setForeground(false); runCurrent()
        search.setForeground(true); runCurrent()
        assertEquals(2, queries.size)
        assertEquals(SearchProblem.Interrupted, search.state.value.problem)
    }

    @Test fun retainedRowsAreHiddenUntilAllOriginalReferencesAreRevalidated() = runTest(dispatcher) {
        val found = listOf(match(), match().copy(ordinal = 1), match("b"))
        behavior = { RetrievalResult.Complete(found) }
        start(); submit(); runCurrent()
        val rows = search.state.value.rows
        val checked = gate()
        validate = { assertEquals(found, it); checked.await() }
        search.setSelected(false)
        search.setSelected(true); runCurrent()
        assertEquals(SearchPhase.Checking, search.state.value.phase)
        checked.complete(Unit); runCurrent()
        assertEquals(rows, search.state.value.rows)
        assertEquals(SearchPhase.Idle, search.state.value.phase)
        assertEquals(1, queries.size)
        validate = { throw SecurityException("generation replaced") }
        search.setForeground(false); search.setForeground(true); runCurrent()
        assertEquals(SearchProblem.Changed, search.state.value.problem)
        assertTrue(search.state.value.rows.isEmpty())
        assertEquals(1, queries.size)
    }

    @Test fun onlyBoundedDraftIsSavedAndProcessRecreationDoesNotRestorePassages() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        start(saved); submit(); runCurrent()
        assertEquals(setOf("searchDraft"), saved.keys())
        search.editDraft("new draft")
        start(saved)
        assertEquals("new draft", search.state.value.draft)
        assertFalse(search.state.value.showingResults)
        assertTrue(search.state.value.rows.isEmpty())
        search.editDraft("x".repeat(65537))
        assertEquals("new draft", saved.get<String>("searchDraft"))
        assertTrue(search.state.value.inputInvalid)
        search.editDraft("new draft")
        assertTrue(search.state.value.inputInvalid)
        search.editDraft("corrected")
        assertFalse(search.state.value.inputInvalid)
        start(SavedStateHandle(mapOf("searchDraft" to "x".repeat(65537))))
        assertTrue(search.state.value.inputInvalid)
        assertEquals("", search.state.value.draft)
    }

    @Test fun invalidUnicodeBytesAndNulNeverReachRetrieverAndAreNotTruncated() = runTest(dispatcher) {
        start()
        for (text in listOf("bad\u0000input", "bad\uD800", "x".repeat(65536), "😀".repeat(20000))) {
            submit(text); runCurrent()
            assertTrue(search.state.value.inputInvalid)
            assertEquals(text, search.state.value.draft)
        }
        assertTrue(queries.isEmpty())
    }

    @Test fun busyLimitsAndFailuresAreNotEmptySuccessAndRetryUsesSubmittedQuery() = runTest(dispatcher) {
        start()
        val cases = listOf(SearchProblem.Busy, SearchProblem.QueryLimit, SearchProblem.Retrieval, SearchProblem.Changed)
        for (problem in cases) {
            behavior = { when (problem) {
                SearchProblem.Busy -> RetrievalResult.Busy
                SearchProblem.QueryLimit -> throw IllegalArgumentException("tokens")
                SearchProblem.Changed -> throw SecurityException("access")
                else -> throw IOException("model/provider/storage")
            } }
            submit("captured"); runCurrent()
            assertEquals(problem, search.state.value.problem)
            assertTrue(search.state.value.rows.isEmpty())
        }
        search.editDraft("newer")
        behavior = { RetrievalResult.Complete(emptyList()) }
        search.retry(); runCurrent()
        assertEquals("captured", queries.last())
        assertEquals("newer", search.state.value.draft)
        assertNull(search.state.value.problem)
        assertTrue(search.state.value.showingResults)
        assertTrue(search.state.value.rows.isEmpty())
    }

    @Test fun membershipAvailabilityAndIndexCompletionInvalidateWithoutRetrieval() = runTest(dispatcher) {
        start()
        val source = SelectedSource(SourceRecord("content://fixture/document/a", SourceKind.Document),
            SourceMetadata("file", null, null, null))
        search.setSources(listOf(source), ready = true)
        search.setIndexing(IndexingState(mapOf(source.record.uri to SourceIndexingState(completed = 1))))
        submit(); runCurrent()
        search.setSources(listOf(source.copy(metadata = source.metadata!!.copy(name = "renamed"))), ready = true)
        assertNull(search.state.value.problem)
        search.setIndexing(IndexingState(mapOf(source.record.uri to SourceIndexingState(completed = 1, pending = 2))))
        assertNull(search.state.value.problem)
        search.setIndexing(IndexingState(mapOf(source.record.uri to SourceIndexingState(completed = 2))))
        assertEquals(SearchProblem.Changed, search.state.value.problem)
        search.retry(); runCurrent()
        search.setSources(listOf(source.copy(metadata = null)), ready = true)
        assertEquals(SearchProblem.Changed, search.state.value.problem)
        assertTrue(search.state.value.rows.isEmpty())
        assertEquals(2, queries.size)
    }

    @Test fun openingReportsViewerAndAccessFailuresAndCannotLaunchAfterClear() = runTest(dispatcher) {
        start(); submit(); runCurrent()
        var document = search.state.value.rows.single().document
        search.openDocument(document) { throw ActivityNotFoundException() }; runCurrent()
        assertEquals(BrowserProblem.NoViewer, search.state.value.openProblem)
        open = { throw SecurityException("revoked") }
        search.openDocument(document) { fail("Revoked file launched") }; runCurrent()
        assertEquals(BrowserProblem.Access, search.state.value.openProblem)
        assertTrue(search.state.value.rows.isEmpty())
        search.retry(); runCurrent()
        document = search.state.value.rows.single().document
        val late = gate()
        open = { withContext(NonCancellable) { late.await(); Intent(Intent.ACTION_VIEW) } }
        search.openDocument(document) { fail("Late viewer launched") }; runCurrent()
        search.clear()
        late.complete(Unit); runCurrent()
        assertFalse(search.state.value.opening)
        assertNull(search.state.value.openProblem)
    }

    @Test fun ownerDestructionSuppressesLateCompletionAndWaitsForCleanup() = runTest(dispatcher) {
        val cleanup = gate()
        var closed = false
        behavior = { try { awaitCancellation() }
            finally { withContext(NonCancellable) { cleanup.await(); closed = true } } }
        start(); submit(); runCurrent()
        store.clear(); runCurrent()
        assertFalse(closed)
        cleanup.complete(Unit); runCurrent()
        assertTrue(closed)
        assertTrue(search.state.value.rows.isEmpty())
    }

    companion object {
        internal fun match(id: String = "a", text: String = "evidence") = RetrievalMatch(text, 1.0, id.hashCode().toLong(),
            "generation", 0, "same.txt", "content://fixture/document/$id", "content://fixture/document/$id")
    }
}
