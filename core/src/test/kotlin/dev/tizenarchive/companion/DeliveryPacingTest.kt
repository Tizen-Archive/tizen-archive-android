package dev.tizenarchive.companion

import dev.tizenarchive.companion.build.DeliveryPacing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class DeliveryPacingTest {
    @Test
    fun `small packages wait the base interval`() {
        assertEquals(Duration.ofSeconds(15), DeliveryPacing.delayAfter(20_000))
    }

    @Test
    fun `a 20 MB package waits longer than the measured delivery time`() {
        assertTrue(DeliveryPacing.delayAfter(20_000_000) >= Duration.ofSeconds(140))
    }

    @Test
    fun `wait grows with size`() {
        assertTrue(DeliveryPacing.delayAfter(50_000_000) > DeliveryPacing.delayAfter(10_000_000))
    }

    @Test
    fun `negative size is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { DeliveryPacing.delayAfter(-1) }
    }
}
