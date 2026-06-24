package com.deerflow.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6366F1), // Indigo accent
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF4F46E5), // User bubble fill
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFF1E232D), // Assistant bubble background
    onSecondary = Color(0xFFE2E8F0),
    secondaryContainer = Color(0xFF262C3A), // Thinking background
    onSecondaryContainer = Color(0xFFE9D8FD),
    tertiary = Color(0xFF8B5CF6), // Purple accent for reasoning
    tertiaryContainer = Color(0xFF3B2A50),
    onTertiaryContainer = Color(0xFFF3E8FF),
    background = Color(0xFF0F1115), // Deep charcoal background
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF171B22), // Surface elements
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF222731), // System/Log card background
    onSurfaceVariant = Color(0xFFCBD5E1),
    outline = Color(0xFF334155), // Card borders
    error = Color(0xFFF43F5E), // Coral red for errors/interrupts
    errorContainer = Color(0xFF4C0519),
    onErrorContainer = Color(0xFFFEE2E2),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF4F46E5), // Indigo
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0E7FF), // User bubble fill
    onPrimaryContainer = Color(0xFF312E81),
    secondary = Color(0xFFF1F5F9), // Light slate blue for bubbles
    onSecondary = Color(0xFF1E293B),
    secondaryContainer = Color(0xFFF3E8FF), // Thinking background
    onSecondaryContainer = Color(0xFF5B21B6),
    tertiary = Color(0xFF7C3AED), // Purple accent
    tertiaryContainer = Color(0xFFFAE8FF),
    onTertiaryContainer = Color(0xFF701A75),
    background = Color(0xFFF8FAFC), // Off-white
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF8FAFC),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFE2E8F0),
    error = Color(0xFFEF4444),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF991B1B),
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
    MaterialTheme(colorScheme = colorScheme, content = content)
}
