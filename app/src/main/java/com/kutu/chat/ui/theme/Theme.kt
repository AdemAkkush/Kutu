package com.kutu.chat.ui.theme

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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.kutu.chat.data.ThemeMode

/** Kutu'nun kimliği: nane yeşilinden çivit moruna uzanan geçiş. Logo, gönder düğmesi ve vurgu öğelerinde kullanılır. */
object KutuBrand {
    val Mint = Color(0xFF34D399)
    val Cyan = Color(0xFF22B8CF)
    val Indigo = Color(0xFF6C7CFF)
    val gradient: Brush get() = Brush.linearGradient(listOf(Mint, Cyan, Indigo))
}

/** Kod blokları için renkler (tema ile birlikte değişir). */
data class CodeColors(
    val background: Color, val header: Color, val text: Color,
    val keyword: Color, val string: Color, val comment: Color, val number: Color, val function: Color,
)

private val DarkCode = CodeColors(
    background = Color(0xFF0B0D12), header = Color(0xFF161A22), text = Color(0xFFD7DCE5),
    keyword = Color(0xFFC792EA), string = Color(0xFFA5D6A7), comment = Color(0xFF6B7385), number = Color(0xFFF78C6C), function = Color(0xFF82AAFF),
)
private val LightCode = CodeColors(
    background = Color(0xFFF3F5F8), header = Color(0xFFE6E9EF), text = Color(0xFF1F2430),
    keyword = Color(0xFF8E44AD), string = Color(0xFF2E7D32), comment = Color(0xFF8A93A3), number = Color(0xFFC2410C), function = Color(0xFF1D4ED8),
)

val LocalCodeColors = staticCompositionLocalOf { DarkCode }
val LocalDarkTheme = staticCompositionLocalOf { true }

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6EE7B7), onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF16453A), onPrimaryContainer = Color(0xFFC8F7E4),
    secondary = Color(0xFFB4C2DB), onSecondary = Color(0xFF1E2A3F),
    secondaryContainer = Color(0xFF252B38), onSecondaryContainer = Color(0xFFDCE4F5),
    tertiary = Color(0xFFC4A7FF), onTertiary = Color(0xFF2E1A5C),
    tertiaryContainer = Color(0xFF35285A), onTertiaryContainer = Color(0xFFE9DDFF),
    background = Color(0xFF0E0F13), onBackground = Color(0xFFE6E8EE),
    surface = Color(0xFF0E0F13), onSurface = Color(0xFFE6E8EE),
    surfaceVariant = Color(0xFF23262F), onSurfaceVariant = Color(0xFF9EA5B3),
    surfaceContainerLowest = Color(0xFF0A0B0E), surfaceContainerLow = Color(0xFF14161B),
    surfaceContainer = Color(0xFF181A20), surfaceContainerHigh = Color(0xFF1F222A), surfaceContainerHighest = Color(0xFF282C36),
    outline = Color(0xFF4A5060), outlineVariant = Color(0xFF2A2E38),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF4A1F1C), onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE6E8EE), inverseOnSurface = Color(0xFF1A1C22),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B7A5B), onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F5E7), onPrimaryContainer = Color(0xFF00382A),
    secondary = Color(0xFF4A5670), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E8F2), onSecondaryContainer = Color(0xFF1B2333),
    tertiary = Color(0xFF6B4EC2), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEBE2FF), onTertiaryContainer = Color(0xFF26124F),
    background = Color(0xFFF7F8FA), onBackground = Color(0xFF15171C),
    surface = Color(0xFFF7F8FA), onSurface = Color(0xFF15171C),
    surfaceVariant = Color(0xFFE5E8ED), onSurfaceVariant = Color(0xFF5A6170),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F3F6),
    surfaceContainer = Color(0xFFECEEF2), surfaceContainerHigh = Color(0xFFE5E8ED), surfaceContainerHighest = Color(0xFFDDE1E7),
    outline = Color(0xFF8D94A2), outlineVariant = Color(0xFFD3D8E0),
    error = Color(0xFFB3261E), errorContainer = Color(0xFFFCE4E1), onErrorContainer = Color(0xFF5C1410),
    inverseSurface = Color(0xFF1F222A), inverseOnSurface = Color(0xFFF1F3F6),
)

private val Base = Typography()
private val KutuTypography = Typography(
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em),
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.015).em),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 25.sp, letterSpacing = 0.1.sp),
    bodyMedium = Base.bodyMedium.copy(lineHeight = 21.sp),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

private val KutuShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun KutuTheme(mode: ThemeMode, dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalCodeColors provides if (dark) DarkCode else LightCode, LocalDarkTheme provides dark) {
        MaterialTheme(colorScheme = scheme, typography = KutuTypography, shapes = KutuShapes, content = content)
    }
}
