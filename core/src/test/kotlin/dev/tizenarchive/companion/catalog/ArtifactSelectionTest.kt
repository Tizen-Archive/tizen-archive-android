package dev.tizenarchive.companion.catalog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ArtifactSelectionTest {
    private fun artifact(url: String?) = Artifact("a".repeat(64), 3, url)

    private fun version(vararg builds: Build) = VersionInfo("x", "1.0", null, "wgt", builds.toList())

    @Test
    fun `picks the first installable build with a download`() {
        val file = artifact("/api/artifacts/a/download")
        val result = ArtifactSelection.select(
            version(Build(Verdict.EXPIRED, listOf(artifact("/e"))), Build(Verdict.INSTALLABLE, listOf(artifact(null), file))),
        )
        assertEquals(Selection.Found(file), result)
    }

    @Test
    fun `explains why nothing can be installed`() {
        val cases = mapOf(
            Verdict.DEVICE_BOUND to NotInstallableReason.DEVICE_BOUND,
            Verdict.EXPIRED to NotInstallableReason.EXPIRED,
            Verdict.NOT_INSTALLABLE to NotInstallableReason.REJECTED_BY_WATCH,
            Verdict.UNVERIFIED to NotInstallableReason.UNVERIFIED,
            Verdict.UNKNOWN to NotInstallableReason.UNVERIFIED,
        )
        for ((verdict, reason) in cases) {
            assertEquals(Selection.NotInstallable(reason), ArtifactSelection.select(version(Build(verdict, listOf(artifact("/x"))))), verdict.name)
        }
    }

    @Test
    fun `installable without a download is reported separately`() {
        assertEquals(
            Selection.NotInstallable(NotInstallableReason.NOT_DOWNLOADABLE),
            ArtifactSelection.select(version(Build(Verdict.INSTALLABLE, listOf(artifact(null))))),
        )
    }

    @Test
    fun `a version without builds`() {
        assertEquals(Selection.NotInstallable(NotInstallableReason.NO_BUILDS), ArtifactSelection.select(version()))
    }
}
