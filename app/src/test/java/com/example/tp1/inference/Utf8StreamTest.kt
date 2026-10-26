package com.example.tp1.inference

import java.nio.charset.CharacterCodingException
import org.junit.Assert.*
import org.junit.Test

class Utf8StreamTest {
    @Test fun everySplitAndByteAtATimePreserveEmojiCjkAndNul() {
        val expected = "Español 日本語 😀\u0000 fin"
        val bytes = expected.toByteArray(Charsets.UTF_8)
        for (split in 0..bytes.size) {
            val decoder = Utf8Stream()
            val text = decoder.append(bytes.copyOfRange(0, split)) + decoder.append(byteArrayOf()) +
                decoder.append(bytes.copyOfRange(split, bytes.size)) + decoder.append(byteArrayOf(), end = true)
            assertEquals("Split $split", expected, text)
        }
        val decoder = Utf8Stream()
        val output = buildString {
            bytes.forEach { append(decoder.append(byteArrayOf(it))) }
            append(decoder.append(byteArrayOf(), end = true))
        }
        assertEquals(expected, output)
    }

    @Test fun malformedAndIncompleteFinalSequencesFail() {
        for (invalid in listOf(byteArrayOf(0xff.toByte()), byteArrayOf(0xc0.toByte(), 0x80.toByte()),
                byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()))) {
            assertThrows(CharacterCodingException::class.java) { Utf8Stream().append(invalid) }
        }
        val decoder = Utf8Stream()
        assertEquals("ok", decoder.append("ok".toByteArray() + byteArrayOf(0xf0.toByte(), 0x9f.toByte())))
        assertEquals(2, decoder.pendingByteCount)
        assertThrows(CharacterCodingException::class.java) { decoder.append(byteArrayOf(), end = true) }
    }
}
