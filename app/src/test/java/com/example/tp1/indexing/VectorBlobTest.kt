package com.example.tp1.indexing

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class VectorBlobTest {
    @Test fun exactLittleEndianFloatRoundTrip() {
        val vector = FloatArray(512).apply { this[0] = 1f; this[1] = -0f }
        val bytes = encodeVector(vector)
        assertEquals(2048, bytes.size)
        assertArrayEquals(byteArrayOf(0, 0, 0x80.toByte(), 0x3f), bytes.copyOfRange(0, 4))
        assertArrayEquals(vector.map { it.toRawBits() }.toIntArray(), decodeVector(bytes).map { it.toRawBits() }.toIntArray())
    }

    @Test fun invalidDimensionsAndBlobLengthsFail() {
        for (size in listOf(0, 511, 513)) assertThrows(IOException::class.java) { encodeVector(FloatArray(size)) }
        for (size in listOf(0, 2047, 2049)) assertThrows(IOException::class.java) { decodeVector(ByteArray(size)) }
    }

    @Test fun nonFiniteZeroAndUnnormalizedVectorsFailOnWriteAndRead() {
        for (value in listOf(0f, 2f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val vector = FloatArray(512).apply { this[0] = value }
            assertThrows(IOException::class.java) { encodeVector(vector) }
            val corrupt = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array()
            assertThrows(IOException::class.java) { decodeVector(corrupt) }
        }
    }
}
