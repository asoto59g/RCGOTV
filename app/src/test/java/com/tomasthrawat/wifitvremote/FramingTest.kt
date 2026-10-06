package com.abcgeomag.rcgotv

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class FramingTest {
    @Test
    fun readsEmptyFrame() {
        val output = ByteArrayOutputStream()
        Framing.write(output, byteArrayOf())

        assertArrayEquals(byteArrayOf(), Framing.read(ByteArrayInputStream(output.toByteArray())))
    }

    @Test
    fun readsSmallFrame() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val output = ByteArrayOutputStream()
        Framing.write(output, payload)

        assertArrayEquals(payload, Framing.read(ByteArrayInputStream(output.toByteArray())))
    }

    @Test
    fun readsFrameWithMultiByteLength() {
        val payload = ByteArray(200) { it.toByte() }
        val output = ByteArrayOutputStream()
        Framing.write(output, payload)

        assertArrayEquals(payload, Framing.read(ByteArrayInputStream(output.toByteArray())))
    }

    @Test
    fun readsPayloadWhenInputStreamReturnsShortReads() {
        val payload = ByteArray(20) { (it * 3).toByte() }
        val output = ByteArrayOutputStream()
        Framing.write(output, payload)
        val input = object : ByteArrayInputStream(output.toByteArray()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, minOf(len, 1))
        }

        assertArrayEquals(payload, Framing.read(input))
    }

    @Test(expected = EOFException::class)
    fun rejectsEofInLength() {
        Framing.read(ByteArrayInputStream(byteArrayOf(0x80.toByte())))
    }

    @Test(expected = EOFException::class)
    fun rejectsEofInPayload() {
        Framing.read(ByteArrayInputStream(byteArrayOf(0x02, 0x01)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOverLimitWrite() {
        Framing.write(ByteArrayOutputStream(), ByteArray(Framing.MAX_FRAME_SIZE + 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOverLimitRead() {
        // 1,048,577 encoded as a protobuf varint.
        Framing.read(ByteArrayInputStream(byteArrayOf(0x81.toByte(), 0x80.toByte(), 0x40)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedFiveByteVarint() {
        Framing.read(
            ByteArrayInputStream(
                byteArrayOf(
                    0x80.toByte(),
                    0x80.toByte(),
                    0x80.toByte(),
                    0x80.toByte(),
                    0x10
                )
            )
        )
    }
}