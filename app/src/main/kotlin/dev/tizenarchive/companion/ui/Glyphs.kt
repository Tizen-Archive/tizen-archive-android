package dev.tizenarchive.companion.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** The small icons of the status line, drawn from path data so that the app needs no icon library. */
internal object Glyphs {
    val Check: ImageVector by lazy { glyph("Check", "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z") }

    val Warning: ImageVector by lazy { glyph("Warning", "M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z") }

    val ChevronRight: ImageVector by lazy { glyph("ChevronRight", "M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z") }

    private fun glyph(name: String, path: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
            .build()
}
