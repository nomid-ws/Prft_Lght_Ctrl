package app.prft.lightctrl.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Modern Monochrome Black & White Studio Palette.
 * Uses progressive neutral matte charcoal surfaces (#0D0D0F -> #161618 -> #202024)
 * to eliminate pure-#000000 OLED scroll halation while delivering crisp pure-white
 * (#FFFFFF / #FAFAFA) hardware console contrast.
 */
object StudioColors {
    val CanvasBase = Color(0xFF0D0D0F)
    val CardSurface = Color(0xFF161618)
    val ControlContainer = Color(0xFF222226)
    val SheetElevated = Color(0xFF1A1A1E)
    val SubtleOutline = Color(0xFF34343B)

    // Monochrome primary & functional tokens
    val FlashAmber = Color(0xFFFFFFFF)
    val OnFlashAmber = Color(0xFF0D0D0F)
    val AirCyan = Color(0xFFD4D4D8)
    val ReadyEmerald = Color(0xFFFAFAFA)
    val StudioRed = Color(0xFFA1A1AA)
    val WarmKelvin = Color(0xFFFAFAFA)
    val CoolKelvin = Color(0xFFD4D4D8)

    val TextPrimary = Color(0xFFFAFAFA)
    val TextSecondary = Color(0xFFA1A1AA)
    val TextMuted = Color(0xFF52525B)
}

private val StudioColorScheme = darkColorScheme(
    primary = StudioColors.FlashAmber,
    onPrimary = StudioColors.OnFlashAmber,
    primaryContainer = Color(0xFFFAFAFA),
    onPrimaryContainer = Color(0xFF0D0D0F),
    secondary = Color(0xFFE4E4E7),
    onSecondary = Color(0xFF0D0D0F),
    secondaryContainer = Color(0xFF26262C),
    onSecondaryContainer = Color(0xFFFAFAFA),
    tertiary = Color(0xFFD4D4D8),
    onTertiary = Color(0xFF0D0D0F),
    background = StudioColors.CanvasBase,
    onBackground = StudioColors.TextPrimary,
    surface = StudioColors.CardSurface,
    onSurface = StudioColors.TextPrimary,
    surfaceVariant = StudioColors.ControlContainer,
    onSurfaceVariant = StudioColors.TextSecondary,
    surfaceContainerHigh = StudioColors.SheetElevated,
    outline = StudioColors.SubtleOutline,
    error = Color(0xFFE4E4E7),
    onError = Color(0xFF0D0D0F),
)

private val StudioTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

@Composable
fun PrftStudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StudioColorScheme,
        typography = StudioTypography,
        content = content,
    )
}
