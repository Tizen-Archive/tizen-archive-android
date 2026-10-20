package dev.tizenarchive.companion.catalog

/** Orders Tizen package versions such as "1.0.5" and "1.0.10": numbers by value, other parts as text. */
public object VersionCompare {
    public fun compare(a: String, b: String): Int {
        val left = parts(a)
        val right = parts(b)
        for (i in 0 until maxOf(left.size, right.size)) {
            val x = left.getOrNull(i)
            val y = right.getOrNull(i)
            val result = when {
                x == null -> if (y!!.toLongOrNull() == 0L) 0 else -1
                y == null -> if (x.toLongOrNull() == 0L) 0 else 1
                else -> {
                    val nx = x.toLongOrNull()
                    val ny = y.toLongOrNull()
                    if (nx != null && ny != null) nx.compareTo(ny) else x.compareTo(y, ignoreCase = true)
                }
            }
            if (result != 0) return result
        }
        return 0
    }

    public fun isNewer(candidate: String, installed: String): Boolean = compare(candidate, installed) > 0

    private fun parts(version: String): List<String> = version.trim().trimStart('v', 'V').split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
}
