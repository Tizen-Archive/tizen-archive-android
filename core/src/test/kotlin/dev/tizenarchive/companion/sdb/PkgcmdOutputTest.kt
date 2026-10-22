package dev.tizenarchive.companion.sdb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The sample lines were captured from `pkgcmd` on a Galaxy Watch (SM-R800, Tizen 5.5) on 22.10.2026. */
class PkgcmdOutputTest {
    private fun parse(text: String): Pair<PkgcmdResult, List<Int>> {
        val output = PkgcmdOutput()
        val percents = text.lines().mapNotNull { output.feed(it) }
        return output.result() to percents
    }

    @Test
    fun `a successful install reports growing percentages and ok`() {
        val text = """
            path is /home/owner/share/tmp/sdk_tools/spike-install.wgt
            __return_cb req_id[1] pkg_type[wgt] pkgid[55V5m8QGTw] key[start] val[install]
            __return_cb req_id[1] pkg_type[wgt] pkgid[55V5m8QGTw] key[install_percent] val[11]
            __return_cb req_id[1] pkg_type[wgt] pkgid[55V5m8QGTw] key[install_percent] val[55]
            __return_cb req_id[1] pkg_type[wgt] pkgid[55V5m8QGTw] key[install_percent] val[100]
            __return_cb req_id[1] pkg_type[wgt] pkgid[55V5m8QGTw] key[end] val[ok]
            spend time for pkgcmd is [4514]ms
        """.trimIndent()
        val (result, percents) = parse(text)
        assertTrue(result.success)
        assertEquals(listOf(11, 55, 100), percents)
    }

    @Test
    fun `a broken package gives code -24 and the text from the watch`() {
        val text = """
            __return_cb req_id[1] pkg_type[wgt] pkgid[] key[error] val[-24]
            __return_cb req_id[1] pkg_type[wgt] pkgid[] key[end] val[fail]
            processing result : Image error [-24] failed
            spend time for pkgcmd is [16648]ms
        """.trimIndent()
        val (result, _) = parse(text)
        assertFalse(result.success)
        assertEquals(-24, result.code)
        assertEquals("Image error", result.message)
    }

    @Test
    fun `a missing file gives code -5`() {
        val (result, _) = parse("processing result : Package not found [-5] failed\nspend time for pkgcmd is [57]ms")
        assertFalse(result.success)
        assertEquals(-5, result.code)
        assertEquals("Package not found", result.message)
    }

    @Test
    fun `removing a package that is not installed gives code -999`() {
        val (result, _) = parse("Undefined error(-1)processing result : Undefined Error [-999] failed\nspend time for pkgcmd is [258]ms")
        assertFalse(result.success)
        assertEquals(-999, result.code)
    }

    @Test
    fun `a successful removal is ok`() {
        val (result, percents) = parse("__return_cb req_id[1] pkg_type[wgt] pkgid[55V5m8QGTw] key[end] val[ok]\nspend time for pkgcmd is [2135]ms")
        assertTrue(result.success)
        assertTrue(percents.isEmpty())
    }

    @Test
    fun `no output at all is a failure, not a success`() {
        val (result, _) = parse("")
        assertFalse(result.success)
        assertNull(result.code)
    }

    @Test
    fun `ok followed by a failure message is a failure`() {
        val (result, _) = parse("key[end] val[ok]\nprocessing result : Something [-3] failed")
        assertFalse(result.success)
        assertEquals(-3, result.code)
    }

    @Test
    fun `percentages are limited to 0 to 100`() {
        val output = PkgcmdOutput()
        assertEquals(100, output.feed("key[install_percent] val[250]"))
        assertEquals(0, output.feed("key[install_percent] val[0]"))
        assertNull(output.feed("key[install_percent] val[x]"))
    }
}
