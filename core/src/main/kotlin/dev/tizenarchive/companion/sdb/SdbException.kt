package dev.tizenarchive.companion.sdb

public enum class SdbError {
    /** The address does not answer or refuses the connection. */
    UNREACHABLE,

    /** The watch closed the connection at once: another sdb host is connected to it. */
    BUSY,

    /** The key was not confirmed on the watch in time. */
    AUTH_TIMEOUT,

    /** The watch refused the key. */
    AUTH_REJECTED,

    /** The watch stopped answering. */
    TIMEOUT,

    /** The connection broke or was closed. */
    DISCONNECTED,

    /** The watch refused to open a service. */
    SERVICE_REFUSED,

    /** The watch sent something that is not sdb. */
    PROTOCOL,
}

public class SdbException(public val error: SdbError, message: String, cause: Throwable? = null) : Exception(message, cause)
