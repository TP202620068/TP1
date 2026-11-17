package com.example.tp1.browser

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.example.tp1.extraction.ExtractionWork
import com.example.tp1.indexing.RetrievalMatch
import com.example.tp1.sources.SourceMetadata
import com.example.tp1.sources.SourceStore
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class DocumentAccess(val sourceUri: String, val documentUri: String)
internal data class DocumentReference(
    val authority: String,
    val providerId: String,
    val name: String,
    val alternatives: List<DocumentAccess>
)

internal fun documentIdentity(documentUri: String): Pair<String, String> {
    val uri = documentUri.toUri()
    return requireNotNull(uri.authority) to DocumentsContract.getDocumentId(uri)
}

internal fun documentReferences(matches: List<RetrievalMatch>): List<DocumentReference> =
    matches.groupBy { documentIdentity(it.documentUri) }.map { (identity, chunks) ->
    DocumentReference(identity.first, identity.second, chunks.first().displayName,
        chunks.map { DocumentAccess(it.sourceUri, it.documentUri) }.distinct())
}

/** Cached metadata is never a grant. Resolve saved membership again for every click. */
internal suspend fun documentIntent(context: Context, document: DocumentReference): Intent = withContext(Dispatchers.IO) {
    val coroutine = currentCoroutineContext()
    synchronized(ExtractionWork.sourceLock) {
        val sources = SourceStore(context).readForRetrieval().associateBy { it.record.uri }
        val browser = DocumentBrowser(context.contentResolver)
        for (access in document.alternatives) {
            coroutine.ensureActive()
            val source = sources[access.sourceUri]?.takeIf { it.available } ?: continue
            val uri = access.documentUri.toUri()
            if (uri.authority != document.authority || DocumentsContract.getDocumentId(uri) != document.providerId) {
                throw SecurityException("Invalid document identity")
            }
            try {
                return@synchronized browser.viewIntent(source, BrowserEntry(access.sourceUri, access.documentUri,
                    SourceMetadata(document.name, null, null, null), false))
            } catch (_: SecurityException) { }
            catch (_: FileNotFoundException) { }
        }
        throw SecurityException("Document unavailable")
    }
}
