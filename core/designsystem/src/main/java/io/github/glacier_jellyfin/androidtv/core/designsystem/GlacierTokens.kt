package io.github.glacier_jellyfin.androidtv.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.takeOrElse
import io.github.mbustos27.nixie.tv.designsystem.NixieTheme

/**
 * Glacier primitives, mirrored from the design's glacier.css (layer 1).
 *
 * Ground, glass, ink and ice light are Glacier's identity and never vary.
 * Only [Accent] may be swapped by the user.
 *
 * Nixie: each colour is read from the Nixie theme's tokens first (NixieTheme), Glacier's value when the theme
 * leaves it unset.
 */
object GlacierColors {
    /** Page ground. */
    val Void get() = NixieTheme.tokens.void.takeOrElse { Color(0xFF0A1420) }

    /** Raised sheet. */
    val Deep get() = NixieTheme.tokens.deep.takeOrElse { Color(0xFF12202E) }

    val GlassFill get() = NixieTheme.tokens.glassFill.takeOrElse {
        Color(red = 173, green = 214, blue = 224, alpha = (0.09f * 255).toInt())
    }
    val GlassBorder get() = NixieTheme.tokens.glassBorder.takeOrElse {
        Color(red = 196, green = 226, blue = 234, alpha = (0.18f * 255).toInt())
    }
    val GlassFill2 get() = NixieTheme.tokens.glassFill2.takeOrElse {
        Color(red = 173, green = 214, blue = 224, alpha = (0.13f * 255).toInt())
    }
    val GlassBorder2 get() = NixieTheme.tokens.glassBorder2.takeOrElse {
        Color(red = 196, green = 226, blue = 234, alpha = (0.28f * 255).toInt())
    }

    val Ice get() = NixieTheme.tokens.ice.takeOrElse { Color(0xFFE8F4F7) }
    val Mist get() = NixieTheme.tokens.mist.takeOrElse { Color(0xFF8FA6B3) }
}

/**
 * The accents, in spectrum order (red to violet, then the colourless Firn) as
 * the settings show them. The five from the design are joined by four more
 * for the hues they leave out, at the same lightness. An accent overrides exactly
 * two colours; adding one is two values and a contrast check against
 * [GlacierColors.Void], which is also the text on both of them.
 */
enum class Accent(
    // Nixie: Glacier's own two colours, still what the settings' swatches show.
    val swatchMain: Color,
    val swatchDeep: Color,
) {
    Alpenglow(Color(0xFFF2909A), Color(0xFFBD6670)),
    Dawn(Color(0xFFF5AC7E), Color(0xFFBF7A4E)),
    MidnightSun(Color(0xFFF0D47C), Color(0xFFB39745)),
    Aurora(Color(0xFF5FE3C0), Color(0xFF2F9E86)),
    Crevasse(Color(0xFF7DC7D9), Color(0xFF4E93A8)),
    BlueIce(Color(0xFF38B6E0), Color(0xFF2186B0)),
    PolarNight(Color(0xFF9FB8F0), Color(0xFF657BBA)),
    Twilight(Color(0xFFC4A9F2), Color(0xFF8B70C2)),
    Firn(Color(0xFFCFE4EC), Color(0xFF8FB3C2)),
    ;

    // Nixie: a theme with an accent of its own (Drive-in's hot pink) shows it in place of the profile's pick.
    val main: Color get() = NixieTheme.tokens.accentMain.takeOrElse { swatchMain }
    val deep: Color get() = NixieTheme.tokens.accentDeep.takeOrElse { swatchDeep }
}

// Nixie: a theme's one radius (Drive-in: 8) replaces all three.
object GlacierShapes {
    val RadiusSm get() = NixieTheme.tokens.radius.takeOrElse { 8.dp }
    val RadiusMd get() = NixieTheme.tokens.radius.takeOrElse { 14.dp }
    val RadiusLg get() = NixieTheme.tokens.radius.takeOrElse { 22.dp }
}

object GlacierElevation {
    val Blur1 = 14.dp
    val Blur2 = 28.dp
}
