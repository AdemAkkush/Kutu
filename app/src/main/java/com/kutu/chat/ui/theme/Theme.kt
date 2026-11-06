package com.kutu.chat.ui.theme

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
import com.kutu.chat.data.ThemeMode

private val NvidiaGreen = Color(0xFF76B900)

private val LightColors = lightColorScheme(
    primary = Color(0xFF4C7A00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFF58A),
    onPrimaryContainer = Color(0xFF102000),
    secondary = Color(0xFF58624A),
    tertiary = Color(0xFF386664),
    background = Color(0xFFFBFCF4),
    surface = Color(0xFFFBFCF4),
)

private val DarkColors = darkColorScheme(
    primary = NvidiaGreen,
    onPrimary = Color(0xFF173800),
    primaryContainer = Color(0xFF365400),
    onPrimaryContainer = Color(0xFFCFF58A),
    secondary = Color(0xFFBFCBAD),
    tertiary = Color(0xFFA0CFCC),
    background = Color(0xFF121411),
    surface = Color(0xFF121411),
)

@Composable
fun KutuTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
