package dev.tizenarchive.companion.build

import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * One companion per Tizen package id: the Android package name is stable, so installing the same Tizen
 * package again replaces the companion (an update), and Gear Plugin delivers the package to the watch again.
 */
public object CompanionNaming {
    private const val PREFIX = "dev.tizenarchive.companion.p"
    private val EPOCH: Instant = Instant.parse("2026-01-01T00:00:00Z")

    public fun androidPackageName(tizenPackageId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(tizenPackageId.toByteArray(Charsets.UTF_8))
        return PREFIX + digest.take(10).joinToString("") { "%02x".format(it) }
    }

    /** Seconds since 2026-01-01: grows with every build, so a reinstall is always an upgrade. */
    public fun versionCode(now: Instant): Int {
        val seconds = Duration.between(EPOCH, now).seconds
        require(seconds in 1..Int.MAX_VALUE) { "Clock is outside the supported range: $now" }
        return seconds.toInt()
    }
}
