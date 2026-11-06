package com.example.tp1.indexing

import android.database.sqlite.SQLiteException
import com.example.tp1.browser.BrowserEntry
import com.example.tp1.browser.BrowserFolder
import com.example.tp1.browser.DirectoryListing
import com.example.tp1.extraction.ExtractionFailure
import com.example.tp1.sources.SelectedSource
import com.example.tp1.sources.SourceKind
import com.example.tp1.sources.SourceMetadata
import com.example.tp1.sources.SourceRecord
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IndexingCoordinatorTest {
    private fun source(id: String = "root", tree: Boolean = false) = SelectedSource(
        SourceRecord("content://fixture/${if (tree) "tree" else "document"}/$id",
            if (tree) SourceKind.Tree else SourceKind.Document, id),
        SourceMetadata(id, if (tree) "vnd.android.document/directory" else "application/rtf", null, null))

    private fun entry(source: SelectedSource, id: String, directory: Boolean = false) = BrowserEntry(
        source.record.uri, "${source.record.uri}/document/$id", SourceMetadata(id, null, null, null), directory)

    private fun coordinator(
        scope: CoroutineScope,
        children: suspend (SelectedSource, BrowserFolder) -> DirectoryListing = { _, _ -> DirectoryListing(emptyList(), false) },
        index: suspend (SelectedSource, BrowserEntry, () -> Unit) -> IndexResult = { _, _, check -> check(); complete() },
        checkSource: (SelectedSource) -> Unit = {}
    ) = IndexingCoordinator(scope, { entry(it, it.record.name!!, it.record.kind == SourceKind.Tree) }, children, index, checkSource)

    private fun complete(chunks: Int = 1) = IndexResult.Complete(1, "snapshot", chunks, false)

    @Test fun duplicateAdmissionAndRepeatedRestoreDoNotRestartCompletedFiles() = runTest {
        var calls = 0
        val work = coordinator(backgroundScope, index = { _, _, check -> check(); calls++; complete(0) })
        val source = source()
        work.restore(emptyList())
        work.admit(listOf(source, source))
        runCurrent()
        repeat(4) { work.restore(listOf(source)); work.admit(listOf(source)) }
        runCurrent()
        assertEquals(1, calls)
        assertEquals(1, work.state.value.sources.getValue(source.record.uri).completed)
        assertEquals(0, work.state.value.sources.getValue(source.record.uri).pending)
    }

    @Test fun nestedTreesDeduplicateLeavesAndStopRepeatedFoldersAndCycles() = runTest {
        val selected = source(tree = true)
        val visited = mutableListOf<String>()
        val indexed = mutableListOf<String>()
        val work = coordinator(backgroundScope, children = { source, folder ->
            visited += folder.name
            DirectoryListing(when (folder.name) {
                "root" -> listOf(entry(source, "a"), entry(source, "nested", true), entry(source, "nested", true))
                else -> listOf(entry(source, "a"), entry(source, "b"), entry(source, "root", true))
            }, false)
        }, index = { _, document, check -> check(); indexed += document.metadata.name; complete() })
        work.admit(listOf(selected))
        runCurrent()
        assertEquals(listOf("root", "nested"), visited)
        assertEquals(listOf("a", "b"), indexed)
        assertFalse(work.state.value.sources.getValue(selected.record.uri).incomplete)
    }

    @Test fun emptyTreeCompletesWithoutIndexingADirectory() = runTest {
        val selected = source(tree = true)
        val work = coordinator(backgroundScope, index = { _, _, _ -> error("Directory indexed") })
        work.admit(listOf(selected))
        runCurrent()
        val state = work.state.value.sources.getValue(selected.record.uri)
        assertFalse(state.discovering)
        assertFalse(state.incomplete)
        assertEquals(0, state.completed)
    }

    @Test fun pendingAndFailedSubtreesDoNotHideHealthyLeavesAndCanBeRetried() = runTest {
        val selected = source(tree = true)
        var recovered = false
        val work = coordinator(backgroundScope, children = { source, folder ->
            if (folder.name == "broken" && !recovered) throw IOException("Provider failed")
            DirectoryListing(when (folder.name) {
                "root" -> listOf(entry(source, "pending", true), entry(source, "broken", true), entry(source, "healthy"))
                "pending" -> if (recovered) listOf(entry(source, "late")) else emptyList()
                else -> emptyList()
            }, folder.name == "pending" && !recovered)
        })
        work.admit(listOf(selected))
        runCurrent()
        val incomplete = work.state.value.sources.getValue(selected.record.uri)
        assertTrue(incomplete.incomplete)
        assertEquals(1, incomplete.completed)
        assertEquals(IndexingProblem.Discovery, incomplete.failures.single().problem)
        recovered = true
        work.retry(selected.record.uri)
        runCurrent()
        val complete = work.state.value.sources.getValue(selected.record.uri)
        assertFalse(complete.incomplete)
        assertEquals(2, complete.completed)
    }

    @Test fun overlappingSelectionsRemainIndependentAndOnlyOneIndexCallRuns() = runTest {
        val release = CompletableDeferred<Unit>()
        var active = 0
        var peak = 0
        val indexed = mutableListOf<String>()
        val work = coordinator(backgroundScope, index = { source, _, check ->
            check(); active++; peak = maxOf(active, peak)
            try { release.await(); indexed += source.record.uri; complete() }
            finally { active-- }
        })
        val direct = source("same")
        val second = source("overlap")
        work.admit(listOf(direct, second))
        runCurrent()
        assertEquals(1, active)
        release.complete(Unit)
        runCurrent()
        assertEquals(1, peak)
        assertEquals(listOf(direct.record.uri, second.record.uri), indexed)
    }

    @Test fun busyRetainsTheItemBacksOffAndCancellationStopsRetries() = runTest {
        var calls = 0
        val selected = source()
        val work = coordinator(backgroundScope, index = { _, _, _ -> calls++; IndexResult.Busy })
        work.admit(listOf(selected))
        runCurrent()
        assertEquals(1, calls)
        assertTrue(work.state.value.sources.getValue(selected.record.uri).waitingForChat)
        advanceTimeBy(249); runCurrent(); assertEquals(1, calls)
        advanceTimeBy(1); runCurrent(); assertEquals(2, calls)
        work.cancelSource(selected.record.uri)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(2, calls)
        assertTrue(work.state.value.sources.isEmpty())
    }

    @Test fun unsupportedOcrAndPerFileFailuresDoNotBlockLaterHealthyFiles() = runTest {
        val selected = source(tree = true)
        val work = coordinator(backgroundScope, children = { source, _ ->
            DirectoryListing(listOf("unknown.extension", "scan", "broken", "healthy").map { entry(source, it) }, false)
        }, index = { _, document, _ -> when (document.metadata.name) {
            "unknown.extension" -> throw ExtractionFailure("unsupported", "Unsupported")
            "scan" -> throw ExtractionFailure("needsOcr", "Needs OCR")
            "broken" -> throw IOException("Document failure")
            else -> complete()
        } })
        work.admit(listOf(selected)); runCurrent()
        val state = work.state.value.sources.getValue(selected.record.uri)
        assertEquals(1, state.completed)
        assertEquals(listOf(IndexingProblem.Unsupported, IndexingProblem.NeedsOcr, IndexingProblem.Document), state.failures.map { it.problem })
        assertNull(work.state.value.blocked)
    }

    @Test fun missingModelBlocksTheQueueWithoutRepeatedAttemptsUntilExplicitRetry() = runTest {
        var calls = 0
        var available = false
        val selected = source(tree = true)
        val work = coordinator(backgroundScope, children = { source, _ ->
            DirectoryListing(listOf(entry(source, "a"), entry(source, "b")), false)
        }, index = { _, _, _ -> calls++; if (!available) throw IndexModelFailure(IOException("Missing")); complete() })
        work.admit(listOf(selected)); runCurrent()
        assertEquals(IndexingProblem.Model, work.state.value.blocked)
        advanceTimeBy(60_000); runCurrent(); assertEquals(1, calls)
        work.restore(listOf(selected)); runCurrent(); assertEquals(1, calls)
        available = true
        work.retry(selected.record.uri); runCurrent()
        assertNull(work.state.value.blocked)
        assertEquals(2, work.state.value.sources.getValue(selected.record.uri).completed)
    }

    @Test fun databaseFailureBlocksInsteadOfFailingEveryQueuedFile() = runTest {
        var calls = 0
        val work = coordinator(backgroundScope, index = { _, _, _ -> calls++; throw SQLiteException("Storage failed") })
        work.admit(listOf(source("a"), source("b"))); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, calls)
        assertEquals(IndexingProblem.Storage, work.state.value.blocked)
    }

    @Test fun removalReaddRejectsOldQueuedWorkAndWaitsForRunningCleanup() = runTest {
        val selected = source(tree = true)
        val closing = CompletableDeferred<Unit>()
        val releaseClose = CompletableDeferred<Unit>()
        var oldCheck: (() -> Unit)? = null
        var calls = 0
        val work = coordinator(backgroundScope, children = { source, _ ->
            DirectoryListing(listOf(entry(source, "a"), entry(source, "b")), false)
        }, index = { _, _, check ->
            calls++
            if (calls == 1) {
                oldCheck = check
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { closing.complete(Unit); releaseClose.await() } }
            } else { check(); complete() }
        })
        work.admit(listOf(selected)); runCurrent()
        work.cancelSource(selected.record.uri)
        work.admit(listOf(selected)); runCurrent()
        assertTrue(closing.isCompleted)
        assertEquals(1, calls)
        assertThrows(CancellationException::class.java) { oldCheck!!() }
        releaseClose.complete(Unit); runCurrent()
        assertEquals(3, calls)
        assertEquals(2, work.state.value.sources.getValue(selected.record.uri).completed)
    }

    @Test fun lateDiscoveryFromOldAdmissionCannotEnqueueAfterReadd() = runTest {
        val selected = source(tree = true)
        val release = CompletableDeferred<Unit>()
        var listings = 0
        val indexed = mutableListOf<String>()
        val work = coordinator(backgroundScope, children = { source, _ ->
            val old = ++listings == 1
            if (old) withContext(NonCancellable) { release.await() }
            DirectoryListing(listOf(entry(source, if (old) "stale" else "current")), false)
        }, index = { _, document, check -> check(); indexed += document.metadata.name; complete() })
        work.admit(listOf(selected)); runCurrent()
        work.cancelSource(selected.record.uri); work.admit(listOf(selected)); runCurrent()
        release.complete(Unit); runCurrent()
        assertEquals(listOf("current"), indexed)
    }

    @Test fun lostGrantCancelsAdmissionAndReadableRestoreStartsANewPass() = runTest {
        var readable = true
        var calls = 0
        val selected = source()
        val work = coordinator(backgroundScope, index = { _, _, _ -> calls++; IndexResult.Busy },
            checkSource = { if (!readable) throw SecurityException("Revoked") })
        work.restore(listOf(selected)); runCurrent()
        readable = false
        advanceTimeBy(250); runCurrent()
        assertEquals(1, calls)
        assertEquals(IndexingProblem.Access, work.state.value.sources.getValue(selected.record.uri).failures.single().problem)
        readable = true
        work.restore(listOf(selected)); runCurrent()
        assertEquals(2, calls)
    }

    @Test fun grantLostInsideIndexCallCancelsAdmissionAndReadableRestoreRecovers() = runTest {
        var readable = true
        var calls = 0
        val selected = source()
        val work = coordinator(backgroundScope, index = { _, _, _ ->
            if (++calls == 1) {
                readable = false
                throw SecurityException("Revoked during extraction")
            }
            complete()
        }, checkSource = { if (!readable) throw SecurityException("Revoked") })
        work.restore(listOf(selected)); runCurrent()
        val lost = work.state.value.sources.getValue(selected.record.uri)
        assertEquals(IndexingProblem.Access, lost.failures.single().problem)
        assertEquals(0, lost.pending)
        assertFalse(lost.running)
        assertNull(work.state.value.blocked)
        readable = true
        work.restore(listOf(selected)); runCurrent()
        assertEquals(2, calls)
        assertEquals(1, work.state.value.sources.getValue(selected.record.uri).completed)
    }

    @Test fun grantLostInsideListingCancelsRootAndSubtreeDiscoveryAndCanRecover() = runTest {
        for (nested in listOf(false, true)) {
            var readable = true
            var fail = true
            val selected = source(tree = true)
            val work = coordinator(backgroundScope, children = { source, folder ->
                if (nested && folder.name == "root") {
                    DirectoryListing(listOf(entry(source, "nested", true)), false)
                } else {
                    if (fail) {
                        readable = false
                        throw SecurityException("Revoked during listing")
                    }
                    DirectoryListing(listOf(entry(source, "healthy")), false)
                }
            }, checkSource = { if (!readable) throw SecurityException("Revoked") })
            work.restore(listOf(selected)); runCurrent()
            val lost = work.state.value.sources.getValue(selected.record.uri)
            assertEquals(IndexingProblem.Access, lost.failures.single().problem)
            assertFalse(lost.discovering)
            assertEquals(0, lost.pending)
            readable = true
            fail = false
            work.restore(listOf(selected)); runCurrent()
            assertEquals(1, work.state.value.sources.getValue(selected.record.uri).completed)
        }
    }

    @Test fun sourceCheckStorageFailureOnAnErrorPathBlocksWithoutKillingTheWorker() = runTest {
        var readable = true
        var calls = 0
        val selected = source()
        val work = coordinator(backgroundScope, index = { _, _, _ ->
            if (++calls == 1) {
                readable = false
                throw IOException("Document read failed")
            }
            complete()
        }, checkSource = { if (!readable) throw IOException("Cannot read source records") })
        work.restore(listOf(selected)); runCurrent()
        assertEquals(IndexingProblem.Storage, work.state.value.blocked)
        assertEquals(1, work.state.value.sources.getValue(selected.record.uri).pending)
        assertEquals(1, calls)
        readable = true
        work.restore(listOf(selected))
        work.retry(selected.record.uri); runCurrent()
        assertNull(work.state.value.blocked)
        assertEquals(2, calls)
        assertEquals(1, work.state.value.sources.getValue(selected.record.uri).completed)
    }

    @Test fun failedRestoreDoesNotBecomeEmptySuccessAndRecoveryRunsOnce() = runTest {
        var calls = 0
        val work = coordinator(backgroundScope, index = { _, _, _ -> calls++; complete() })
        val selected = source()
        work.restoreError(); runCurrent()
        assertEquals(IndexingProblem.Storage, work.state.value.blocked)
        assertEquals(0, calls)
        work.restore(listOf(selected)); runCurrent()
        repeat(3) { work.restore(listOf(selected)) }; runCurrent()
        assertNull(work.state.value.blocked)
        assertEquals(1, calls)
    }

    @Test fun pendingFileBufferAppliesBackpressureToDiscovery() = runTest {
        val selected = source(tree = true)
        var calls = 0
        val work = coordinator(backgroundScope, children = { source, _ ->
            DirectoryListing((1..100).map { entry(source, "$it") }, false)
        }, index = { _, _, _ -> calls++; IndexResult.Busy })
        work.admit(listOf(selected)); runCurrent()
        val state = work.state.value.sources.getValue(selected.record.uri)
        assertEquals(1, calls)
        assertTrue(state.discovering)
        assertTrue(state.pending in 32..34)
    }
}
