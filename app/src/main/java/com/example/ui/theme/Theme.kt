package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = MangaIndigoLight,
    onPrimary = Color.White,
    primaryContainer = MangaIndigoDark,
    onPrimaryContainer = Color.White,
    secondary = MangaCrimson,
    onSecondary = Color.White,
    tertiary = MangaCyan,
    background = MangaInk900,
    surface = MangaInk800,
    surfaceVariant = MangaInk700,
    onBackground = Color(0xFFF1F5F9),
    onSurface = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFFCBD5E1),
    outline = MangaInk600
)

private val LightColorScheme = lightColorScheme(
    primary = MangaIndigoDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEEF2FF),
    onPrimaryContainer = MangaIndigoDark,
    secondary = MangaCrimson,
    onSecondary = Color.White,
    tertiary = MangaCyan,
    background = MangaPaperLight,
    surface = MangaPaperCard,
    surfaceVariant = Color(0xFFF1F5F9),
    onBackground = MangaInk900,
    onSurface = MangaInk900,
    onSurfaceVariant = MangaInk600,
    outline = Color(0xFFCBD5E1)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Keep distinctive manga comic styling
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
