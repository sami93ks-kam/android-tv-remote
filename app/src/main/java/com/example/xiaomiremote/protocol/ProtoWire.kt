package com.example.xiaomiremote.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private const val MAX_FRAME_SIZE = 256 * 1024

internal class ProtoWriter {
    private val bytes = ByteArrayOutputStream()

    fun writeInt32(field: Int, value: Int) = apply {
        bytes.writeVarint((field shl 3).toLong())
        bytes.writeVarint(value.toLong())
    }

    fun writeBytes(field: Int, value: ByteArray) = apply {
        bytes.writeVarint(((field shl 3) or 2).toLong())
        bytes.writeVarint(value.size.toLong())
        bytes.write(value)
    }

    fun writeString(field: Int, value: String) = writeBytes(field, value.toByteArray(Charsets.UTF_8))

    fun writeMessage(field: Int, value: ProtoWriter) = writeBytes(field, value.bytes.toByteArray())

    fun writeDelimitedTo(out: OutputStream) {
        val frame = ByteArrayOutputStream()
        frame.writeVarint(bytes.size().toLong())
        bytes.writeTo(frame)
        out.write(frame.toByteArray())
        out.flush()
    }
}

internal class ProtoReader(bytes: ByteArray) {
    private val input = ByteArrayInputStream(bytes)

    fun forEachField(read: ProtoReader.(field: Int, wireType: Int) -> Unit) {
        while (input.available() > 0) {
            val tag = input.readVarint(input.read())
            val field = tag ushr 3
            if (field == 0L || field > 0x1FFFFFFF) throw IOException("Invalid protobuf tag")
            read.invoke(this, field.toInt(), (tag and 7).toInt())
        }
    }

    fun readInt32() = input.readVarint(input.read()).toInt()

    fun readBytes(): ByteArray {
        val length = input.readVarint(input.read())
        if (length !in 0..input.available().toLong()) throw EOFException("Truncated protobuf field")
        return input.readFully(length.toInt())
    }

    fun skipField(wireType: Int) {
        val length = when (wireType) {
            0 -> {
                readInt32()
                return
            }
            1 -> 8L
            2 -> input.readVarint(input.read())
            5 -> 4L
            else -> throw IOException("Unsupported protobuf wire type")
        }
        if (length !in 0..input.available().toLong()) throw EOFException("Truncated protobuf field")
        input.skip(length)
    }

    companion object {
        fun readDelimited(input: InputStream): ProtoReader {
            val length = input.readVarint(input.read())
            if (length !in 0..MAX_FRAME_SIZE.toLong()) throw IOException("Invalid protobuf frame size")
            return ProtoReader(input.readFully(length.toInt()))
        }
    }
}

private fun InputStream.readFully(length: Int): ByteArray {
    val bytes = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val count = read(bytes, offset, length - offset)
        if (count < 0) throw EOFException("Truncated protobuf frame")
        offset += count
    }
    return bytes
}

private fun InputStream.readVarint(first: Int): Long {
    var byte = first
    var value = 0L
    for (shift in 0..63 step 7) {
        if (byte < 0) throw EOFException("Missing protobuf varint")
        if (shift == 63 && byte > 1) throw IOException("Invalid protobuf varint")
        value = value or ((byte and 0x7F).toLong() shl shift)
        if (byte < 128) return value
        byte = read()
    }
    throw IOException("Invalid protobuf varint")
}

private fun OutputStream.writeVarint(value: Long) {
    var rest = value
    while (rest ushr 7 != 0L) {
        write((rest.toInt() and 0x7F) or 0x80)
        rest = rest ushr 7
    }
    write(rest.toInt())
}
