package dev.tizenarchive.companion.sdb

import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors

/** What a probe of one address found. */
public enum class ProbeResult {
    /** Nothing answers on the sdb port. */
    NONE,

    /** The port is open but whatever answers is not sdb. */
    OTHER,

    /** An sdb daemon: it answered the connection request with an authorization request or accepted it. */
    SDB,

    /** An sdb daemon that closed the connection at once, as it does while another host is connected to it. */
    SDB_BUSY,
}

public fun interface SdbProbe {
    public fun probe(address: InetAddress, timeoutMs: Int): ProbeResult
}

/** One sdb device found in the local network. */
public class FoundWatch(public val address: InetAddress, public val busy: Boolean)

/**
 * Probes the sdb port with a connection request and reads the first packet. The request makes the watch send an authorization
 * request and nothing more: no key is offered, so the watch shows no dialog.
 */
public class TcpSdbProbe(
    private val port: Int = SDB_PORT,
    private val socketFactory: () -> Socket = { Socket() },
) : SdbProbe {
    override fun probe(address: InetAddress, timeoutMs: Int): ProbeResult {
        val socket = try {
            socketFactory()
        } catch (_: IOException) {
            return ProbeResult.NONE
        }
        try {
            socket.tcpNoDelay = true
            try {
                socket.connect(InetSocketAddress(address, port), timeoutMs)
            } catch (_: IOException) {
                return ProbeResult.NONE
            }
            socket.soTimeout = maxOf(timeoutMs, MIN_ANSWER_MS)
            val output = socket.getOutputStream()
            output.write(SdbPacket(SdbPacket.CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray(Charsets.US_ASCII)).encode())
            output.flush()
            return try {
                val packet = SdbPacket.read(socket.getInputStream(), MAX_PACKET)
                if (packet.command == SdbPacket.AUTH || packet.command == SdbPacket.CNXN) ProbeResult.SDB else ProbeResult.OTHER
            } catch (_: EOFException) {
                ProbeResult.SDB_BUSY
            } catch (_: SocketException) {
                ProbeResult.SDB_BUSY
            } catch (_: IOException) {
                ProbeResult.OTHER
            }
        } catch (_: IOException) {
            return ProbeResult.NONE
        } finally {
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }

    public companion object {
        public const val SDB_PORT: Int = 26101
        private const val VERSION = 0x01000000
        private const val MAX_DATA = 0x40000
        private const val MAX_PACKET = 1 shl 16
        private const val MIN_ANSWER_MS = 1000
    }
}

/** Looks for watches (sdb daemons) in the phone's own local network. */
public class WatchScanner(private val probe: SdbProbe = TcpSdbProbe(), private val parallelism: Int = 64) {
    /** The probing runs [parallelism] addresses at a time and stops at once when the calling thread is interrupted. */
    @Throws(InterruptedException::class)
    public fun scan(candidates: List<InetAddress>, onFound: (FoundWatch) -> Unit = {}): List<FoundWatch> {
        val first = run(candidates, FIRST_PASS_MS, onFound)
        // A watch that sleeps wakes up on the first packet but answers too late for the short wait: try once more, patiently.
        val found = first.ifEmpty { run(candidates, SECOND_PASS_MS, onFound) }
        return found.sortedBy { it.address.address.fold(0L) { acc, b -> acc * 256 + (b.toInt() and 0xff) } }
    }

    private fun run(candidates: List<InetAddress>, timeoutMs: Int, onFound: (FoundWatch) -> Unit): List<FoundWatch> {
        val pool = Executors.newFixedThreadPool(parallelism.coerceIn(1, maxOf(1, candidates.size)))
        try {
            val completion = ExecutorCompletionService<FoundWatch?>(pool)
            for (address in candidates) {
                completion.submit {
                    when (probe.probe(address, timeoutMs)) {
                        ProbeResult.SDB -> FoundWatch(address, busy = false)
                        ProbeResult.SDB_BUSY -> FoundWatch(address, busy = true)
                        else -> null
                    }?.also(onFound)
                }
            }
            val found = mutableListOf<FoundWatch>()
            repeat(candidates.size) { completion.take().get()?.let(found::add) }
            return found
        } finally {
            pool.shutdownNow()
        }
    }

    public companion object {
        private const val FIRST_PASS_MS = 400
        private const val SECOND_PASS_MS = 1200
        private const val MAX_HOSTS = 1022

        /**
         * The addresses to probe around [own], a private IPv4 address with the network's [prefixLength]: the hosts of its subnet without
         * the phone itself. A network larger than about a thousand hosts is cut down to the /24 around the phone; a public or
         * not private address gives an empty list.
         */
        public fun candidates(own: InetAddress, prefixLength: Int): List<InetAddress> {
            val bytes = own.address
            if (bytes.size != 4 || WatchAddress.parse(own.hostAddress.orEmpty()) == null) return emptyList()
            if (prefixLength !in 1..30) return emptyList()
            val effective = if (hostCount(prefixLength) > MAX_HOSTS) 24 else prefixLength
            val value = bytes.fold(0L) { acc, b -> acc * 256 + (b.toInt() and 0xff) }
            val mask = (0xffffffffL shl (32 - effective)) and 0xffffffffL
            val network = value and mask
            val broadcast = network or mask.inv() and 0xffffffffL
            return (network + 1 until broadcast).filter { it != value }.map { n ->
                InetAddress.getByAddress(ByteArray(4) { i -> (n shr (24 - 8 * i)).toByte() })
            }
        }

        private fun hostCount(prefixLength: Int): Long = (1L shl (32 - prefixLength)) - 2
    }
}
