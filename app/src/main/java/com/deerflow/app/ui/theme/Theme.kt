package com.deerflow.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class DeerflowSemanticColors(
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val userBubble: Color,
    val onUserBubble: Color,
    val codeSurface: Color,
    val codeHeaderSurface: Color,
    val inlineCodeSurface: Color,
    val diffAddedBg: Color,
    val diffAddedText: Color,
    val diffRemovedBg: Color,
    val diffRemovedText: Color,
)

private val DarkSemanticColors = DeerflowSemanticColors(
    success = Color(0xFF34D399),
    successContainer = Color(0xFF0F2E24),
    onSuccessContainer = Color(0xFFA7F3D0),
    warning = Color(0xFFFBBF24),
    warningContainer = Color(0xFF33240B),
    onWarningContainer = Color(0xFFFDE68A),
    userBubble = Color(0xFF252D4A),
    onUserBubble = Color(0xFFEEF2FF),
    codeSurface = Color(0xFF11151E),
    codeHeaderSurface = Color(0xFF181E2B),
    inlineCodeSurface = Color(0xFF212838),
    diffAddedBg = Color(0xFF122C20),
    diffAddedText = Color(0xFF6EE7B7),
    diffRemovedBg = Color(0xFF33151E),
    diffRemovedText = Color(0xFFFCA5A5),
)

private val LightSemanticColors = DeerflowSemanticColors(
    success = Color(0xFF16A34A),
    successContainer = Color(0xFFDCFCE7),
    onSuccessContainer = Color(0xFF14532D),
    warning = Color(0xFFD97706),
    warningContainer = Color(0xFFFEF3C7),
    onWarningContainer = Color(0xFF78350F),
    userBubble = Color(0xFFE0E7FF),
    onUserBubble = Color(0xFF1E1B4B),
    codeSurface = Color(0xFFF1F5F9),
    codeHeaderSurface = Color(0xFFE7EDF4),
    inlineCodeSurface = Color(0xFFE2E8F0),
    diffAddedBg = Color(0xFFDCFCE7),
    diffAddedText = Color(0xFF166534),
    diffRemovedBg = Color(0xFFFEE2E2),
    diffRemovedText = Color(0xFF991B1B),
)

val LocalDeerflowColors = staticCompositionLocalOf { LightSemanticColors }

val MaterialTheme.deerflowColors: DeerflowSemanticColors
    @Composable
    @ReadOnlyComposable
    get() = LocalDeerflowColors.current

private val DarkColors = darkColorScheme(
    primary = Color(0xFF818CF8),
    onPrimary = Color(0xFF11132E),
    primaryContainer = Color(0xFF282E4F),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFF94A3B8),
    onSecondary = Color(0xFF0F172A),
    secondaryContainer = Color(0xFF1F2633),
    onSecondaryContainer = Color(0xFFE2E8F0),
    tertiary = Color(0xFFA78BFA),
    onTertiary = Color(0xFF22134D),
    tertiaryContainer = Color(0xFF312257),
    onTertiaryContainer = Color(0xFFEDE9FE),
    background = Color(0xFF0D0F14),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF0D0F14),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1B202B),
    onSurfaceVariant = Color(0xFF94A3B8),
    surfaceContainerLowest = Color(0xFF090B0F),
    surfaceContainerLow = Color(0xFF131720),
    surfaceContainer = Color(0xFF181D28),
    surfaceContainerHigh = Color(0xFF202634),
    surfaceContainerHighest = Color(0xFF2A3244),
    outline = Color(0xFF334155),
    outlineVariant = Color(0xFF232B3A),
    error = Color(0xFFFB7185),
    onError = Color(0xFF4C0519),
    errorContainer = Color(0xFF4A1324),
    onErrorContainer = Color(0xFFFFE4E6),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Color(0xFF475569),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE2E8F0),
    onSecondaryContainer = Color(0xFF1E293B),
    tertiary = Color(0xFF7C3AED),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEDE9FE),
    onTertiaryContainer = Color(0xFF4C1D95),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF8FAFC),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFEFF3F8),
    onSurfaceVariant = Color(0xFF64748B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF1F5F9),
    surfaceContainerHigh = Color(0xFFE8EEF5),
    surfaceContainerHighest = Color(0xFFDDE5EF),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

private val DeerflowTypography = Typography(
    headlineMedium = TextStyle(
        fontSize = 24.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleSmall = TextStyle(
        fontSize = 14.5.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(
        fontSize = 15.5.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodySmall = TextStyle(
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
    ),
)

private val DeerflowShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun DeerflowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    val semanticColors = if (darkTheme) DarkSemanticColors else LightSemanticColors
    CompositionLocalProvider(LocalDeerflowColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = DeerflowShapes,
            typography = DeerflowTypography,
            content = content,
        )
    }
}
