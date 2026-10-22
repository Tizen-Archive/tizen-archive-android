package dev.tizenarchive.companion.sdb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WatchAddressTest {
    @Test
    fun `private IPv4 addresses are accepted`() {
        for (ok in listOf("192.168.0.188", "10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.254", "169.254.10.20", "192.168.43.1")) {
            assertNotNull(WatchAddress.parse(ok), ok)
        }
        assertEquals("192.168.0.188", WatchAddress.parse("192.168.0.188")!!.hostAddress)
    }

    @Test
    fun `anything else is refused`() {
        val bad = listOf(
            "8.8.8.8", "172.15.0.1", "172.32.0.1", "127.0.0.1", "0.0.0.0", "100.64.0.1", "224.0.0.1", "255.255.255.255",
            "localhost", "watch.local", "::1", "fe80::1", "", " 192.168.0.1", "192.168.0.1 ", "192.168.0.1:26101",
            "192.168.0", "192.168.0.1.5", "192.168.0.256", "192.168.0.-1", "192.168.00.1", "192.168.0.01", "0xc0.168.0.1", "192.168.0.1\n",
            "１９２.168.0.1",
        )
        for (text in bad) assertNull(WatchAddress.parse(text), "'$text'")
    }
}
