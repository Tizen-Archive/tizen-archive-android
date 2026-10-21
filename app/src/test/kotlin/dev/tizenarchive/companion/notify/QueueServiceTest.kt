package dev.tizenarchive.companion.notify

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tizenarchive.companion.ui.ItemState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class QueueServiceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        QueueService.initial = null
        QueueService.cancelHandler = null
    }

    @Test
    fun `the first update starts the service, later ones do not`() {
        val notifier = QueueProgressNotifier(app)
        notifier.update(ProgressInfo(1, 2, "Alpha", ItemState.Building))
        val started = shadowOf(app).nextStartedService
        assertEquals(QueueService::class.java.name, started.component?.className)
        assertNotNull(QueueService.initial)

        notifier.update(ProgressInfo(1, 2, "Alpha", ItemState.AwaitingConfirm))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun `stop ends the service and a new queue starts it again`() {
        val notifier = QueueProgressNotifier(app)
        notifier.update(ProgressInfo(1, 1, "Alpha", ItemState.Reading))
        shadowOf(app).clearStartedServices()
        notifier.stop()
        assertEquals(QueueService::class.java.name, shadowOf(app).nextStoppedService.component?.className)
        assertNull(QueueService.initial)

        notifier.update(ProgressInfo(1, 1, "Beta", ItemState.Reading))
        assertNotNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun `the service goes to the foreground with the prepared notification`() {
        val notifier = QueueProgressNotifier(app)
        notifier.update(ProgressInfo(1, 1, "Alpha", ItemState.Building))
        val service = Robolectric.buildService(QueueService::class.java, Intent(app, QueueService::class.java)).create().startCommand(0, 1).get()
        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertEquals(QueueService.ID, shadowOf(service).lastForegroundNotificationId)
    }

    @Test
    fun `a start without a queue stops the service at once`() {
        val service = Robolectric.buildService(QueueService::class.java, Intent(app, QueueService::class.java)).create().startCommand(0, 1).get()
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `the cancel action reaches the queue and removing the task does too`() {
        var cancelled = 0
        QueueService.cancelHandler = { cancelled++ }
        val cancel = Intent(app, QueueService::class.java).setAction(QueueService.ACTION_CANCEL)
        val service = Robolectric.buildService(QueueService::class.java, cancel).create().startCommand(0, 1).get()
        assertEquals(1, cancelled)
        service.onTaskRemoved(null)
        assertEquals(2, cancelled)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }
}
