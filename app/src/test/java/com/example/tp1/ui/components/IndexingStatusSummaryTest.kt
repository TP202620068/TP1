package com.example.tp1.ui.components

import com.example.tp1.indexing.IndexingFailure
import com.example.tp1.indexing.IndexingProblem
import com.example.tp1.indexing.IndexingState
import com.example.tp1.indexing.SourceIndexingState
import org.junit.Assert.*
import org.junit.Test

class IndexingStatusSummaryTest {
    private fun failure(problem: IndexingProblem) = IndexingFailure(problem.name, problem)
    private fun state(source: SourceIndexingState) = IndexingState(mapOf("source" to source))

    @Test fun emptyOrUnselectedSourcesHaveNoStatus() {
        assertNull(IndexingState().statusSummary())
        assertNull(state(SourceIndexingState()).statusSummary("another-source"))
    }

    @Test fun skippedImageDoesNotCountAsAnErrorOrPartOfTheDocumentTotal() {
        val summary = state(SourceIndexingState(discovering = false, pending = 6, running = true,
            failures = listOf(failure(IndexingProblem.Unsupported)))).statusSummary()!!
        assertEquals(0, summary.completed)
        assertEquals(6, summary.total)
        assertEquals(1, summary.omitted)
        assertTrue(summary.active)
        assertFalse(summary.hasError)
        assertTrue(summary.retrySources.isEmpty())
    }

    @Test fun progressAggregatesDocumentsWithoutCountingFailedFolderListings() {
        val summary = IndexingState(mapOf(
            "first" to SourceIndexingState(discovering = false, completed = 2, pending = 3,
                failures = listOf(failure(IndexingProblem.Unsupported), failure(IndexingProblem.NeedsOcr))),
            "second" to SourceIndexingState(discovering = false, completed = 1, pending = 1, incomplete = true,
                failures = listOf(failure(IndexingProblem.Document), failure(IndexingProblem.Discovery)))
        )).statusSummary()!!
        assertEquals(3, summary.completed)
        assertEquals(8, summary.total)
        assertEquals(2, summary.omitted)
        assertEquals(IndexingProblem.Discovery, summary.failure?.problem)
        assertTrue(summary.active)
        assertTrue(summary.hasError)
        assertTrue(summary.retrySources.isEmpty())
    }

    @Test fun completedWorkWithOnlyUnsupportedAndOcrFilesDoesNotOfferRetry() {
        val summary = state(SourceIndexingState(discovering = false, completed = 2,
            failures = listOf(failure(IndexingProblem.Unsupported), failure(IndexingProblem.NeedsOcr)))).statusSummary()!!
        assertEquals(2, summary.completed)
        assertEquals(2, summary.total)
        assertEquals(2, summary.omitted)
        assertFalse(summary.active)
        assertFalse(summary.hasError)
        assertNull(summary.failure)
        assertTrue(summary.retrySources.isEmpty())
    }

    @Test fun everyOtherProblemRemainsAnErrorAndCanBeRetriedWhenIdle() {
        for (problem in IndexingProblem.entries.filterNot { it == IndexingProblem.Unsupported || it == IndexingProblem.NeedsOcr }) {
            val error = failure(problem)
            val summary = state(SourceIndexingState(discovering = false,
                failures = listOf(failure(IndexingProblem.Unsupported), error))).statusSummary()!!
            assertTrue(problem.name, summary.hasError)
            assertEquals(error, summary.failure)
            assertEquals(listOf("source"), summary.retrySources)
        }
    }

    @Test fun retryTargetsOnlySourcesWithGenuineFailuresOrIncompleteDiscovery() {
        val summary = IndexingState(mapOf(
            "omitted" to SourceIndexingState(discovering = false, failures = listOf(failure(IndexingProblem.NeedsOcr))),
            "broken" to SourceIndexingState(discovering = false, failures = listOf(failure(IndexingProblem.Document))),
            "incomplete" to SourceIndexingState(discovering = false, incomplete = true),
            "healthy" to SourceIndexingState(discovering = false, completed = 1)
        )).statusSummary()!!
        assertEquals(listOf("broken", "incomplete"), summary.retrySources)
        assertTrue(summary.incomplete)
        assertTrue(summary.hasError)
    }

    @Test fun scopedStatusDoesNotIncludeOtherSourcesCountsOrFailures() {
        val summary = IndexingState(mapOf(
            "selected" to SourceIndexingState(discovering = false, completed = 2,
                failures = listOf(failure(IndexingProblem.Unsupported))),
            "other" to SourceIndexingState(pending = 10, incomplete = true,
                failures = listOf(failure(IndexingProblem.Access)))
        )).statusSummary("selected")!!
        assertEquals(2, summary.completed)
        assertEquals(2, summary.total)
        assertEquals(1, summary.omitted)
        assertFalse(summary.active)
        assertFalse(summary.hasError)
        assertTrue(summary.retrySources.isEmpty())
    }

    @Test fun discoveryAndQueuedWorkAreActiveEvenBetweenIndexCalls() {
        for (source in listOf(SourceIndexingState(), SourceIndexingState(discovering = false, pending = 1))) {
            val summary = state(source).statusSummary()!!
            assertTrue(summary.active)
            assertTrue(summary.retrySources.isEmpty())
        }
    }

    @Test fun chatWaitIsNotShownWhileAnotherSourceIsRunning() {
        val waiting = SourceIndexingState(discovering = false, pending = 1, waitingForChat = true)
        assertTrue(state(waiting).statusSummary()!!.waitingForChat)
        val summary = IndexingState(mapOf("waiting" to waiting,
            "running" to SourceIndexingState(discovering = false, pending = 1, running = true))).statusSummary()!!
        assertTrue(summary.active)
        assertFalse(summary.waitingForChat)
    }

    @Test fun modelAndStorageBlockersOverrideActiveFlagsAndAllowScopedRetry() {
        for (problem in listOf(IndexingProblem.Model, IndexingProblem.Storage)) {
            val state = IndexingState(mapOf(
                "first" to SourceIndexingState(pending = 1, running = true),
                "second" to SourceIndexingState(pending = 2, waitingForChat = true)
            ), blocked = problem)
            val summary = state.statusSummary()!!
            assertEquals(problem, summary.blocked)
            assertFalse(summary.active)
            assertFalse(summary.waitingForChat)
            assertTrue(summary.hasError)
            assertEquals(listOf("first", "second"), summary.retrySources)
            assertEquals(listOf("second"), state.statusSummary("second")!!.retrySources)
        }
    }
}
