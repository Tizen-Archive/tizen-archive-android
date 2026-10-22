package dev.tizenarchive.companion.sdb

/** How a `pkgcmd` run ended. [code] and [message] come from the watch (for example `-24`, `Image error`). */
public class PkgcmdResult(public val success: Boolean, public val code: Int? = null, public val message: String? = null)

/**
 * Reads the output of `pkgcmd -i/-u` line by line. Success is `key[end] val[ok]`; a failure shows as `key[error] val[<code>]`,
 * `key[end] val[fail]` or `processing result : <text> [<code>] failed`.
 */
public class PkgcmdOutput {
    private var endOk = false
    private var failed = false
    private var code: Int? = null
    private var message: String? = null

    /** Feeds one line; returns the install percentage when the line carries one. */
    public fun feed(line: String): Int? {
        PERCENT.find(line)?.let { return it.groupValues[1].toIntOrNull()?.coerceIn(0, 100) }
        END.find(line)?.let { if (it.groupValues[1] == "ok") endOk = true else failed = true }
        ERROR.find(line)?.let { code = it.groupValues[1].toIntOrNull() ?: code }
        RESULT.find(line)?.let {
            failed = true
            message = it.groupValues[1].trim()
            code = it.groupValues[2].toIntOrNull() ?: code
        }
        return null
    }

    public fun result(): PkgcmdResult = when {
        failed -> PkgcmdResult(false, code, message)
        endOk -> PkgcmdResult(true)
        else -> PkgcmdResult(false, code, message ?: "pkgcmd gave no result")
    }

    private companion object {
        val PERCENT = Regex("key\\[install_percent] val\\[(\\d+)]")
        val END = Regex("key\\[end] val\\[(ok|fail)]")
        val ERROR = Regex("key\\[error] val\\[(-?\\d+)]")
        val RESULT = Regex("processing result : (.*?) \\[(-?\\d+)] failed")
    }
}
