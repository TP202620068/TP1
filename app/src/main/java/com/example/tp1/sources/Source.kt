package com.example.tp1.sources

internal enum class SourceKind { Document, Tree }

internal data class SourceRecord(val uri: String, val kind: SourceKind, val name: String? = null)

internal data class SourceMetadata(
    val name: String,
    val mimeType: String?,
    val size: Long?,
    val modified: Long?
)

internal data class SelectedSource(val record: SourceRecord, val metadata: SourceMetadata?) {
    val available: Boolean get() = metadata != null
}

internal data class SourceState(
    val restoring: Boolean = true,
    val busy: Boolean = false,
    val selecting: Boolean = false,
    val storageReady: Boolean = false,
    val sources: List<SelectedSource> = emptyList(),
    val message: Int? = null
) {
    val canCloseSelection: Boolean get() = !restoring && !busy

    fun closeSelection(): SourceState = if (canCloseSelection) copy(selecting = false) else this
}

// URI text is the identity, including case and escaping. Names are only presentation.
internal fun List<SourceRecord>.deduplicated(): List<SourceRecord> = distinctBy { it.uri }
