package dev.tizenarchive.companion.capture

import dev.tizenarchive.companion.sdb.RemoteEntry

/** A package file the watch is receiving or has received; [expectedSize] is known only for Galaxy Store deliveries. */
public data class Delivery(val name: String, val size: Long, val expectedSize: Long?)

/** What one listing of the delivery directory showed. */
public class Observation(public val ready: List<Delivery>, public val receiving: List<Delivery>)

/**
 * Follows the directory where the watch keeps a package delivered from the phone until its installer is done with it.
 * Galaxy Store names the file `tizenapp_<ms>_samsungapps-<product>-<size>-<n>.wgt` (also for tpk packages), so it is complete
 * when it reaches that size; a companion's file `watcher.<wgt|tpk>` is complete when its size stays the same between two listings.
 * Other files of the directory are never touched. Each appearance of a file is reported ready once.
 */
public class DeliveryTracker {
    private var previous: Map<String, Long> = emptyMap()
    private val taken = mutableSetOf<String>()

    public fun observe(entries: List<RemoteEntry>): Observation {
        val current = entries.filter { it.isFile && isDelivery(it.name) }.associate { it.name to it.size }
        // A name that disappeared may come back with the next delivery
        taken.retainAll(current.keys)
        val ready = mutableListOf<Delivery>()
        val receiving = mutableListOf<Delivery>()
        for ((name, size) in current) {
            if (name in taken) continue
            val expected = expectedSize(name)
            val complete = if (expected != null) size == expected else size > 0 && previous[name] == size
            val delivery = Delivery(name, size, expected)
            if (complete) {
                taken += name
                ready += delivery
            } else {
                receiving += delivery
            }
        }
        previous = current
        return Observation(ready, receiving)
    }

    /** The copy of [name] was unusable: offer it again while it is still there, perhaps it was not complete yet. */
    public fun retry(name: String) {
        taken -= name
        previous = previous - name
    }

    public companion object {
        private val STORE = Regex("tizenapp_\\d{1,20}_samsungapps-\\d{1,20}-(\\d{1,12})-\\d{1,20}\\.wgt")
        private val COMPANION = Regex("watcher\\.(wgt|tpk)")

        /** Only these names are ever turned into a path on the watch. */
        public fun isDelivery(name: String): Boolean = STORE.matches(name) || COMPANION.matches(name)

        public fun expectedSize(name: String): Long? = STORE.matchEntire(name)?.groupValues?.get(1)?.toLongOrNull()
    }
}
