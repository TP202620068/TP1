package com.example.tp1.indexing

import com.example.tp1.extraction.ExtractionFailure
import java.io.IOException
import java.io.InterruptedIOException
import org.junit.Assert.*
import org.junit.Test

class IndexingDiagnosticsTest {
    @Test fun timeoutIncludesRequestStageChunkAndBothElapsedTimesWithoutDocumentContents() {
        var now = 0L
        val records = mutableListOf<Pair<String, Throwable?>>()
        val diagnostics = IndexingDiagnostics({ message, error -> records += message to error }, { now })
        diagnostics.request = 23
        now = 50
        diagnostics.start("embedding", "chunk=2/5 inputTokens=300 deadlineMs=600000")
        now = 175
        val error = InterruptedIOException("Embedding deadline exceeded; private document text")
        diagnostics.report("failed", error)
        val (message, recordedError) = records.last()
        for (field in listOf("request=23", "event=failed", "stage=embedding", "elapsedMs=175", "stageMs=125",
            "chunk=2/5", "inputTokens=300", "deadlineMs=600000", "failure=timeout")) assertTrue(message, field in message)
        assertFalse(message.contains("private document text"))
        assertSame(error, recordedError)
    }

    @Test fun extractionFailureRetainsItsCodeAndOriginalCause() {
        val records = mutableListOf<Pair<String, Throwable?>>()
        val diagnostics = IndexingDiagnostics({ message, error -> records += message to error })
        diagnostics.start("extraction")
        val error = ExtractionFailure("malformed", "Cannot parse PDF").apply { initCause(IOException("Parser failure")) }
        diagnostics.report("failed", error)
        assertTrue(records.last().first.contains("failure=malformed"))
        assertSame(error, records.last().second)
        assertNotNull(records.last().second?.cause)
    }

    @Test fun movingStagesResetsOnlyTheStageClock() {
        var now = 100L
        val messages = mutableListOf<String>()
        val diagnostics = IndexingDiagnostics({ message, _ -> messages += message }, { now })
        now = 125
        diagnostics.start("extraction")
        now = 150
        diagnostics.start("chunking")
        now = 200
        diagnostics.report("completed")
        assertTrue(messages.last().contains("elapsedMs=100"))
        assertTrue(messages.last().contains("stageMs=50"))
        assertTrue(messages.last().contains("stage=chunking"))
        assertTrue(messages.last().contains("failure=none"))
    }

    @Test fun otherExceptionsRemainIdentifiableWithoutPuttingTheirMessageInReleaseLogs() {
        var message = ""
        val diagnostics = IndexingDiagnostics({ text, _ -> message = text })
        diagnostics.report("failed", IllegalStateException("content://private/document"))
        assertTrue(message.contains("failure=IllegalStateException"))
        assertFalse(message.contains("content://"))
    }
}
