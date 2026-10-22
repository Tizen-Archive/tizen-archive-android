package dev.tizenarchive.companion.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryCodecTest {
    @Test
    fun `a history survives encoding and decoding`() {
        val items = listOf(RememberedPackage("com.a", "1.0", "Alpha", 10), RememberedPackage("b.c", null, null, 20))
        val back = HistoryCodec.decode(HistoryCodec.encode(items))
        assertEquals(listOf("com.a", "b.c"), back.map { it.id })
        assertEquals("1.0", back[0].version)
        assertEquals("Alpha", back[0].name)
        assertNull(back[1].version)
        assertNull(back[1].name)
        assertEquals(listOf(10L, 20L), back.map { it.installedAt })
    }

    @Test
    fun `separators inside a name cannot break the format`() {
        val back = HistoryCodec.decode(HistoryCodec.encode(listOf(RememberedPackage("com.a", "1\t2", "Na\tme\nx\r", 5))))
        assertEquals(1, back.size)
        assertEquals("Na me x ", back[0].name)
        assertEquals("1 2", back[0].version)
    }

    @Test
    fun `damaged lines are dropped, the rest is kept`() {
        val text = "com.a\t1.0\tAlpha\t10\nnot enough fields\n\t1\t2\t3\ncom.b\t\t\tnot-a-number\n"
        assertEquals(listOf("com.a", "com.b"), HistoryCodec.decode(text).map { it.id })
        assertEquals(0L, HistoryCodec.decode(text)[1].installedAt)
        assertEquals(emptyList<RememberedPackage>(), HistoryCodec.decode(""))
    }
}
