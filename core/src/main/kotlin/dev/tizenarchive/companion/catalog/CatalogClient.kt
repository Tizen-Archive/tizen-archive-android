package dev.tizenarchive.companion.catalog

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

public enum class CatalogError { NOT_FOUND, NETWORK, BAD_RESPONSE, SIZE_MISMATCH, CHECKSUM_MISMATCH, TOO_LARGE }

public class CatalogException(public val error: CatalogError, message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Talks to the catalog. The server address is fixed by the app and never comes from a link; only HTTPS is
 * accepted unless [allowCleartext] is set (tests and local development).
 */
public class CatalogClient(
    baseUrl: String,
    private val userAgent: String,
    private val allowCleartext: Boolean = false,
) {
    private val base: URI = URI(baseUrl.trimEnd('/')).also {
        require(it.scheme == "https" || (allowCleartext && it.scheme == "http")) { "Catalog URL must use HTTPS" }
    }
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** Resolves the latest version when [version] is null. */
    public fun version(packageId: String, version: String?): VersionInfo {
        require(InstallLinkParser.isIdentifier(packageId)) { "Invalid package id" }
        val resolved = version ?: getJson<PackageInfo>("/api/packages/$packageId").latestVersion
            ?: throw CatalogException(CatalogError.NOT_FOUND, "The package has no published versions")
        require(InstallLinkParser.isIdentifier(resolved)) { "Invalid version" }
        val info = getJson<VersionInfo>("/api/packages/$packageId/versions/$resolved")
        for (artifact in info.builds.flatMap { it.artifacts }) {
            if (artifact.sha256.length != 64 || !artifact.sha256.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                throw CatalogException(CatalogError.BAD_RESPONSE, "The catalog returned a malformed SHA-256")
            }
            if (artifact.size < 0) throw CatalogException(CatalogError.BAD_RESPONSE, "The catalog returned a negative size")
        }
        if (!InstallLinkParser.isIdentifier(info.packageId) || !InstallLinkParser.isIdentifier(info.version)) {
            throw CatalogException(CatalogError.BAD_RESPONSE, "The catalog returned an unexpected identifier")
        }
        return info
    }

    /** Downloads and verifies the artifact; the partial file is removed on any failure. */
    public fun download(artifact: Artifact, destination: File, progress: (Long) -> Unit = {}) {
        val path = artifact.downloadUrl ?: throw CatalogException(CatalogError.BAD_RESPONSE, "No download URL")
        if (artifact.size > MAX_ARTIFACT_BYTES) throw CatalogException(CatalogError.TOO_LARGE, "The package is too large")
        try {
            open(path).use { connection ->
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                connection.inputStream.use { input ->
                    destination.outputStream().buffered().use { output ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > artifact.size) break
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            progress(total)
                        }
                    }
                }
                if (total != artifact.size) {
                    throw CatalogException(CatalogError.SIZE_MISMATCH, "Expected ${artifact.size} bytes, got $total")
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(artifact.sha256, ignoreCase = true)) {
                    throw CatalogException(CatalogError.CHECKSUM_MISMATCH, "SHA-256 mismatch")
                }
            }
        } catch (e: Exception) {
            destination.delete()
            if (e is IOException) throw CatalogException(CatalogError.NETWORK, e.message ?: "Network error", e)
            throw e
        }
    }

    private inline fun <reified T> getJson(path: String): T {
        open(path, accept = "application/json").use { connection ->
            val bytes = try {
                connection.inputStream.use { it.readNBytes(MAX_JSON_BYTES + 1) }
            } catch (e: IOException) {
                throw CatalogException(CatalogError.NETWORK, e.message ?: "Network error", e)
            }
            if (bytes.size > MAX_JSON_BYTES) throw CatalogException(CatalogError.BAD_RESPONSE, "The response is too large")
            try {
                return json.decodeFromString<T>(bytes.toString(Charsets.UTF_8))
            } catch (e: SerializationException) {
                throw CatalogException(CatalogError.BAD_RESPONSE, "Unexpected response from the catalog", e)
            }
        }
    }

    /** Only paths on the catalog itself are followed; an absolute URL from a response is never fetched. */
    private fun open(path: String, accept: String? = null): Connection {
        if (!path.startsWith("/") || path.startsWith("//")) {
            throw CatalogException(CatalogError.BAD_RESPONSE, "The catalog returned an unexpected URL")
        }
        val url = URI(base.scheme, base.authority, base.path.trimEnd('/') + path, null, null).toURL()
        try {
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", userAgent)
            accept?.let { connection.setRequestProperty("Accept", it) }
            return when (val code = connection.responseCode) {
                200 -> Connection(connection)
                404 -> throw CatalogException(CatalogError.NOT_FOUND, "Not found")
                else -> throw CatalogException(CatalogError.NETWORK, "The catalog answered HTTP $code")
            }
        } catch (e: IOException) {
            throw CatalogException(CatalogError.NETWORK, e.message ?: "Network error", e)
        }
    }

    private class Connection(private val connection: HttpURLConnection) : AutoCloseable {
        val inputStream get() = connection.inputStream

        override fun close() = connection.disconnect()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_JSON_BYTES = 5 * 1024 * 1024
        const val MAX_ARTIFACT_BYTES = 512L * 1024 * 1024
        const val BUFFER = 64 * 1024
    }
}
