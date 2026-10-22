package dev.tizenarchive.companion.sdb

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SdbPacketTest {
    @Test
    fun `a packet survives encoding and reading`() {
        val data = byteArrayOf(1, 2, 3, -1)
        val bytes = SdbPacket(SdbPacket.WRTE, 5, 9, data).encode()
        assertEquals(24 + 4, bytes.size)
        val read = SdbPacket.read(ByteArrayInputStream(bytes), 100)
        assertEquals(SdbPacket.WRTE, read.command)
        assertEquals(5, read.arg0)
        assertEquals(9, read.arg1)
        assertArrayEquals(data, read.data)
    }

    @Test
    fun `the header has the layout the watch expects`() {
        // CNXN, version 0x01000000, maxdata 0x40000, length 7, byte sum of "host::\0" (0x232), magic = ~CNXN
        val expected = "434e584e" + "00000001" + "00000400" + "07000000" + "32020000" + "bcb1a7b1" + "686f73743a3a00"
        val actual = SdbPacket(SdbPacket.CNXN, 0x01000000, 0x40000, "host::\u0000".toByteArray()).encode().joinToString("") { "%02x".format(it) }
        assertEquals(expected, actual)
    }

    @Test
    fun `a wrong magic is refused`() {
        val bytes = SdbPacket(SdbPacket.OKAY, 1, 2, ByteArray(0)).encode()
        bytes[20] = 0
        assertThrows(IOException::class.java) { SdbPacket.read(ByteArrayInputStream(bytes), 100) }
    }

    @Test
    fun `a length above the limit is refused before any allocation`() {
        val bytes = SdbPacket(SdbPacket.WRTE, 1, 2, ByteArray(50)).encode()
        assertThrows(IOException::class.java) { SdbPacket.read(ByteArrayInputStream(bytes), 49) }
    }

    @Test
    fun `a truncated packet ends with end of stream`() {
        val bytes = SdbPacket(SdbPacket.WRTE, 1, 2, ByteArray(50)).encode()
        assertThrows(EOFException::class.java) { SdbPacket.read(ByteArrayInputStream(bytes, 0, 30), 100) }
        assertThrows(EOFException::class.java) { SdbPacket.read(ByteArrayInputStream(bytes, 0, 10), 100) }
    }
}
