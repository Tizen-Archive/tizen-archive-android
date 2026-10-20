package dev.tizenarchive.companion.install

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

/** Installs one APK through a PackageInstaller session; the system asks the user to confirm. */
internal class PackageInstallerGateway(private val context: Context) {
    private val counter = AtomicInteger()

    suspend fun install(apk: File): InstallOutcome = suspendCancellableCoroutine { continuation ->
        val installer = context.packageManager.packageInstaller
        val action = "${context.packageName}.INSTALL_RESULT.${counter.incrementAndGet()}"
        val sessionId = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                        if (confirm != null) {
                            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } else {
                            finish(InstallOutcome.Failed(status, "No confirmation screen"))
                        }
                    }
                    PackageInstaller.STATUS_SUCCESS -> {
                        val name = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME).orEmpty()
                        finish(InstallOutcome.Installed(name))
                    }
                    PackageInstaller.STATUS_FAILURE_ABORTED -> finish(InstallOutcome.Declined)
                    else -> finish(InstallOutcome.Failed(status, message))
                }
            }

            private fun finish(outcome: InstallOutcome) {
                runCatching { context.unregisterReceiver(this) }
                if (continuation.isActive) continuation.resume(outcome) { _, _, _ -> }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        continuation.invokeOnCancellation {
            runCatching { context.unregisterReceiver(receiver) }
            runCatching { installer.abandonSession(sessionId) }
        }

        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("companion.apk", 0, apk.length()).use { output ->
                        input.copyTo(output, BUFFER)
                        session.fsync(output)
                    }
                }
                val callback = Intent(action).setPackage(context.packageName)
                val pending = PendingIntent.getBroadcast(
                    context, sessionId, callback, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { context.unregisterReceiver(receiver) }
            runCatching { installer.abandonSession(sessionId) }
            if (continuation.isActive) continuation.resume(InstallOutcome.Failed(PackageInstaller.STATUS_FAILURE, e.message)) { _, _, _ -> }
        }
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}
