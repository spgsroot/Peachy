package ru.spgsroot.peachy.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Palette from the user-supplied Peachy brandboard. Dark only by design: the app keeps its
// single dark appearance, and dynamic wallpaper colours would replace the brand.
private val PeachyBlack = Color(0xFF0B0B0D)
private val PeachyGraphite = Color(0xFF15171B)
private val PeachyOffWhite = Color(0xFFF4F4F5)
private val PeachyPink = Color(0xFFE7A7BE)
private val PeachyRose = Color(0xFFC97A9B)
private val PeachyLilac = Color(0xFFD6B6C8)
private val PeachyMuted = Color(0xFFADA7B4)
private val PeachyOutline = Color(0xFF6F737D)
private val PeachyDivider = Color(0xFF2A2D33)
private val PeachySurfaceVariant = Color(0xFF262A31)
private val PeachySurfaceLow = Color(0xFF111318)
private val PeachySurfaceHigh = Color(0xFF1C1F24)
private val PeachySurfaceHighest = Color(0xFF23262C)
private val PeachySurfaceBright = Color(0xFF2A2D33)
private val PeachyOnPinkContainer = Color(0xFFF7DCE7)
private val PeachyPinkContainer = Color(0xFF3A2430)
private val PeachyOnRoseContainer = Color(0xFFF3DDE5)
private val PeachyRoseContainer = Color(0xFF3A2C33)
private val PeachyOnLilacContainer = Color(0xFFF4DEEA)
private val PeachyLilacContainer = Color(0xFF3B2E39)
private val PeachyInverseSurface = Color(0xFFF4F4F5)
private val PeachyInverseOnSurface = Color(0xFF1A1B1F)
private val PeachyInversePrimary = Color(0xFF8E4A64)
private val PeachyError = Color(0xFFF2B8B5)
private val PeachyOnError = Color(0xFF601410)
private val PeachyErrorContainer = Color(0xFF8C1D18)
private val PeachyOnErrorContainer = Color(0xFFF9DEDC)

private val PeachyColorScheme = darkColorScheme(
    primary = PeachyPink,
    onPrimary = PeachyBlack,
    primaryContainer = PeachyPinkContainer,
    onPrimaryContainer = PeachyOnPinkContainer,
    inversePrimary = PeachyInversePrimary,
    secondary = PeachyRose,
    onSecondary = PeachyBlack,
    secondaryContainer = PeachyRoseContainer,
    onSecondaryContainer = PeachyOnRoseContainer,
    tertiary = PeachyLilac,
    onTertiary = PeachyBlack,
    tertiaryContainer = PeachyLilacContainer,
    onTertiaryContainer = PeachyOnLilacContainer,
    background = PeachyBlack,
    onBackground = PeachyOffWhite,
    surface = PeachyGraphite,
    onSurface = PeachyOffWhite,
    surfaceVariant = PeachySurfaceVariant,
    onSurfaceVariant = PeachyMuted,
    surfaceTint = PeachyPink,
    surfaceDim = PeachyBlack,
    surfaceBright = PeachySurfaceBright,
    surfaceContainerLowest = PeachyBlack,
    surfaceContainerLow = PeachySurfaceLow,
    surfaceContainer = PeachyGraphite,
    surfaceContainerHigh = PeachySurfaceHigh,
    surfaceContainerHighest = PeachySurfaceHighest,
    inverseSurface = PeachyInverseSurface,
    inverseOnSurface = PeachyInverseOnSurface,
    error = PeachyError,
    onError = PeachyOnError,
    errorContainer = PeachyErrorContainer,
    onErrorContainer = PeachyOnErrorContainer,
    outline = PeachyOutline,
    outlineVariant = PeachyDivider,
    scrim = Color.Black
)

private val PeachyShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun PeachyTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = PeachyColorScheme,
        typography = Typography,
        shapes = PeachyShapes
    ) {
        // Material 3 leaves the default content colour black; anything drawn on a
        // custom-coloured surface (for example one with alpha) would be dark on dark.
        CompositionLocalProvider(
            LocalContentColor provides PeachyColorScheme.onBackground,
            content = content
        )
    }
}
