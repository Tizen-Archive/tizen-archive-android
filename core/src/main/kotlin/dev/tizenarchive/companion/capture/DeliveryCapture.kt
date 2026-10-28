package dev.tizenarchive.companion.capture

import dev.tizenarchive.companion.sdb.SdbService
import dev.tizenarchive.companion.signature.SignatureAssessment
import dev.tizenarchive.companion.signature.SignatureInspector
import dev.tizenarchive.companion.tizen.InvalidTizenPackageException
import dev.tizenarchive.companion.tizen.TizenPackageInfo
import dev.tizenarchive.companion.tizen.TizenPackageReader
import java.io.File

public sealed interface CaptureResult {
    /**
     * [file] is a copy in the work directory that the caller moves or deletes. [signature] predicts whether other watches accept
     * the package; null when its signature cannot be read.
     */
    public class Captured(public val file: File, public val info: TizenPackageInfo, public val signature: SignatureAssessment?) : CaptureResult

    /** The watch's installer removed the file before it could be copied. */
    public class Missed(public val name: String) : CaptureResult

    /** The copy is not a Tizen package. */
    public class Invalid(public val name: String) : CaptureResult
}

/**
 * Copies packages that Galaxy Wearable delivers to the watch, while the watch keeps them for its installer (seconds for small
 * packages, so [poll] has to run every couple of seconds). Only reads: nothing on the watch is changed.
 */
public class DeliveryCapture(
    private val service: SdbService,
    private val workDir: File,
    private val directory: String = DELIVERY_DIR,
) {
    private val tracker = DeliveryTracker()

    /**
     * One look at the directory; [onReceiving] sees the files still arriving, [onResult] each delivery as soon as it is copied,
     * so that a connection breaking on a later file does not lose the earlier ones.
     */
    public fun poll(onReceiving: (List<Delivery>) -> Unit = {}, onResult: (CaptureResult) -> Unit) {
        val observation = tracker.observe(service.list(directory))
        onReceiving(observation.receiving)
        observation.ready.forEach { delivery -> take(delivery)?.let(onResult) }
    }

    /** Null when a companion file turned out incomplete: it is offered again by the next look. */
    private fun take(delivery: Delivery): CaptureResult? {
        workDir.mkdirs()
        val copy = File.createTempFile("capture", ".part", workDir)
        val found = try {
            copy.outputStream().buffered().use { service.pull("$directory/${delivery.name}", it, delivery.expectedSize ?: MAX_SIZE) }
        } catch (e: Exception) {
            copy.delete()
            throw e
        }
        if (!found) {
            copy.delete()
            return CaptureResult.Missed(delivery.name)
        }
        val info = try {
            TizenPackageReader.read(copy)
        } catch (e: InvalidTizenPackageException) {
            copy.delete()
            if (delivery.expectedSize == null) {
                tracker.retry(delivery.name)
                return null
            }
            return CaptureResult.Invalid(delivery.name)
        }
        return CaptureResult.Captured(copy, info, SignatureInspector.inspect(copy))
    }

    public companion object {
        /** Where the watch's Wearable Manager Service keeps a delivered package; the shell may not list it, the file service may. */
        public const val DELIVERY_DIR: String = "/opt/usr/home/owner/apps_rw/com.samsung.w-manager-service/data"
        private const val MAX_SIZE = 512L shl 20
    }
}
