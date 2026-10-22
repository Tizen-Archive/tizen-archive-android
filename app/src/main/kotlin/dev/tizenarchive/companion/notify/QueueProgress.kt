package dev.tizenarchive.companion.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.tizenarchive.companion.MainActivity
import dev.tizenarchive.companion.R
import dev.tizenarchive.companion.ui.ItemState

/** Where the queue is: item [index] of [total] (1-based), its name and what is happening to it. */
internal data class ProgressInfo(val index: Int, val total: Int, val name: String?, val state: ItemState)

/** Keeps the process alive and tells the user what the queue does while the app is not in front. */
internal interface QueueProgress {
    /** Called when the user cancels from the notification. */
    var onCancel: (() -> Unit)?

    fun update(info: ProgressInfo)

    fun stop()
}

internal class QueueProgressNotifier(private val context: Context) : QueueProgress {
    override var onCancel: (() -> Unit)?
        get() = QueueService.cancelHandler
        set(value) {
            QueueService.cancelHandler = value
        }

    private var running = false
    private var lastText: String? = null
    private var lastAt = 0L

    @Synchronized
    override fun update(info: ProgressInfo) {
        val text = describe(info)
        val now = System.currentTimeMillis()
        // A countdown changes every second; the system limits how often one app may update a notification
        if (running && text == lastText || running && now - lastAt < MIN_INTERVAL_MS && info.state is ItemState.Waiting) return
        lastText = text
        lastAt = now
        val notification = build(info, text)
        if (!running) {
            running = true
            QueueService.initial = notification
            ContextCompat.startForegroundService(context, Intent(context, QueueService::class.java))
        } else {
            Notifications.post(context, QueueService.ID, notification)
        }
    }

    @Synchronized
    override fun stop() {
        if (!running) return
        running = false
        lastText = null
        QueueService.initial = null
        context.stopService(Intent(context, QueueService::class.java))
    }

    private fun describe(info: ProgressInfo): String {
        val state = when (info.state) {
            ItemState.Queued -> R.string.state_queued
            ItemState.Reading -> R.string.state_reading
            ItemState.Building -> R.string.state_building
            ItemState.AwaitingConfirm -> R.string.state_confirm
            else -> null
        }
        val detail = when (val s = info.state) {
            is ItemState.Downloading -> context.getString(R.string.state_downloading, s.percent)
            is ItemState.Waiting -> context.getString(R.string.state_waiting, s.secondsLeft)
            is ItemState.Uploading -> context.getString(R.string.state_uploading, s.percent)
            is ItemState.InstallingOnWatch ->
                s.percent?.let { context.getString(R.string.state_watch_installing_percent, it) } ?: context.getString(R.string.state_watch_installing)
            else -> state?.let(context::getString).orEmpty()
        }
        return listOfNotNull(info.name, detail.ifEmpty { null }).joinToString("\n")
    }

    private fun build(info: ProgressInfo, text: String): Notification {
        Notifications.ensureChannels(context)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            context, 1, Intent(context, QueueService::class.java).setAction(QueueService.ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (info.total > 1) {
            context.getString(R.string.notification_progress_many, info.index, info.total)
        } else {
            context.getString(R.string.notification_progress)
        }
        return NotificationCompat.Builder(context, Notifications.PROGRESS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_watch)
            .setContentTitle(title)
            .setContentText(text.lineSequence().last())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.cancel), cancel)
            .build()
    }

    private companion object {
        const val MIN_INTERVAL_MS = 1000L
    }
}

/** Channels and the permission check shared by the queue notifications. */
internal object Notifications {
    const val PROGRESS_CHANNEL = "progress"
    const val CONFIRM_CHANNEL = "confirm"

    fun allowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun post(context: Context, id: Int, notification: Notification) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    fun ensureChannels(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannel(PROGRESS_CHANNEL, context.getString(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CONFIRM_CHANNEL, context.getString(R.string.channel_confirm), NotificationManager.IMPORTANCE_HIGH),
        )
    }
}
