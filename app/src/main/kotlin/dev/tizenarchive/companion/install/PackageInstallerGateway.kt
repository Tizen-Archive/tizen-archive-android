package dev.tizenarchive.companion.install

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageInstaller
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.suspendCancellableCoroutine

internal sealed interface InstallOutcome {
    data class Installed(val packageName: String) : InstallOutcome

    /** The user declined the system confirmation. */
    data object Declined : InstallOutcome

    data class Failed(val status: Int, val message: String?) : InstallOutcome
}

internal sealed interface UninstallOutcome {
    data object Removed : UninstallOutcome

    /** The user declined the system confirmation. */
    data object Declined : UninstallOutcome

    data class Failed(val status: Int, val message: String?) : UninstallOutcome
}

/** The raw result PackageInstaller reports for a session or an uninstall. */
private class StatusEvent(val status: Int, val message: String?, val packageName: String?)

/** What the view model needs to install and remove helper apps; [PackageInstallerGateway] is the real one. */
internal interface Installer {
    suspend fun install(apk: File): InstallOutcome

    suspend fun uninstall(packageName: String): UninstallOutcome
}

/** Installs and removes helper apps through PackageInstaller; the system asks the user to confirm. */
internal class PackageInstallerGateway(private val context: Context) : Installer {
    private val counter = AtomicInteger()

    override suspend fun install(apk: File): InstallOutcome {
        val installer = context.packageManager.packageInstaller
        val sessionId = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        val event = awaitStatus(onCancel = { runCatching { installer.abandonSession(sessionId) } }) { sender, fail ->
            try {
                installer.openSession(sessionId).use { session ->
                    apk.inputStream().use { input ->
                        session.openWrite("companion.apk", 0, apk.length()).use { output ->
                            input.copyTo(output, BUFFER)
                            session.fsync(output)
                        }
                    }
                    session.commit(sender)
                }
            } catch (e: Exception) {
                runCatching { installer.abandonSession(sessionId) }
                fail(e)
            }
        }
        return when (event.status) {
            PackageInstaller.STATUS_SUCCESS -> InstallOutcome.Installed(event.packageName.orEmpty())
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallOutcome.Declined
            else -> InstallOutcome.Failed(event.status, event.message)
        }
    }

    override suspend fun uninstall(packageName: String): UninstallOutcome {
        val installer = context.packageManager.packageInstaller
        val event = awaitStatus(onCancel = {}) { sender, fail ->
            try {
                installer.uninstall(packageName, sender)
            } catch (e: Exception) {
                fail(e)
            }
        }
        return when (event.status) {
            PackageInstaller.STATUS_SUCCESS -> UninstallOutcome.Removed
            PackageInstaller.STATUS_FAILURE_ABORTED -> UninstallOutcome.Declined
            else -> UninstallOutcome.Failed(event.status, event.message)
        }
    }

    /**
     * Runs [start] with an IntentSender that PackageInstaller reports to, shows the system confirmation when it is
     * asked for, and returns the final status. [start] calls `fail` when it cannot even begin.
     */
    private suspend fun awaitStatus(
        onCancel: () -> Unit,
        start: (IntentSender, (Exception) -> Unit) -> Unit,
    ): StatusEvent = suspendCancellableCoroutine { continuation ->
        val action = "${context.packageName}.INSTALLER_STATUS.${counter.incrementAndGet()}"

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    if (confirm != null) {
                        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } else {
                        finish(StatusEvent(PackageInstaller.STATUS_FAILURE, "No confirmation screen", null))
                    }
                    return
                }
                finish(
                    StatusEvent(
                        status,
                        intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                        intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME),
                    ),
                )
            }

            private fun finish(event: StatusEvent) {
                runCatching { context.unregisterReceiver(this) }
                if (continuation.isActive) continuation.resume(event) { _, _, _ -> }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        continuation.invokeOnCancellation {
            runCatching { context.unregisterReceiver(receiver) }
            onCancel()
        }

        val id = counter.incrementAndGet()
        val callback = Intent(action).setPackage(context.packageName)
        val pending = PendingIntent.getBroadcast(context, id, callback, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        start(pending.intentSender) { e ->
            runCatching { context.unregisterReceiver(receiver) }
            if (continuation.isActive) {
                continuation.resume(StatusEvent(PackageInstaller.STATUS_FAILURE, e.message, null)) { _, _, _ -> }
            }
        }
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}
