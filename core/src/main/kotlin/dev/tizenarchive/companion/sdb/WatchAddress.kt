package dev.tizenarchive.companion.sdb

import java.net.InetAddress

/** Watch addresses: only IPv4 literals of a private network, so the app never connects to arbitrary hosts. */
public object WatchAddress {
    /** Returns the address, or null when [text] is not a dotted-decimal private IPv4 address (10/8, 172.16/12, 192.168/16, 169.254/16). */
    public fun parse(text: String): InetAddress? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for ((i, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' } || (part.length > 1 && part[0] == '0')) return null
            octets[i] = part.toInt()
            if (octets[i] > 255) return null
        }
        val private = octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 169 && octets[1] == 254)
        if (!private) return null
        return InetAddress.getByAddress(ByteArray(4) { octets[it].toByte() })
    }
}
