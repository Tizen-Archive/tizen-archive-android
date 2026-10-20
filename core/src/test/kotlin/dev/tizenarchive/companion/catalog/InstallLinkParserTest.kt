package dev.tizenarchive.companion.catalog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class InstallLinkParserTest {
    @Test
    fun `parses package and version`() {
        assertEquals(InstallLink("Dice", "1.0.2"), InstallLinkParser.parse("tizen-archive://install?package=Dice&version=1.0.2"))
    }

    @Test
    fun `version is optional and a trailing slash or upper case scheme is accepted`() {
        assertEquals(
            InstallLink("com.samsung.alarm-solis", null),
            InstallLinkParser.parse("TIZEN-ARCHIVE://install/?package=com.samsung.alarm-solis"),
        )
    }

    @Test
    fun `rejects anything but identifiers`() {
        for (link in listOf(
            "https://install?package=Dice",
            "tizen-archive://other?package=Dice",
            "tizen-archive://install",
            "tizen-archive://install?version=1.0",
            "tizen-archive://install?package=../x",
            "tizen-archive://install?package=Dice%2F..",
            "tizen-archive://install?package=Dice&server=http://evil",
            "tizen-archive://install?package=Dice&package=Other",
            "tizen-archive://install?package=Dice&device=1.2.3.4",
            "tizen-archive://install?package=",
            "tizen-archive://install?package=a b",
            "tizen-archive://install?package=" + "a".repeat(129),
            "",
        )) {
            assertThrows(InvalidLinkException::class.java, { InstallLinkParser.parse(link) }, link)
        }
    }
}
