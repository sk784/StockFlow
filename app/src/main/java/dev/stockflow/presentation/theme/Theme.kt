package dev.stockflow.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF116B5F), onPrimary = Color.White,
    primaryContainer = Color(0xFFD9F1E9), onPrimaryContainer = Color(0xFF073C34),
    secondary = Color(0xFF526779), secondaryContainer = Color(0xFFE5EDF3), onSecondaryContainer = Color(0xFF263D4D),
    tertiary = Color(0xFF956022), tertiaryContainer = Color(0xFFFFEDD2), onTertiaryContainer = Color(0xFF67400C),
    background = Color(0xFFF5F7F9), onBackground = Color(0xFF172D3B),
    surface = Color.White, onSurface = Color(0xFF172D3B),
    surfaceVariant = Color(0xFFEAF0F3), onSurfaceVariant = Color(0xFF5B6D79),
    surfaceContainer = Color(0xFFEDF2F5), surfaceContainerLow = Color(0xFFF8FAFB),
    surfaceContainerHigh = Color(0xFFE5EBEF), outline = Color(0xFF8696A1), outlineVariant = Color(0xFFDCE4E9),
    error = Color(0xFFB43D49), errorContainer = Color(0xFFFFE9EB), onErrorContainer = Color(0xFF7E2530),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF86D7BE), onPrimary = Color(0xFF00392D),
    primaryContainer = Color(0xFF174D40), onPrimaryContainer = Color(0xFFBAF3DD),
    secondary = Color(0xFFAAC6D9), secondaryContainer = Color(0xFF2A4050), onSecondaryContainer = Color(0xFFD8EAF5),
    tertiary = Color(0xFFF1C37C), tertiaryContainer = Color(0xFF5A421E), onTertiaryContainer = Color(0xFFFFDCA5),
    background = Color(0xFF101A22), onBackground = Color(0xFFE2ECF2),
    surface = Color(0xFF17242D), onSurface = Color(0xFFE2ECF2),
    surfaceVariant = Color(0xFF273741), onSurfaceVariant = Color(0xFFAEBFCB),
    surfaceContainer = Color(0xFF1C2D37), surfaceContainerLow = Color(0xFF14212A),
    surfaceContainerHigh = Color(0xFF273943), outline = Color(0xFF82949F), outlineVariant = Color(0xFF344751),
    error = Color(0xFFFFB3BC), errorContainer = Color(0xFF54242D), onErrorContainer = Color(0xFFFFDADF),
)
private val AppTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun StockFlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(22.dp)),
        content = content,
    )
}
