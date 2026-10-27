package com.example.tp1.inference

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class InferenceResponseTest {
    @Test fun everyTerminalReasonPreservesTextCountsAndDetails() = runBlocking<Unit> {
        for (reason in FinishReason.entries) {
            val response = flowOf(
                GenerationEvent.Text("hello 😀"), GenerationEvent.Text("\u0000世界"),
                GenerationEvent.Finished(reason, 7, 2, "diagnostic")
            ).toInferenceResponse()
            assertEquals("hello 😀\u0000世界", response.text)
            assertEquals(reason, response.finishReason)
            assertEquals(7, response.generatedTokens)
            assertEquals(2, response.incompleteUtf8Bytes)
            assertEquals("diagnostic", response.detail)
        }
    }

    @Test fun missingDuplicateAndNonterminalTailAreRejected() = runBlocking<Unit> {
        val end = GenerationEvent.Finished(FinishReason.EOG, 0)
        for (events in listOf(emptyList(), listOf(GenerationEvent.Text("partial")), listOf(end, end),
                listOf(end, GenerationEvent.Text("late")))) {
            try { flowOf(*events.toTypedArray()).toInferenceResponse(); fail("Invalid stream accepted") }
            catch (_: IllegalStateException) { }
        }
        val empty = flowOf(end).toInferenceResponse()
        assertEquals("", empty.text)
        assertEquals(FinishReason.EOG, empty.finishReason)
    }

    @Test fun cancellationAndThrownFailuresNeverReturnSuccessfulPartialText() = runBlocking<Unit> {
        for (failure in listOf(CancellationException("cancelled"), IOException("failure"))) {
            try {
                flow { emit(GenerationEvent.Text("partial")); throw failure }.toInferenceResponse()
                fail("Failed stream returned a response")
            } catch (actual: Exception) { assertSame(failure, actual) }
        }
    }
}
