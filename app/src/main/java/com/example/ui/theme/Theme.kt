package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.domain.model.AppThemeStyle

data class ForgePaletteExtras(
    val canvasBg: Color,
    val glassSurface: Color,
    val glassElevated: Color,
    val glassBorder: Color,
    val isHighContrast: Boolean
)

val LocalForgeExtras = staticCompositionLocalOf {
    ForgePaletteExtras(
        canvasBg = ObsidianCanvas,
        glassSurface = CharcoalSurface,
        glassElevated = CharcoalElevated,
        glassBorder = GlassBorderSubtle,
        isHighContrast = false
    )
}

private val PingForgeShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun PingForgeTheme(
    themeStyle: AppThemeStyle = AppThemeStyle.OBSIDIAN_GLASS,
    content: @Composable () -> Unit
) {
    val extras = when (themeStyle) {
        AppThemeStyle.OBSIDIAN_GLASS -> ForgePaletteExtras(
            canvasBg = ObsidianCanvas,
            glassSurface = CharcoalSurface.copy(alpha = 0.86f),
            glassElevated = CharcoalElevated.copy(alpha = 0.92f),
            glassBorder = GlassBorderSubtle,
            isHighContrast = false
        )
        AppThemeStyle.AMOLED_BLACK -> ForgePaletteExtras(
            canvasBg = AmoledCanvas,
            glassSurface = AmoledSurface.copy(alpha = 0.92f),
            glassElevated = AmoledCard.copy(alpha = 0.96f),
            glassBorder = GlassBorderSubtle,
            isHighContrast = false
        )
        AppThemeStyle.HIGH_CONTRAST -> ForgePaletteExtras(
            canvasBg = HighContrastCanvas,
            glassSurface = HighContrastCard.copy(alpha = 0.96f),
            glassElevated = Color(0xFF232C42),
            glassBorder = GlassBorderStrong,
            isHighContrast = true
        )
    }

    val colorScheme = darkColorScheme(
        primary = ElectricViolet,
        onPrimary = Color(0xFF0A0816),
        primaryContainer = ElectricViolet.copy(alpha = 0.18f),
        onPrimaryContainer = TextPrimary,
        secondary = IceCyan,
        onSecondary = Color(0xFF041418),
        secondaryContainer = IceCyan.copy(alpha = 0.16f),
        onSecondaryContainer = TextPrimary,
        tertiary = Emerald,
        onTertiary = Color(0xFF041910),
        tertiaryContainer = Emerald.copy(alpha = 0.16f),
        onTertiaryContainer = TextPrimary,
        background = extras.canvasBg,
        onBackground = TextPrimary,
        surface = extras.glassSurface,
        onSurface = TextPrimary,
        surfaceVariant = extras.glassElevated,
        onSurfaceVariant = TextSecondary,
        outline = extras.glassBorder,
        outlineVariant = Color.White.copy(alpha = 0.06f),
        error = AlertCrimson,
        onError = Color.White
    )

    CompositionLocalProvider(LocalForgeExtras provides extras) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PingForgeTypography,
            shapes = PingForgeShapes,
            content = content
        )
    }
}
