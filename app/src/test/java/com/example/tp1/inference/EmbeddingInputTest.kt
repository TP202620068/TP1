package com.example.tp1.inference

import org.junit.Assert.*
import org.junit.Test

class EmbeddingInputTest {
    @Test fun rolesAddExactlyOnePrefixWithoutChangingText() {
        val text = "  Garantía de dos años 😀\nDocument: literal content"
        assertEquals("Document: $text", String(embeddingInput(text, query = false), Charsets.UTF_8))
        assertEquals("Query: $text", String(embeddingInput(text, query = true), Charsets.UTF_8))
        assertNull(Model.JinaV5NanoRetrieval.profile.sampling)
        assertFalse(Model.JinaV5NanoRetrieval.profile.requireSingleLeadingBos)
        assertThrows(IllegalArgumentException::class.java) {
            Model.JinaV5NanoRetrieval.profile.copy(requireSingleLeadingBos = true)
        }
    }

    @Test fun invalidTextIsRejectedAndRolePrefixCountsTowardUtf8Limit() {
        for (query in listOf(false, true)) {
            for (text in listOf("", " \n\t", "bad\u0000text", "bad\uD800", "bad\uDC00")) {
                assertThrows(IllegalArgumentException::class.java) { embeddingInput(text, query) }
            }
            val prefixSize = if (query) 7 else 10
            val boundary = "a".repeat(64 * 1024 - prefixSize - 4) + "😀"
            assertEquals(64 * 1024, embeddingInput(boundary, query).size)
            assertThrows(IllegalArgumentException::class.java) { embeddingInput(boundary + "a", query) }
        }
    }
}
