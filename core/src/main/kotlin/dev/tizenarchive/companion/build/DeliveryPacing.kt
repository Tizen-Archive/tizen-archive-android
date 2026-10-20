package dev.tizenarchive.companion.build

import java.time.Duration

/**
 * Gear Plugin copies every package to one temporary file and sends it over Bluetooth, one at a time, and it
 * silently drops requests that arrive while it is busy. Measured on a Galaxy Watch (SM-R800): a few seconds
 * apart loses deliveries, 14 seconds apart delivered all of 14 small packages, a 20 MB package needed
 * about 140 seconds (about 160 KB/s plus roughly 12 seconds for the installation on the watch).
 */
public object DeliveryPacing {
    private val BASE: Duration = Duration.ofSeconds(15)
    private const val ASSUMED_BYTES_PER_SECOND = 120_000L

    /** How long to wait after a companion was installed before installing the next one. */
    public fun delayAfter(packageSizeBytes: Long): Duration {
        require(packageSizeBytes >= 0) { "Negative size" }
        return BASE.plusSeconds(packageSizeBytes / ASSUMED_BYTES_PER_SECOND)
    }
}
