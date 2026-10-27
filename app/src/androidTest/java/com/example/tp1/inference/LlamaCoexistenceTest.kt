package com.example.tp1.inference

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tp1.browser.DocumentBrowser
import com.example.tp1.extraction.DocumentExtractor
import com.example.tp1.sources.SourceKind
import com.example.tp1.sources.SourceStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Run each method in its own instrumentation process to establish both library load orders. */
@ManualInference
class LlamaCoexistenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private suspend fun extract(): String {
        val resolver = context.contentResolver
        val provider = Uri.parse("content://com.example.tp1.test.documents")
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
        val fixture = try {
            checkNotNull(resolver.call(provider, "fixture-extraction", null, Bundle().apply {
                putByteArray("bytes", "name,value\nTP1,coexistence fixture\n".toByteArray())
                putString("name", "coexistence.csv")
                putString("mime", "text/csv")
                putLong("size", -1)
            }))
        } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
        // All source access and native extraction below use the ordinary app grant.
        val uri = checkNotNull(fixture.getString("uri"))
        val store = SourceStore(context)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        val source = store.add(store.restore().sources, listOf(uri), SourceKind.Document, flags).sources.single { it.record.uri == uri }
        try {
            val artifact = DocumentExtractor(context).extract(source, DocumentBrowser(resolver).root(source))
            val text = artifact.markdown.inputStream().use { String(it.readNBytes(1024), Charsets.UTF_8) }
            assertTrue(text.contains("coexistence fixture"))
            return text
        } finally { store.remove(store.restore().sources, uri) }
    }

    private suspend fun infer(text: String) {
        val session = InferenceSession.open(ModelManager(context), Model.Test)
        try {
            val response = session.infer("Read this excerpt, then say hello:\n$text")
            assertTrue(response.text.isNotBlank())
            assertTrue(response.finishReason in setOf(FinishReason.EOG, FinishReason.LENGTH))
            assertTrue(response.generatedTokens in 1..32)
        } finally { session.close() }
    }

    @Test fun extractionThenInference() = runBlocking<Unit> {
        val text = extract() // Loads AnyDoc before constructing the inference bridge.
        repeat(2) { infer(if (it == 0) text else extract()) }
    }

    @Test fun inferenceThenExtractionThenInference() = runBlocking<Unit> {
        infer("Synthetic fixture") // Session closes before parsing.
        repeat(2) { infer(extract()) }
    }
}
