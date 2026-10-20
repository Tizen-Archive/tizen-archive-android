package dev.tizenarchive.companion.catalog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VersionCompareTest {
    @Test
    fun `numbers are compared by value`() {
        assertTrue(VersionCompare.isNewer("1.0.10", "1.0.5"))
        assertFalse(VersionCompare.isNewer("1.0.5", "1.0.10"))
        assertTrue(VersionCompare.isNewer("2.0", "1.9.9"))
    }

    @Test
    fun `equal versions and trailing zeros are not newer`() {
        assertEquals(0, VersionCompare.compare("1.0", "1.0.0"))
        assertFalse(VersionCompare.isNewer("1.0.0", "1.0"))
        assertTrue(VersionCompare.isNewer("1.0.1", "1.0"))
    }

    @Test
    fun `a leading v and other separators are ignored`() {
        assertEquals(0, VersionCompare.compare("v1.2.3", "1-2-3"))
        assertTrue(VersionCompare.isNewer("1.2.4", "v1.2.3"))
    }

    @Test
    fun `text parts fall back to text order`() {
        assertTrue(VersionCompare.compare("1.0.beta", "1.0.alpha") > 0)
    }
}
