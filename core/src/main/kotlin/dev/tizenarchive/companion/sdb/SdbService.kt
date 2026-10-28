package dev.tizenarchive.companion.sdb

import java.io.InputStream
import java.io.OutputStream

/** One entry of a directory listing; [mode] holds the Unix file type and permission bits, [modifiedAt] is in seconds. */
public class RemoteEntry(public val name: String, public val size: Long, public val mode: Int, public val modifiedAt: Long) {
    public val isFile: Boolean get() = mode and FILE_TYPE_MASK == REGULAR_FILE

    private companion object {
        const val FILE_TYPE_MASK = 0xF000
        const val REGULAR_FILE = 0x8000
    }
}

/** What an installer needs from a connected watch; [SdbSession] is the real one, tests use fakes. Not thread-safe: one call at a time. */
public interface SdbService : AutoCloseable {
    /** The watch's banner, for example `device::SM-R800::0`. */
    public val banner: String

    /** Runs [command] in the watch's shell and returns everything it printed; [onLine] sees complete lines as they arrive. */
    public fun shell(command: String, onLine: (String) -> Unit = {}): String

    /** Writes [data] to [remotePath] (an absolute path of plain characters) with [mode]; [progress] gets the number of bytes sent so far. */
    public fun push(remotePath: String, data: InputStream, mode: Int = DEFAULT_FILE_MODE, progress: (Long) -> Unit = {})

    /**
     * The entries of [remoteDir], `.` and `..` excluded. The file service reads directories that the shell may not open; a missing
     * directory lists as empty.
     */
    public fun list(remoteDir: String): List<RemoteEntry>

    /** Copies [remotePath] into [out]; false when the watch has no such file. More than [maxBytes] fails with [SdbError.PROTOCOL]. */
    public fun pull(remotePath: String, out: OutputStream, maxBytes: Long): Boolean

    public companion object {
        public const val DEFAULT_FILE_MODE: Int = 420
    }
}
