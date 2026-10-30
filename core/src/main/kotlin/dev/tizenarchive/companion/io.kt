package dev.tizenarchive.companion

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** `InputStream.readNBytes(int)` exists only since Android 13 (API 33), and the app supports API 26. */
internal fun InputStream.readUpTo(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var left = limit
    while (left > 0) {
        val n = read(buffer, 0, minOf(buffer.size, left))
        if (n < 0) break
        out.write(buffer, 0, n)
        left -= n
    }
    return out.toByteArray()
}

/**
 * Writes [input] to [destination], stopping once it runs past [limit], and returns how many bytes came (more than [limit] when it did)
 * and their SHA-256 in lower-case hex.
 */
internal fun copyHashed(input: InputStream, destination: File, limit: Long, progress: (Long) -> Unit): Pair<Long, String> {
    val digest = MessageDigest.getInstance("SHA-256")
    var total = 0L
    destination.outputStream().buffered().use { output ->
        val buffer = ByteArray(COPY_BUFFER)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) break
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
            progress(total)
        }
    }
    return total to digest.digest().joinToString("") { "%02x".format(it) }
}

private const val COPY_BUFFER = 64 * 1024
