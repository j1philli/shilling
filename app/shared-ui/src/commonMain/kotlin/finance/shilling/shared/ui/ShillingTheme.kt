package finance.shilling.shared.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

object ShillingColors {
    val Gold100 = Color(0xFFFEF9C3)
    val Gold200 = Color(0xFFFEF08A)
    val Gold300 = Color(0xFFFDE047)
    val Gold400 = Color(0xFFFACC15)
    val Gold600 = Color(0xFFCA8A04)
    val Gold700 = Color(0xFFA16207)
    val Gold800 = Color(0xFF854D0E)
    val Gold900 = Color(0xFF713F12)
    val Gold950 = Color(0xFF422006)

    val LightBackground = Color.White
    val LightForeground = Color(0xFF1C1C1C)
    val LightMuted = Color(0xFF5E5E5E)
    val LightOutline = Color(0xFF8F8F8F)
    val LightBorder = Color(0xFFE5E5E5)

    val DarkBackground = Color(0xFF1F1F1F)
    val DarkForeground = Color(0xFFFAFAFA)
    val DarkMuted = Color(0xFFA3A3A3)
    val DarkOutline = Color(0xFF737373)
    val DarkBorder = Color(0xFF3A3A3A)
}

// Primary is a deep gold in light mode so it passes AA contrast both as a button fill
// (with white text) and as text/icon color on white surfaces.
private val LightColorScheme = lightColorScheme(
    primary = ShillingColors.Gold700,
    onPrimary = Color.White,
    primaryContainer = ShillingColors.Gold100,
    onPrimaryContainer = ShillingColors.Gold900,
    inversePrimary = ShillingColors.Gold300,
    secondary = Color(0xFF57534E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1EFED),
    onSecondaryContainer = Color(0xFF292524),
    tertiary = Color(0xFF15803D),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCFCE7),
    onTertiaryContainer = Color(0xFF14532D),
    error = Color(0xFFC62828),
    onError = Color.White,
    errorContainer = Color(0xFFFDE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    background = ShillingColors.LightBackground,
    onBackground = ShillingColors.LightForeground,
    surface = ShillingColors.LightBackground,
    onSurface = ShillingColors.LightForeground,
    surfaceVariant = Color(0xFFF4F4F4),
    onSurfaceVariant = ShillingColors.LightMuted,
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF2E2E2E),
    inverseOnSurface = Color(0xFFF5F5F5),
    outline = ShillingColors.LightOutline,
    outlineVariant = ShillingColors.LightBorder,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFE2E2E2),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF9F9F9),
    surfaceContainer = Color(0xFFF4F4F4),
    surfaceContainerHigh = Color(0xFFEEEEEE),
    surfaceContainerHighest = Color(0xFFE8E8E8),
)

private val DarkColorScheme = darkColorScheme(
    primary = ShillingColors.Gold400,
    onPrimary = ShillingColors.Gold950,
    primaryContainer = ShillingColors.Gold800,
    onPrimaryContainer = ShillingColors.Gold200,
    inversePrimary = ShillingColors.Gold700,
    secondary = Color(0xFFD6D3D1),
    onSecondary = Color(0xFF292524),
    secondaryContainer = Color(0xFF44403C),
    onSecondaryContainer = Color(0xFFF5F5F4),
    tertiary = Color(0xFF4ADE80),
    onTertiary = Color(0xFF052E16),
    tertiaryContainer = Color(0xFF14532D),
    onTertiaryContainer = Color(0xFFBBF7D0),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFECACA),
    background = ShillingColors.DarkBackground,
    onBackground = ShillingColors.DarkForeground,
    surface = ShillingColors.DarkBackground,
    onSurface = ShillingColors.DarkForeground,
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = ShillingColors.DarkMuted,
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFFEDEDED),
    inverseOnSurface = Color(0xFF1F1F1F),
    outline = ShillingColors.DarkOutline,
    outlineVariant = ShillingColors.DarkBorder,
    surfaceBright = Color(0xFF3A3A3A),
    surfaceDim = ShillingColors.DarkBackground,
    surfaceContainerLowest = Color(0xFF171717),
    surfaceContainerLow = Color(0xFF242424),
    surfaceContainer = Color(0xFF282828),
    surfaceContainerHigh = Color(0xFF303030),
    surfaceContainerHighest = Color(0xFF383838),
)

private val ShillingShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

private val ShillingTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun ShillingTheme(
    darkTheme: Boolean = when (DisplayPreferences.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    },
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        shapes = ShillingShapes,
        typography = ShillingTypography,
        content = content
    )
}
