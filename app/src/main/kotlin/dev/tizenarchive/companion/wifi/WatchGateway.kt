package dev.tizenarchive.companion.wifi

import dev.tizenarchive.companion.capture.CaptureResult
import dev.tizenarchive.companion.capture.Delivery
import dev.tizenarchive.companion.sdb.FoundWatch
import dev.tizenarchive.companion.sdb.InstallProgress
import dev.tizenarchive.companion.sdb.PackageType
import dev.tizenarchive.companion.sdb.SdbError
import dev.tizenarchive.companion.sdb.WatchPackage
import java.io.File
import java.net.InetAddress

/** How a package is delivered to the watch. */
internal enum class InstallMode { COMPANION, WIFI }

/** The package a file contains; [version] is null when its manifest names none. */
internal class PackageIdentity(val id: String, val version: String?)

internal sealed interface WatchOutcome {
    data object Installed : WatchOutcome

    /** The watch already has this version or a newer one, so nothing was sent. */
    data class AlreadyInstalled(val version: String) : WatchOutcome

    /** The watch answered but refused the package; [code] and [message] come from `pkgcmd`. */
    data class Refused(val code: Int?, val message: String?) : WatchOutcome

    data class Problem(val error: SdbError) : WatchOutcome

    /** The phone is not connected to a Wi-Fi network. */
    data object NoWifi : WatchOutcome
}

internal sealed interface WatchListOutcome {
    data class Listed(val packages: List<WatchPackage>) : WatchListOutcome

    data class Problem(val error: SdbError) : WatchListOutcome

    data object NoWifi : WatchListOutcome
}

internal sealed interface WatchRemoveOutcome {
    data object Removed : WatchRemoveOutcome

    data class Refused(val code: Int?, val message: String?) : WatchRemoveOutcome

    data class Problem(val error: SdbError) : WatchRemoveOutcome

    data object NoWifi : WatchRemoveOutcome
}

internal sealed interface FindOutcome {
    data class Found(val watches: List<FoundWatch>) : FindOutcome

    data object NoWifi : FindOutcome

    /** The Wi-Fi network is not an IPv4 private one, so there is nothing sensible to scan. */
    data object NoSubnet : FindOutcome
}

internal sealed interface CaptureEvent {
    /** Connected; the delivery directory is looked at every couple of seconds. */
    data object Watching : CaptureEvent

    /** Files still arriving at the watch, after each look (empty when none). */
    data class Receiving(val deliveries: List<Delivery>) : CaptureEvent

    data class Result(val result: CaptureResult) : CaptureEvent

    /** The connection failed or broke; another attempt follows. */
    data class Problem(val error: SdbError) : CaptureEvent

    /** The phone is not connected to a Wi-Fi network; another attempt follows. */
    data object NoWifi : CaptureEvent
}

/** Installs a package on a watch over Wi-Fi; [WifiWatchGateway] is the real one, tests use fakes. */
internal interface WatchGateway {
    /** When [skipIfInstalled] is given and the watch already has that version or a newer one, nothing is sent. */
    suspend fun install(
        address: InetAddress,
        file: File,
        type: PackageType,
        skipIfInstalled: PackageIdentity?,
        progress: (InstallProgress) -> Unit,
    ): WatchOutcome

    suspend fun list(address: InetAddress): WatchListOutcome

    /** Removes a package; the application's data on the watch is lost. */
    suspend fun remove(address: InetAddress, packageId: String): WatchRemoveOutcome

    /** Looks for watches in the Wi-Fi network of the phone. */
    suspend fun find(): FindOutcome

    /**
     * Copies into [workDir] the packages that Galaxy Wearable delivers to the watch, reconnecting when the connection breaks.
     * Returns only by cancellation. The session holds the watch: other calls fail as busy meanwhile.
     */
    suspend fun capture(address: InetAddress, workDir: File, onEvent: suspend (CaptureEvent) -> Unit)
}
