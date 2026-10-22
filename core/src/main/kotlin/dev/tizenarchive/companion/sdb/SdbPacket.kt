package dev.tizenarchive.companion.sdb

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One sdb packet: a 24-byte little-endian header followed by [data]. */
internal class SdbPacket(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray) {
    fun encode(): ByteArray {
        var sum = 0
        for (b in data) sum += b.toInt() and 0xff
        return ByteBuffer.allocate(HEADER_SIZE + data.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1).putInt(data.size).putInt(sum).putInt(command xor -1)
            .put(data).array()
    }

    companion object {
        const val HEADER_SIZE = 24
        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43
        const val WRTE = 0x45545257

        /** Reads one packet. The checksum is not verified: the watch fills it in, but nothing depends on it. */
        fun read(input: InputStream, maxData: Int): SdbPacket {
            val header = ByteArray(HEADER_SIZE)
            readFully(input, header)
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val command = buffer.getInt()
            val arg0 = buffer.getInt()
            val arg1 = buffer.getInt()
            val length = buffer.getInt()
            buffer.getInt()
            val magic = buffer.getInt()
            if (magic != command xor -1) throw IOException("Bad packet magic")
            if (length < 0 || length > maxData) throw IOException("Bad packet length $length")
            val data = ByteArray(length)
            readFully(input, data)
            return SdbPacket(command, arg0, arg1, data)
        }

        private fun readFully(input: InputStream, target: ByteArray) {
            var done = 0
            while (done < target.size) {
                val n = input.read(target, done, target.size - done)
                if (n < 0) throw EOFException()
                done += n
            }
        }
    }
}
