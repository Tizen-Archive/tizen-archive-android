package dev.tizenarchive.companion.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import dev.tizenarchive.companion.capture.CaptureResult
import dev.tizenarchive.companion.capture.Delivery
import dev.tizenarchive.companion.capture.DeliveryCapture
import dev.tizenarchive.companion.catalog.VersionCompare
import dev.tizenarchive.companion.sdb.InstallProgress
import dev.tizenarchive.companion.sdb.PackageType
import dev.tizenarchive.companion.sdb.SdbError
import dev.tizenarchive.companion.sdb.SdbException
import dev.tizenarchive.companion.sdb.SdbSession
import dev.tizenarchive.companion.sdb.TcpSdbProbe
import dev.tizenarchive.companion.sdb.WatchInstaller
import dev.tizenarchive.companion.sdb.WatchScanner
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** Talks sdb to the watch over the phone's Wi-Fi network, never over mobile data. */
internal class WifiWatchGateway(
    private val context: Context,
    private val keys: KeystoreSdbKey,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : WatchGateway {
    override suspend fun install(
        address: InetAddress,
        file: File,
        type: PackageType,
        skipIfInstalled: PackageIdentity?,
        progress: (InstallProgress) -> Unit,
    ): WatchOutcome = withWatch(address, WatchOutcome.NoWifi, { WatchOutcome.Problem(it) }) { session ->
        val installer = WatchInstaller(session)
        val wanted = skipIfInstalled?.version
        if (skipIfInstalled != null && wanted != null) {
            val there = installer.installedVersion(skipIfInstalled.id)
            if (there != null && VersionCompare.compare(there, wanted) >= 0) return@withWatch WatchOutcome.AlreadyInstalled(there)
        }
        val result = installer.install(file, type, progress)
        if (result.success) WatchOutcome.Installed else WatchOutcome.Refused(result.code, result.message)
    }

    override suspend fun list(address: InetAddress): WatchListOutcome =
        withWatch(address, WatchListOutcome.NoWifi, { WatchListOutcome.Problem(it) }) { WatchListOutcome.Listed(WatchInstaller(it).list()) }

    override suspend fun remove(address: InetAddress, packageId: String): WatchRemoveOutcome =
        withWatch(address, WatchRemoveOutcome.NoWifi, { WatchRemoveOutcome.Problem(it) }) { session ->
            val result = WatchInstaller(session).remove(packageId)
            if (result.success) WatchRemoveOutcome.Removed else WatchRemoveOutcome.Refused(result.code, result.message)
        }

    override suspend fun capture(address: InetAddress, workDir: File, onEvent: suspend (CaptureEvent) -> Unit) = withContext(io) {
        while (true) {
            val network = wifiNetwork()
            if (network == null) {
                onEvent(CaptureEvent.NoWifi)
                delay(CAPTURE_RETRY_MS)
                continue
            }
            var problem = SdbError.DISCONNECTED
            try {
                session(network, address) { session ->
                    val capture = DeliveryCapture(session, workDir)
                    onEvent(CaptureEvent.Watching)
                    while (true) {
                        var receiving = emptyList<Delivery>()
                        val results = mutableListOf<CaptureResult>()
                        try {
                            runInterruptible { capture.poll({ receiving = it }) { results += it } }
                        } catch (e: SdbException) {
                            // What was copied before the connection broke is gone from the watch by the time it comes back
                            results.forEach { onEvent(CaptureEvent.Result(it)) }
                            throw e
                        }
                        onEvent(CaptureEvent.Receiving(receiving))
                        results.forEach { onEvent(CaptureEvent.Result(it)) }
                        delay(CAPTURE_POLL_MS)
                    }
                }
            } catch (e: SdbException) {
                problem = e.error
            } catch (e: IOException) {
                problem = SdbError.UNREACHABLE
            }
            // Stopping closes the socket, which surfaces as a broken connection: that is not worth reporting
            ensureActive()
            onEvent(CaptureEvent.Problem(problem))
            delay(CAPTURE_RETRY_MS)
        }
    }

    /** Runs [block] in an authorized session over the Wi-Fi network; cancelling the caller closes the socket, which ends a blocking call at once. */
    private suspend fun <T> withWatch(address: InetAddress, noWifi: T, problem: (SdbError) -> T, block: (SdbSession) -> T): T = withContext(io) {
        val network = wifiNetwork() ?: return@withContext noWifi
        try {
            session(network, address) { block(it) }
        } catch (e: SdbException) {
            problem(e.error)
        } catch (e: IOException) {
            problem(SdbError.UNREACHABLE)
        }
    }

    private suspend fun <T> session(network: Network, address: InetAddress, block: suspend (SdbSession) -> T): T {
        val socket = Socket()
        try {
            network.bindSocket(socket)
        } catch (e: IOException) {
            socket.close()
            throw e
        }
        val session = SdbSession.connect(socket, InetSocketAddress(address, SDB_PORT), keys.signer(), keyName())
        return coroutineScope {
            val closer = launch {
                try {
                    awaitCancellation()
                } finally {
                    session.close()
                }
            }
            try {
                block(session)
            } finally {
                closer.cancel()
            }
        }
    }

    override suspend fun find(): FindOutcome {
        val network = wifiNetwork() ?: return FindOutcome.NoWifi
        val own = context.getSystemService(ConnectivityManager::class.java)?.getLinkProperties(network)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address } ?: return FindOutcome.NoSubnet
        val candidates = WatchScanner.candidates(own.address, own.prefixLength)
        if (candidates.isEmpty()) return FindOutcome.NoSubnet
        val scanner = WatchScanner(TcpSdbProbe(socketFactory = { Socket().also(network::bindSocket) }))
        return FindOutcome.Found(runInterruptible(io) { scanner.scan(candidates) })
    }

    /**
     * The active network is mobile data whenever the watch's Wi-Fi has no internet, so the Wi-Fi network is looked up among all of
     * them; `allNetworks` is deprecated, but it is the only synchronous way that works on Android 8.
     */
    @Suppress("DEPRECATION")
    private fun wifiNetwork(): Network? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        // A VPN running over Wi-Fi reports the Wi-Fi transport too, but its sockets leave with the VPN's address and never reach the LAN.
        return manager.allNetworks.firstOrNull {
            val capabilities = manager.getNetworkCapabilities(it)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
    }

    /** The name the watch shows next to the key; plain ASCII because the key line is sent as such. */
    private fun keyName(): String = "${Build.MODEL}@tizen-companion".map { if (it.code in 0x21..0x7e) it else '_' }.joinToString("")

    private companion object {
        const val SDB_PORT = 26101

        /** A small package stays on the watch about 3 s after it arrived. */
        const val CAPTURE_POLL_MS = 1_500L
        const val CAPTURE_RETRY_MS = 5_000L
    }
}
