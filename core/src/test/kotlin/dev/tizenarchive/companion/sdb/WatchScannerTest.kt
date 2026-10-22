package dev.tizenarchive.companion.sdb

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WatchScannerTest {
    private fun ip(text: String): InetAddress = InetAddress.getByName(text)

    private fun hosts(own: String, prefix: Int) = WatchScanner.candidates(ip(own), prefix).map { it.hostAddress }

    @Test
    fun `a 24 network gives its 253 other hosts`() {
        val list = hosts("192.168.0.223", 24)
        assertEquals(253, list.size)
        assertEquals("192.168.0.1", list.first())
        assertEquals("192.168.0.254", list.last())
        assertFalse("192.168.0.223" in list)
        assertFalse("192.168.0.0" in list)
        assertFalse("192.168.0.255" in list)
    }

    @Test
    fun `small networks are scanned whole`() {
        assertEquals(listOf("192.168.43.2"), hosts("192.168.43.1", 30))
        assertEquals(listOf("10.1.2.1", "10.1.2.2", "10.1.2.3", "10.1.2.5", "10.1.2.6").sorted(), hosts("10.1.2.4", 29).sorted())
        assertEquals(1021, hosts("10.0.0.5", 22).size)
    }

    @Test
    fun `a larger network is cut down to the 24 around the phone`() {
        for (prefix in listOf(21, 16, 8)) {
            val list = hosts("10.20.30.40", prefix)
            assertEquals(253, list.size, "prefix $prefix")
            assertTrue(list.all { it.orEmpty().startsWith("10.20.30.") })
        }
    }

    @Test
    fun `addresses that are not private and nonsense prefixes give nothing`() {
        assertTrue(hosts("8.8.8.8", 24).isEmpty())
        assertTrue(hosts("100.64.1.1", 24).isEmpty())
        assertTrue(hosts("127.0.0.1", 24).isEmpty())
        assertTrue(WatchScanner.candidates(ip("::1"), 24).isEmpty())
        for (prefix in listOf(-1, 0, 31, 32, 33)) assertTrue(hosts("192.168.0.5", prefix).isEmpty(), "prefix $prefix")
    }

    @Test
    fun `a 172 private network is accepted`() {
        assertEquals(253, hosts("172.16.5.9", 24).size)
        assertTrue(hosts("172.32.5.9", 24).isEmpty())
    }

    private fun fake(vararg answers: Pair<String, ProbeResult>, log: MutableList<Int>? = null) = SdbProbe { address, timeout ->
        log?.add(timeout)
        answers.firstOrNull { it.first == address.hostAddress }?.second ?: ProbeResult.NONE
    }

    private fun all() = WatchScanner.candidates(ip("192.168.0.223"), 24)

    @Test
    fun `scan returns the sdb devices in address order, busy ones marked`() {
        val probe = fake("192.168.0.188" to ProbeResult.SDB, "192.168.0.9" to ProbeResult.SDB_BUSY, "192.168.0.50" to ProbeResult.OTHER)
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        val found = WatchScanner(probe).scan(all()) { seen += it.address.hostAddress.orEmpty() }
        assertEquals(listOf("192.168.0.9", "192.168.0.188"), found.map { it.address.hostAddress })
        assertEquals(listOf(true, false), found.map { it.busy })
        assertEquals(setOf("192.168.0.9", "192.168.0.188"), seen.toSet())
    }

    @Test
    fun `a second, patient pass runs only when the first found nothing`() {
        val waits = java.util.Collections.synchronizedList(mutableListOf<Int>())
        assertTrue(WatchScanner(fake(log = waits)).scan(all()).isEmpty())
        assertEquals(setOf(400, 1200), waits.toSet())
        assertEquals(253 * 2, waits.size)

        val sleepy = SdbProbe { _, timeout -> if (timeout >= 1200) ProbeResult.SDB else ProbeResult.NONE }
        assertEquals(253, WatchScanner(sleepy).scan(all()).size)

        val quick = java.util.Collections.synchronizedList(mutableListOf<Int>())
        WatchScanner(fake("192.168.0.188" to ProbeResult.SDB, log = quick)).scan(all())
        assertEquals(setOf(400), quick.toSet())
    }

    @Test
    fun `no more probes run at once than allowed`() {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val probe = SdbProbe { _, _ ->
            peak.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
            Thread.sleep(5)
            running.decrementAndGet()
            ProbeResult.SDB
        }
        WatchScanner(probe, parallelism = 8).scan(all())
        assertTrue(peak.get() in 2..8, "peak ${peak.get()}")
    }

    @Test
    fun `an empty candidate list scans nothing`() {
        assertTrue(WatchScanner(fake()).scan(emptyList()).isEmpty())
    }

    @Test
    fun `interrupting the caller stops the scan`() {
        val started = CountDownLatch(1)
        val probe = SdbProbe { _, _ ->
            started.countDown()
            Thread.sleep(200)
            ProbeResult.NONE
        }
        val outcome = AtomicReference<Throwable?>()
        val worker = thread {
            try {
                WatchScanner(probe, parallelism = 2).scan(all())
            } catch (e: Throwable) {
                outcome.set(e)
            }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val begun = System.nanoTime()
        worker.interrupt()
        worker.join(5_000)
        assertFalse(worker.isAlive)
        assertTrue(outcome.get() is InterruptedException)
        assertTrue(System.nanoTime() - begun < 3_000_000_000L, "stopped in ${(System.nanoTime() - begun) / 1_000_000} ms")
    }

    // ---- the real probe on loopback ---------------------------------------------------------------------------

    private val loopback = InetAddress.getLoopbackAddress()

    @Test
    fun `the real probe recognizes an sdb daemon by its authorization request`() {
        FakeSdbd().use { watch ->
            assertEquals(ProbeResult.SDB, TcpSdbProbe(watch.address.port).probe(loopback, 500))
            assertEquals(0, watch.confirmations.get())
        }
    }

    @Test
    fun `a daemon that closes at once is reported as busy`() {
        FakeSdbd().use { watch ->
            watch.busy = true
            assertEquals(ProbeResult.SDB_BUSY, TcpSdbProbe(watch.address.port).probe(loopback, 500))
        }
    }

    @Test
    fun `a daemon that resets the connection is busy too`() {
        FakeSdbd().use { watch ->
            watch.busy = true
            watch.busyReset = true
            assertEquals(ProbeResult.SDB_BUSY, TcpSdbProbe(watch.address.port).probe(loopback, 500))
        }
    }

    @Test
    fun `a closed port is nothing`() {
        val port = ServerSocket(0).use { it.localPort }
        assertEquals(ProbeResult.NONE, TcpSdbProbe(port).probe(loopback, 300))
    }

    @Test
    fun `some other service on the port is not an sdb daemon`() {
        FakeSdbd().use { watch ->
            watch.sendGarbage = true
            assertEquals(ProbeResult.OTHER, TcpSdbProbe(watch.address.port).probe(loopback, 500))
        }
        ServerSocket(0).use { server ->
            val answering = thread(isDaemon = true) {
                server.accept().use { it.getOutputStream().write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray()) }
            }
            assertEquals(ProbeResult.OTHER, TcpSdbProbe(server.localPort).probe(loopback, 500))
            answering.join(2_000)
        }
    }

    @Test
    fun `a service that stays silent is not an sdb daemon and does not hang the scan`() {
        ServerSocket(0).use { server ->
            val held = thread(isDaemon = true) { server.accept().use { runCatching { Thread.sleep(3_000) } } }
            val started = System.nanoTime()
            assertEquals(ProbeResult.OTHER, TcpSdbProbe(server.localPort).probe(loopback, 300))
            assertTrue((System.nanoTime() - started) < 3_000_000_000L)
            held.interrupt()
        }
    }

    @Test
    fun `a socket that cannot be made is no answer`() {
        val probe = TcpSdbProbe(26101, socketFactory = { throw java.io.IOException("no network") })
        assertEquals(ProbeResult.NONE, probe.probe(loopback, 200))
    }

    @Test
    fun `the scan on loopback finds the fake watch`() {
        FakeSdbd().use { watch ->
            val found = WatchScanner(TcpSdbProbe(watch.address.port), parallelism = 4).scan(listOf(loopback))
            assertEquals(listOf("127.0.0.1"), found.map { it.address.hostAddress })
        }
    }
}
