package com.example.tp1.browser

import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.database.Cursor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import com.example.tp1.sources.SelectedSource
import com.example.tp1.sources.SourceKind
import com.example.tp1.sources.SourceMetadata
import java.io.FileNotFoundException
import java.io.IOException
import java.io.Closeable
import java.io.InputStream
import java.util.Locale

internal data class DirectoryListing(val entries: List<BrowserEntry>, val pending: Boolean)

internal class AuthorizedDocument(val metadata: SourceMetadata, val stream: InputStream) : Closeable {
    override fun close() = stream.close()
}

/** Source-authorized provider access. Call on Dispatchers.IO. */
internal class DocumentBrowser(private val resolver: ContentResolver) {
    fun root(source: SelectedSource): BrowserEntry {
        val uri = source.record.uri.toUri()
        val tree = source.record.kind == SourceKind.Tree
        return BrowserEntry(
            source.record.uri,
            (if (tree) DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)) else uri).toString(),
            checkNotNull(source.metadata), tree
        )
    }

    fun children(source: SelectedSource, folder: BrowserFolder): DirectoryListing {
        requireRead(source)
        require(source.record.kind == SourceKind.Tree)
        val tree = source.record.uri.toUri()
        val document = authorizedDocument(source, folder.uri)
        val info = metadata(document.toString(), folder.name)
        if (info.mimeType != null && info.mimeType != Document.MIME_TYPE_DIR) throw FileNotFoundException()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(document))
        val cursor = resolver.query(children, columns, null, null, null) ?: throw IOException("No provider cursor")
        return cursor.use { it ->
            if (it.extras.getString(DocumentsContract.EXTRA_ERROR) != null) throw IOException("Provider error")
            val entries = buildList {
                while (it.moveToNext()) {
                    val id = it.text(Document.COLUMN_DOCUMENT_ID)?.takeIf(String::isNotBlank)
                        ?: throw IOException("Missing document identity")
                    val details = it.details()
                    add(BrowserEntry(source.record.uri,
                        DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(),
                        details, details.mimeType == Document.MIME_TYPE_DIR))
                }
            }.distinctBy { it.uri }
            DirectoryListing(entries, it.extras.getBoolean(DocumentsContract.EXTRA_LOADING))
        }
    }

    fun viewIntent(source: SelectedSource, entry: BrowserEntry): Intent {
        requireRead(source)
        require(entry.sourceUri == source.record.uri && !entry.directory)
        // A document selection never authorizes another document, even with a similar URI.
        require(source.record.kind == SourceKind.Tree || entry.uri == source.record.uri)
        val uri = authorizedDocument(source, entry.uri)
        resolver.openAssetFileDescriptor(uri, "r")?.use { } ?: throw FileNotFoundException()
        val details = metadata(entry.uri, entry.metadata.name)
        if (details.mimeType == Document.MIME_TYPE_DIR) throw FileNotFoundException()
        val mime = details.mimeType?.takeIf { it.isNotBlank() }
            ?: resolver.getType(uri)?.takeIf { it.isNotBlank() }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(details.name.substringAfterLast('.', "").lowercase(Locale.ROOT))
            ?: "application/octet-stream"
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            clipData = ClipData.newRawUri(details.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun openRead(source: SelectedSource, entry: BrowserEntry): AuthorizedDocument {
        requireRead(source)
        if (entry.sourceUri != source.record.uri || entry.directory) throw SecurityException("Invalid document selection")
        val uri = authorizedDocument(source, entry.uri)
        val details = metadata(uri.toString(), entry.metadata.name)
        if (details.mimeType == Document.MIME_TYPE_DIR) throw FileNotFoundException("Cannot extract a directory")
        val stream = resolver.openInputStream(uri) ?: throw FileNotFoundException("Document unavailable")
        return AuthorizedDocument(details, stream)
    }

    private fun requireRead(source: SelectedSource) {
        if (!source.available || resolver.persistedUriPermissions.none {
                it.isReadPermission && it.uri.toString() == source.record.uri
            }) throw SecurityException("Source read grant unavailable")
    }

    private fun authorizedDocument(source: SelectedSource, text: String): android.net.Uri {
        val uri = text.toUri()
        val expected = if (source.record.kind == SourceKind.Tree) {
            DocumentsContract.buildDocumentUriUsingTree(source.record.uri.toUri(), DocumentsContract.getDocumentId(uri))
        } else source.record.uri.toUri()
        if (uri != expected) throw SecurityException("Document is not anchored to this source")
        return expected
    }

    private fun metadata(uri: String, fallback: String): SourceMetadata =
        (resolver.query(uri.toUri(), columns, null, null, null) ?: throw FileNotFoundException("Document unavailable")).use {
            if (!it.moveToFirst()) throw FileNotFoundException()
            it.details(fallback)
        }

    private fun Cursor.details(fallback: String = "Sin nombre") = SourceMetadata(
        text(Document.COLUMN_DISPLAY_NAME)?.takeIf(String::isNotBlank) ?: fallback,
        text(Document.COLUMN_MIME_TYPE),
        number(Document.COLUMN_SIZE)?.takeIf { it >= 0 },
        number(Document.COLUMN_LAST_MODIFIED)?.takeIf { it > 0 }
    )

    private fun Cursor.text(column: String): String? = getColumnIndex(column).let {
        if (it < 0 || isNull(it)) null else getString(it)
    }

    private fun Cursor.number(column: String): Long? = getColumnIndex(column).let {
        if (it < 0 || isNull(it)) null else getLong(it)
    }

    private companion object {
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
    }
}
