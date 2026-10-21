package dev.tizenarchive.companion

import java.io.InputStream

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
