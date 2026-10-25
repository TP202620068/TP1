package com.example.tp1.extraction

/** The constructor descriptor is also used by Rust and proguard-rules.pro. */
internal data class NativeConversion(
    val code: String,
    val detail: String,
    val format: String?,
    val ocrPages: LongArray,
    val pageCount: Long
)

internal object AnyDocNative {
    init { System.loadLibrary("tp1_anydoc") }

    fun convert(inputPath: String, extensionHint: String?, outputPath: String, limits: ExtractionLimits): NativeConversion =
        convertNative(inputPath, extensionHint, outputPath, limits.inputBytes, limits.outputBytes)

    private external fun convertNative(
        inputPath: String,
        extensionHint: String?,
        outputPath: String,
        inputLimit: Long,
        outputLimit: Long
    ): NativeConversion
}
