package com.example.tp1.indexing

import com.example.tp1.extraction.ExtractionFailure
import java.io.InterruptedIOException

internal class IndexingDiagnostics(
    private val log: (String, Throwable?) -> Unit,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val started = nowMillis()
    private var stageStarted = started
    private var stage = "storage"
    private var details = ""
    var request = 0L

    fun start(stage: String, details: String = "") {
        this.stage = stage
        this.details = details
        stageStarted = nowMillis()
        report("started")
    }

    fun report(event: String, error: Throwable? = null) {
        val now = nowMillis()
        val failure = when (error) {
            is InterruptedIOException -> "timeout"
            is ExtractionFailure -> error.code
            null -> "none"
            else -> error.javaClass.simpleName
        }
        log("request=$request event=$event stage=$stage elapsedMs=${now - started} " +
            "stageMs=${now - stageStarted} $details failure=$failure", error)
    }
}
