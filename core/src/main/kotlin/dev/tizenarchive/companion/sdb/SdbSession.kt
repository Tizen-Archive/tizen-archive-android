package dev.tizenarchive.companion.sdb

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

public class SdbOptions(
    public val connectTimeoutMs: Int = 10_000,
    public val readTimeoutMs: Int = 120_000,
    /** How long to wait for the user to confirm a new key on the watch. */
    public val confirmTimeoutMs: Int = 60_000,
)

/** An authorized sdb connection to one watch. Not thread-safe; [close] may be called from another thread to abort a running call. */
public class SdbSession private constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
    override val banner: String,
    private val deviceMaxData: Int,
) : SdbService {
    private var nextLocalId = 1

    override fun shell(command: String, onLine: (String) -> Unit): String {
        val id = open("shell:$command")
        val all = ByteArrayOutputStream()
        val pending = StringBuilder()
        try {
            while (true) {
                val packet = next(id.local) ?: continue
                if (packet.command == SdbPacket.WRTE) {
                    all.write(packet.data)
                    if (all.size() > MAX_SHELL_OUTPUT) throw SdbException(SdbError.PROTOCOL, "The command printed too much")
                    send(SdbPacket.OKAY, id.local, id.remote)
                    pending.append(String(packet.data, Charsets.UTF_8))
                    drainLines(pending, onLine)
                } else if (packet.command == SdbPacket.CLSE) {
                    send(SdbPacket.CLSE, id.local, id.remote)
                    break
                }
            }
        } catch (e: IOException) {
            throw translate(e)
        }
        if (pending.isNotEmpty()) onLine(pending.toString())
        return all.toString("UTF-8")
    }

    override fun push(remotePath: String, data: InputStream, mode: Int, progress: (Long) -> Unit) {
        require(REMOTE_PATH.matches(remotePath)) { "Unsupported remote path" }
        val id = open("sync:")
        try {
            val target = "$remotePath,$mode".toByteArray(Charsets.UTF_8)
            writeSync(id, "SEND", target.size, target)
            val buffer = ByteArray(minOf(MAX_CHUNK, deviceMaxData - SYNC_HEADER))
            var sent = 0L
            while (true) {
                val n = data.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                writeSync(id, "DATA", n, buffer.copyOf(n))
                sent += n
                progress(sent)
            }
            writeSync(id, "DONE", (System.currentTimeMillis() / 1000).toInt(), ByteArray(0))
            val reply = next(id.local)
            if (reply == null || reply.command != SdbPacket.WRTE) throw SdbException(SdbError.PROTOCOL, "No answer to the file transfer")
            send(SdbPacket.OKAY, id.local, id.remote)
            if (String(reply.data, 0, minOf(4, reply.data.size), Charsets.US_ASCII) != "OKAY") {
                throw SdbException(SdbError.PROTOCOL, "The watch refused the file: " + String(reply.data, Charsets.UTF_8).trim())
            }
            writeRaw(id, syncCommand("QUIT", 0, ByteArray(0)), waitForAck = false)
            // The service closes itself; whatever it still sends is skipped by local id in the next call.
        } catch (e: IOException) {
            throw translate(e)
        }
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    private class Stream(val local: Int, val remote: Int)

    private fun open(service: String): Stream {
        val local = nextLocalId++
        try {
            send(SdbPacket.OPEN, local, 0, (service + "\u0000").toByteArray(Charsets.UTF_8))
            while (true) {
                val packet = next(local) ?: continue
                if (packet.command == SdbPacket.OKAY) return Stream(local, packet.arg0)
                throw SdbException(SdbError.SERVICE_REFUSED, "The watch refused the service")
            }
        } catch (e: IOException) {
            throw translate(e)
        }
    }

    /** The next packet addressed to [local]; packets of earlier, already closed services are dropped (null). */
    private fun next(local: Int): SdbPacket? {
        val packet = SdbPacket.read(input, MAX_PACKET)
        return if (packet.arg1 == local) packet else null
    }

    private fun send(command: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)) {
        output.write(SdbPacket(command, arg0, arg1, data).encode())
        output.flush()
    }

    private fun syncCommand(id: String, length: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(SYNC_HEADER + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(id.toByteArray(Charsets.US_ASCII)).putInt(length).put(payload).array()

    private fun writeSync(id: Stream, command: String, length: Int, payload: ByteArray) = writeRaw(id, syncCommand(command, length, payload), waitForAck = true)

    private fun writeRaw(id: Stream, bytes: ByteArray, waitForAck: Boolean) {
        send(SdbPacket.WRTE, id.local, id.remote, bytes)
        if (!waitForAck) return
        while (true) {
            val ack = next(id.local) ?: continue
            if (ack.command != SdbPacket.OKAY) throw SdbException(SdbError.PROTOCOL, "The write was not acknowledged")
            return
        }
    }

    private fun drainLines(pending: StringBuilder, onLine: (String) -> Unit) {
        while (true) {
            val end = pending.indexOf("\n")
            if (end < 0) return
            onLine(pending.substring(0, end).trimEnd('\r'))
            pending.delete(0, end + 1)
        }
    }

    public companion object {
        private const val MAX_PACKET = 1 shl 20
        private const val MAX_CHUNK = 64 * 1024
        private const val SYNC_HEADER = 8
        private const val MAX_SHELL_OUTPUT = 4 shl 20
        private const val HOST_VERSION = 0x01000000
        private const val HOST_MAX_DATA = 0x40000
        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_PUBLIC_KEY = 3
        private val REMOTE_PATH = Regex("/[A-Za-z0-9._/-]{1,200}")

        /**
         * Connects [socket] (which the caller may have bound to a network) to [address] and authorizes with [signer].
         * A key the watch has not seen makes the watch ask its user; the wait is limited by [SdbOptions.confirmTimeoutMs].
         */
        public fun connect(socket: Socket, address: InetSocketAddress, signer: SdbSigner, keyName: String, options: SdbOptions = SdbOptions()): SdbSession {
            try {
                try {
                    socket.tcpNoDelay = true
                    socket.connect(address, options.connectTimeoutMs)
                } catch (e: IOException) {
                    throw SdbException(SdbError.UNREACHABLE, "Cannot connect to the watch", e)
                }
                socket.soTimeout = options.readTimeoutMs
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val banner = handshake(input, output, signer, keyName, socket, options)
                return SdbSession(socket, input, output, banner.first, banner.second)
            } catch (e: Exception) {
                try {
                    socket.close()
                } catch (_: IOException) {
                }
                throw if (e is SdbException) e else translate(e)
            }
        }

        private fun handshake(input: InputStream, output: OutputStream, signer: SdbSigner, keyName: String, socket: Socket, options: SdbOptions): Pair<String, Int> {
            fun send(command: Int, arg0: Int, arg1: Int, data: ByteArray) {
                output.write(SdbPacket(command, arg0, arg1, data).encode())
                output.flush()
            }
            send(SdbPacket.CNXN, HOST_VERSION, HOST_MAX_DATA, "host::\u0000".toByteArray(Charsets.US_ASCII))
            var reply = try {
                SdbPacket.read(input, MAX_PACKET)
            } catch (e: EOFException) {
                throw SdbException(SdbError.BUSY, "The watch closed the connection: another sdb host may be connected", e)
            } catch (e: SocketException) {
                // The watch may also reset the connection instead of closing it
                throw SdbException(SdbError.BUSY, "The watch reset the connection: another sdb host may be connected", e)
            }
            var signed = false
            var keySent = false
            while (reply.command == SdbPacket.AUTH && reply.arg0 == AUTH_TOKEN) {
                when {
                    !signed -> {
                        send(SdbPacket.AUTH, AUTH_SIGNATURE, 0, signer.sign(reply.data))
                        signed = true
                    }
                    !keySent -> {
                        send(SdbPacket.AUTH, AUTH_PUBLIC_KEY, 0, AdbPublicKey.encode(signer.publicKey, keyName))
                        keySent = true
                        socket.soTimeout = options.confirmTimeoutMs
                    }
                    else -> throw SdbException(SdbError.AUTH_REJECTED, "The watch did not accept the key")
                }
                reply = try {
                    SdbPacket.read(input, MAX_PACKET)
                } catch (e: SocketTimeoutException) {
                    throw SdbException(if (keySent) SdbError.AUTH_TIMEOUT else SdbError.TIMEOUT, "No answer from the watch", e)
                } catch (e: EOFException) {
                    throw SdbException(if (keySent) SdbError.AUTH_REJECTED else SdbError.DISCONNECTED, "The watch closed the connection", e)
                }
            }
            if (reply.command != SdbPacket.CNXN) throw SdbException(SdbError.PROTOCOL, "Unexpected answer from the watch")
            socket.soTimeout = options.readTimeoutMs
            val maxData = if (reply.arg1 > 0) minOf(reply.arg1, HOST_MAX_DATA) else 4096
            return String(reply.data, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' } to maxData
        }

        private fun translate(e: Exception): SdbException = when (e) {
            is SdbException -> e
            is SocketTimeoutException -> SdbException(SdbError.TIMEOUT, "The watch stopped answering", e)
            is EOFException -> SdbException(SdbError.DISCONNECTED, "The connection to the watch was closed", e)
            else -> if (e.message?.startsWith("Bad packet") == true) {
                SdbException(SdbError.PROTOCOL, "The watch sent something that is not sdb", e)
            } else {
                SdbException(SdbError.DISCONNECTED, "The connection to the watch broke", e)
            }
        }
    }

    private fun translate(e: IOException): SdbException = Companion.translate(e)
}
