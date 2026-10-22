package dev.tizenarchive.companion.wifi

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
}
