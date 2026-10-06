package com.abcgeomag.rcgotv

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

object Framing {
    const val MAX_FRAME_SIZE = 1024 * 1024

    fun write(output: OutputStream, bytes: ByteArray) {
        require(bytes.size <= MAX_FRAME_SIZE) { "Protobuf frame exceeds " + MAX_FRAME_SIZE + " bytes" }
        var length = bytes.size
        while (length > 0x7f) {
            output.write((length and 0x7f) or 0x80)
            length = length ushr 7
        }
        output.write(length)
        output.write(bytes)
        output.flush()
    }

    fun read(input: InputStream): ByteArray {
        var length = 0
        for (index in 0 until 5) {
            val value = input.read()
            if (value < 0) throw EOFException("EOF while reading protobuf frame length")
            if (index == 4 && (value and 0xf0) != 0) {
                throw IllegalArgumentException("Invalid protobuf frame length varint")
            }
            length = length or ((value and 0x7f) shl (index * 7))
            if ((value and 0x80) == 0) {
                if (length < 0 || length > MAX_FRAME_SIZE) {
                    throw IllegalArgumentException("Protobuf frame length exceeds " + MAX_FRAME_SIZE + " bytes")
                }
                val bytes = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val count = input.read(bytes, offset, length - offset)
                    if (count < 0) throw EOFException("EOF while reading protobuf frame")
                    if (count == 0) continue
                    offset += count
                }
                return bytes
            }
        }
        throw IllegalArgumentException("Invalid protobuf frame length varint")
    }
}