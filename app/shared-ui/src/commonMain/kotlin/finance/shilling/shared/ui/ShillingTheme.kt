package finance.shilling.shared.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object ShillingColors {
    val Gold50 = Color(0xFFFEFCE8)
    val Gold100 = Color(0xFFFEF9C3)
    val Gold200 = Color(0xFFFEF08A)
    val Gold300 = Color(0xFFFDE047)
    val Gold400 = Color(0xFFFACC15)
    val Gold500 = Color(0xFFEAB308)
    val Gold600 = Color(0xFFCA8A04)
    val Gold700 = Color(0xFFA16207)

    val LightBackground = Color.White
    val LightCard = Color(0xFFF7F7F7)
    val LightForeground = Color(0xFF1C1C1C)
    val LightMuted = Color(0xFF6B6B6B)
    val LightBorder = Color(0xFFE5E5E5)

    val DarkBackground = Color(0xFF1F1F1F)
    val DarkCard = Color(0xFF2A2A2A)
    val DarkForeground = Color(0xFFFAFAFA)
    val DarkMuted = Color(0xFF999999)
    val DarkBorder = Color(0x1AFFFFFF)

    val Destructive = Color(0xFFEF4444)
    val Income = Color(0xFF22C55E)
}

private val LightColorScheme = lightColorScheme(
    primary = ShillingColors.Gold500,
    onPrimary = Color.White,
    primaryContainer = ShillingColors.Gold100,
    onPrimaryContainer = ShillingColors.Gold700,
    secondary = ShillingColors.Gold600,
    onSecondary = Color.White,
    secondaryContainer = ShillingColors.Gold100,
    onSecondaryContainer = ShillingColors.Gold700,
    background = ShillingColors.LightBackground,
    onBackground = ShillingColors.LightForeground,
    surface = ShillingColors.LightBackground,
    onSurface = ShillingColors.LightForeground,
    surfaceVariant = ShillingColors.LightCard,
    onSurfaceVariant = ShillingColors.LightMuted,
    outline = ShillingColors.LightBorder,
    outlineVariant = ShillingColors.LightBorder,
    error = ShillingColors.Destructive,
    onError = Color.White,
    tertiary = ShillingColors.Income,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCFCE7),
    onTertiaryContainer = Color(0xFF166534),
    inversePrimary = ShillingColors.Gold300,
)

private val DarkColorScheme = darkColorScheme(
    primary = ShillingColors.Gold400,
    onPrimary = ShillingColors.DarkBackground,
    primaryContainer = ShillingColors.Gold700,
    onPrimaryContainer = ShillingColors.Gold200,
    secondary = ShillingColors.Gold500,
    onSecondary = ShillingColors.DarkBackground,
    secondaryContainer = ShillingColors.Gold700,
    onSecondaryContainer = ShillingColors.Gold200,
    background = ShillingColors.DarkBackground,
    onBackground = ShillingColors.DarkForeground,
    surface = ShillingColors.DarkBackground,
    onSurface = ShillingColors.DarkForeground,
    surfaceVariant = ShillingColors.DarkCard,
    onSurfaceVariant = ShillingColors.DarkMuted,
    outline = ShillingColors.DarkBorder,
    outlineVariant = ShillingColors.DarkBorder,
    error = ShillingColors.Destructive,
    onError = Color.White,
    tertiary = ShillingColors.Income,
    onTertiary = ShillingColors.DarkBackground,
    tertiaryContainer = Color(0xFF1A3A2A),
    onTertiaryContainer = Color(0xFFBBF7D0),
    inversePrimary = ShillingColors.Gold600,
)

private val ShillingShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(18.dp),
)

@Composable
fun ShillingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        shapes = ShillingShapes,
        content = content
    )
}
