package dev.tizenarchive.companion.sdb

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** A just-enough sdbd on loopback: authorization with key confirmation, `shell:` and `sync:` services. Written from the protocol, not from the client. */
class FakeSdbd(val maxData: Int = 262_144) : AutoCloseable {
    private val server = ServerSocket(0, 20, InetAddress.getLoopbackAddress())
    val address = InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort)

    /** The watch closes every connection at once, as when another host is connected. */
    @Volatile var busy = false

    /** A busy watch resets the connection instead of closing it politely (RST, as the real one does for some clients). */
    @Volatile var busyReset = false

    /** The user taps Allow on the watch; false means Deny (the watch closes the connection). */
    @Volatile var allowNewKeys = true

    /** How long the user takes to confirm. */
    @Volatile var confirmDelayMs = 0L

    /** Sends the last packet of a closed sync service again, as the real watch does. */
    @Volatile var staleClose = false

    @Volatile var sendGarbage = false

    val confirmations = AtomicInteger()
    val shells = ConcurrentHashMap<String, String>()
    val shellChunk = AtomicInteger(Int.MAX_VALUE)
    val files = ConcurrentHashMap<String, ByteArray>()
    val modes = ConcurrentHashMap<String, Int>()
    val commands = CopyOnWriteArrayList<String>()
    val writeSizes = CopyOnWriteArrayList<Int>()
    val keyNames = CopyOnWriteArrayList<String>()
    private val trusted = CopyOnWriteArrayList<java.security.PublicKey>()
    private val sockets = CopyOnWriteArrayList<Socket>()

    init {
        thread(isDaemon = true, name = "fake-sdbd") {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: java.io.IOException) {
                    return@thread
                }
                sockets += socket
                thread(isDaemon = true) { runCatching { serve(socket) }; runCatching { socket.close() } }
            }
        }
    }

    override fun close() {
        server.close()
        sockets.forEach { runCatching { it.close() } }
    }

    private fun serve(socket: Socket) {
        if (busy) {
            if (busyReset) socket.setSoLinger(true, 0)
            return
        }
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        fun send(command: Int, a0: Int, a1: Int, data: ByteArray = ByteArray(0)) {
            output.write(SdbPacket(command, a0, a1, data).encode())
            output.flush()
        }
        if (sendGarbage) {
            output.write(ByteArray(24) { 7 })
            output.flush()
            return
        }
        val hello = SdbPacket.read(input, 1 shl 20)
        require(hello.command == SdbPacket.CNXN && String(hello.data).startsWith("host::"))
        var token = ByteArray(20) { (it * 13 + 1).toByte() }
        send(SdbPacket.AUTH, 1, 0, token)
        while (true) {
            val packet = SdbPacket.read(input, 1 shl 20)
            require(packet.command == SdbPacket.AUTH)
            when (packet.arg0) {
                2 -> if (trusted.any { verify(it, token, packet.data) }) {
                    send(SdbPacket.CNXN, 0x01000000, maxData, "device::SM-R800::0\u0000".toByteArray())
                    break
                } else {
                    token = ByteArray(20) { (it * 7 + 3).toByte() }
                    send(SdbPacket.AUTH, 1, 0, token)
                }
                3 -> {
                    confirmations.incrementAndGet()
                    if (!allowNewKeys) return
                    Thread.sleep(confirmDelayMs)
                    val (key, name) = parseKey(packet.data)
                    keyNames += name
                    trusted += key
                    send(SdbPacket.CNXN, 0x01000000, maxData, "device::SM-R800::0\u0000".toByteArray())
                    break
                }
                else -> return
            }
        }
        services(input, ::send)
    }

    private fun services(input: InputStream, send: (Int, Int, Int, ByteArray) -> Unit) {
        var remoteIds = 100
        var syncPath: String? = null
        val received = ByteArrayOutputStream()
        var syncRemote = -1
        var syncLocal = -1
        while (true) {
            val packet = try {
                SdbPacket.read(input, 1 shl 20)
            } catch (_: EOFException) {
                return
            }
            when (packet.command) {
                SdbPacket.OPEN -> {
                    val service = String(packet.data).trimEnd('\u0000')
                    val remote = ++remoteIds
                    send(SdbPacket.OKAY, remote, packet.arg0, ByteArray(0))
                    if (service.startsWith("shell:")) {
                        val command = service.removePrefix("shell:")
                        commands += command
                        val out = (shells[command] ?: "").toByteArray()
                        var offset = 0
                        while (offset < out.size) {
                            val n = minOf(shellChunk.get(), out.size - offset)
                            send(SdbPacket.WRTE, remote, packet.arg0, out.copyOfRange(offset, offset + n))
                            offset += n
                        }
                        send(SdbPacket.CLSE, remote, packet.arg0, ByteArray(0))
                    } else if (service == "sync:") {
                        syncRemote = remote
                        syncLocal = packet.arg0
                        syncPath = null
                        received.reset()
                    }
                }
                SdbPacket.WRTE -> {
                    send(SdbPacket.OKAY, packet.arg1, packet.arg0, ByteArray(0))
                    writeSizes += packet.data.size
                    val id = String(packet.data, 0, 4)
                    val length = ByteBuffer.wrap(packet.data, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
                    when (id) {
                        "SEND" -> {
                            val target = String(packet.data, 8, length)
                            syncPath = target.substringBeforeLast(',')
                            modes[syncPath] = target.substringAfterLast(',').toInt()
                        }
                        "DATA" -> received.write(packet.data, 8, length)
                        "DONE" -> {
                            files[syncPath!!] = received.toByteArray()
                            send(SdbPacket.WRTE, syncRemote, syncLocal, "OKAY\u0000\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1))
                        }
                        "QUIT" -> {
                            send(SdbPacket.CLSE, syncRemote, syncLocal, ByteArray(0))
                            if (staleClose) send(SdbPacket.CLSE, syncRemote, syncLocal, ByteArray(0))
                        }
                    }
                }
            }
        }
    }

    private fun verify(key: java.security.PublicKey, token: ByteArray, signature: ByteArray): Boolean {
        val prefix = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
        val verifier = Signature.getInstance("NONEwithRSA")
        verifier.initVerify(key)
        verifier.update(prefix)
        verifier.update(token)
        return verifier.verify(signature)
    }

    /** Reads the Android RSAPublicKey struct without the client's help. */
    private fun parseKey(data: ByteArray): Pair<java.security.PublicKey, String> {
        val text = String(data, Charsets.US_ASCII).trimEnd('\u0000')
        val space = text.indexOf(' ')
        val struct = Base64.getDecoder().decode(text.substring(0, space))
        val buffer = ByteBuffer.wrap(struct).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.getInt() == 64)
        buffer.getInt()
        val modulus = ByteArray(256).also { buffer.get(it) }.reversedArray()
        buffer.position(buffer.position() + 256)
        val exponent = buffer.getInt()
        val key = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(BigInteger(1, modulus), BigInteger.valueOf(exponent.toLong())))
        return key to text.substring(space + 1)
    }
}
