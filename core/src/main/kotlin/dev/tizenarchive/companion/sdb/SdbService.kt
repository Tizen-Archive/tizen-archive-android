package dev.tizenarchive.companion.sdb

import java.io.InputStream

/** What an installer needs from a connected watch; [SdbSession] is the real one, tests use fakes. Not thread-safe: one call at a time. */
public interface SdbService : AutoCloseable {
    /** The watch's banner, for example `device::SM-R800::0`. */
    public val banner: String

    /** Runs [command] in the watch's shell and returns everything it printed; [onLine] sees complete lines as they arrive. */
    public fun shell(command: String, onLine: (String) -> Unit = {}): String

    /** Writes [data] to [remotePath] (an absolute path of plain characters) with [mode]; [progress] gets the number of bytes sent so far. */
    public fun push(remotePath: String, data: InputStream, mode: Int = DEFAULT_FILE_MODE, progress: (Long) -> Unit = {})

    public companion object {
        public const val DEFAULT_FILE_MODE: Int = 420
    }
}
