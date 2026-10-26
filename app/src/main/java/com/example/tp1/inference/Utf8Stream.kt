package com.example.tp1.inference

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Token boundaries are byte boundaries, not character boundaries. Invalid UTF-8 is an error. */
internal class Utf8Stream {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    private var pending = byteArrayOf()
    val pendingByteCount: Int get() = pending.size

    fun append(piece: ByteArray, end: Boolean = false): String {
        val input = ByteBuffer.wrap(pending + piece)
        val output = CharBuffer.allocate(input.remaining() + 1)
        val result = decoder.decode(input, output, end)
        if (result.isError) result.throwException()
        check(!result.isOverflow)
        pending = ByteArray(input.remaining()).also { input.get(it) }
        if (end) {
            val flushed = decoder.flush(output)
            if (flushed.isError) flushed.throwException()
            check(!flushed.isOverflow && pending.isEmpty())
        }
        output.flip()
        return output.toString()
    }
}
