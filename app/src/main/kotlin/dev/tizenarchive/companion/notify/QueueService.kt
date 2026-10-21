package dev.tizenarchive.companion.notify

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat

/**
 * Only keeps the process in the foreground while a queue runs, so that Android does not freeze or kill it when the app
 * is not in front. The queue itself runs in the view model; this service does not do any work.
 */
internal class QueueService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelHandler?.invoke()
            return START_NOT_STICKY
        }
        val notification = initial ?: return stopNow()
        ServiceCompat.startForeground(this, ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return START_NOT_STICKY
    }

    /** Android 15 limits data-sync services to a few hours a day and calls this when the time is up. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        cancelHandler?.invoke()
        stopSelf()
    }

    private fun stopNow(): Int {
        // Started after the queue had already ended: a foreground service must still call startForeground
        stopSelf()
        return START_NOT_STICKY
    }

    companion object {
        const val ID = 2
        const val ACTION_CANCEL = "dev.tizenarchive.companion.CANCEL_QUEUE"

        @Volatile
        var initial: Notification? = null

        @Volatile
        var cancelHandler: (() -> Unit)? = null
    }
}
