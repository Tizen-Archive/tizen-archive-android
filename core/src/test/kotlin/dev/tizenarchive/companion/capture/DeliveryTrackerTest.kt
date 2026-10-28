package dev.tizenarchive.companion.capture

import dev.tizenarchive.companion.sdb.RemoteEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeliveryTrackerTest {
    private val store = "tizenapp_1793213454602_samsungapps-000003107455-395907-100100006.wgt"

    private fun file(name: String, size: Long) = RemoteEntry(name, size, 0x81a4, 0)

    @Test
    fun `a store delivery is ready once it reaches the size in its name`() {
        val tracker = DeliveryTracker()
        val first = tracker.observe(listOf(file(store, 100_000)))
        assertTrue(first.ready.isEmpty())
        assertEquals(listOf(Delivery(store, 100_000, 395_907)), first.receiving)
        assertEquals(listOf(Delivery(store, 395_907, 395_907)), tracker.observe(listOf(file(store, 395_907))).ready)
        // Reported once while it stays
        assertTrue(tracker.observe(listOf(file(store, 395_907))).ready.isEmpty())
    }

    @Test
    fun `a store delivery seen complete at once is ready at once`() {
        assertEquals(1, DeliveryTracker().observe(listOf(file(store, 395_907))).ready.size)
    }

    @Test
    fun `a companion delivery is ready when its size holds between two listings`() {
        val tracker = DeliveryTracker()
        assertTrue(tracker.observe(listOf(file("watcher.tpk", 10))).ready.isEmpty())
        assertTrue(tracker.observe(listOf(file("watcher.tpk", 20))).ready.isEmpty())
        assertEquals(listOf("watcher.tpk"), tracker.observe(listOf(file("watcher.tpk", 20))).ready.map { it.name })
    }

    @Test
    fun `the same name delivered again after it was gone is ready again`() {
        val tracker = DeliveryTracker()
        assertEquals(1, tracker.observe(listOf(file(store, 395_907))).ready.size)
        tracker.observe(emptyList())
        assertEquals(1, tracker.observe(listOf(file(store, 395_907))).ready.size)
    }

    @Test
    fun `a retried name is offered again`() {
        val tracker = DeliveryTracker()
        tracker.observe(listOf(file("watcher.wgt", 5)))
        assertEquals(1, tracker.observe(listOf(file("watcher.wgt", 5))).ready.size)
        tracker.retry("watcher.wgt")
        assertTrue(tracker.observe(listOf(file("watcher.wgt", 5))).ready.isEmpty())
        assertEquals(1, tracker.observe(listOf(file("watcher.wgt", 5))).ready.size)
    }

    @Test
    fun `other files and directories of the service are ignored`() {
        val tracker = DeliveryTracker()
        val entries = listOf(
            file("HostStatus.xml", 1_795),
            file("watcher.zip", 3),
            file("../watcher.wgt", 3),
            RemoteEntry("watcher.wgt", 4096, 0x41ed, 0),
            file("tizenapp_1_samsungapps-2-3-4.tpk", 3),
            file("tizenapp_1_samsungapps-2-3-4.wgt/x", 3),
        )
        repeat(2) {
            val observation = tracker.observe(entries)
            assertTrue(observation.ready.isEmpty() && observation.receiving.isEmpty())
        }
    }

    @Test
    fun `names are recognized strictly`() {
        assertTrue(DeliveryTracker.isDelivery(store))
        assertTrue(DeliveryTracker.isDelivery("watcher.wgt"))
        assertFalse(DeliveryTracker.isDelivery("watcher.wgt; rm -rf /"))
        assertFalse(DeliveryTracker.isDelivery("tizenapp_x_samsungapps-1-2-3.wgt"))
        assertEquals(395_907L, DeliveryTracker.expectedSize(store))
        assertNull(DeliveryTracker.expectedSize("watcher.wgt"))
    }
}
