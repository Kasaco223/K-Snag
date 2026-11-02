// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Tema Material 3 con los colores de la app de Windows (src/kdownloader/ui/css/tokens.css, v2.0.1). Sin color
// dinámico: primero la marca. Los contrastes se comprobaron con la fórmula de WCAG (texto >= 4.5:1).
package io.github.kasaco223.kdownloader.ui.theme

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

val KdDark = darkColorScheme(
    primary = Color(0xFFA5B4FC), onPrimary = Color(0xFF1E1B4B), // --accent-text; 9.39:1 sobre el fondo
    primaryContainer = Color(0xFF5A54E6), onPrimaryContainer = Color.White, // inicio del degradado del CTA; 5.46:1
    inversePrimary = Color(0xFF4F46E5),
    secondary = Color(0xFFC4B5FD), onSecondary = Color(0xFF2E1065), // derivado de --brand-2
    secondaryContainer = Color(0xFF272A55), onSecondaryContainer = Color(0xFFEEF0F6),
    tertiary = Color(0xFFFDA08F), onTertiary = Color(0xFF431407), // --coral-text
    tertiaryContainer = Color(0xFF352224), onTertiaryContainer = Color(0xFFFDA08F),
    error = Color(0xFFF87171), onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF2E232B), onErrorContainer = Color(0xFFFCA5A5), // 7.95:1
    background = Color(0xFF101218), onBackground = Color(0xFFEEF0F6),
    surface = Color(0xFF101218), onSurface = Color(0xFFEEF0F6),
    surfaceVariant = Color(0xFF1F2330), onSurfaceVariant = Color(0xFFA6ACBD), // --surface-2 / --text-2 (6.90:1)
    surfaceTint = Color(0xFF101218), // = surface: capas planas como en Windows
    inverseSurface = Color(0xFFEEF0F6), inverseOnSurface = Color(0xFF14161D),
    outline = Color(0xFF8A91A5), outlineVariant = Color(0xFF36383D), // --text-3 (5.95:1)
    surfaceDim = Color(0xFF101218), surfaceBright = Color(0xFF2E3344),
    surfaceContainerLowest = Color(0xFF0B0D12),
    surfaceContainerLow = Color(0xFF171A23), // --surface-1: tarjetas y hojas
    surfaceContainer = Color(0xFF1B1E29),
    surfaceContainerHigh = Color(0xFF1F2330), // --surface-2
    surfaceContainerHighest = Color(0xFF282D3C), // --surface-3
)

val KdLight = lightColorScheme(
    primary = Color(0xFF4F46E5), onPrimary = Color.White, // 6.29:1
    primaryContainer = Color(0xFFEFF0FE), onPrimaryContainer = Color(0xFF312E81),
    inversePrimary = Color(0xFFA5B4FC),
    secondary = Color(0xFF6D28D9), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E0FC), onSecondaryContainer = Color(0xFF312E81),
    tertiary = Color(0xFFC2410C), onTertiary = Color.White, // 5.18:1
    tertiaryContainer = Color(0xFFFEEEEC), onTertiaryContainer = Color(0xFF9A3412),
    error = Color(0xFFB42318), onError = Color.White,
    errorContainer = Color(0xFFFEF2F2), onErrorContainer = Color(0xFFB42318), // 6.01:1
    background = Color(0xFFF6F7FB), onBackground = Color(0xFF14161D),
    surface = Color(0xFFF6F7FB), onSurface = Color(0xFF14161D),
    surfaceVariant = Color(0xFFEEF0F6), onSurfaceVariant = Color(0xFF4B5163), // 6.94:1 sobre --surface-2
    surfaceTint = Color(0xFFF6F7FB),
    inverseSurface = Color(0xFF1F2330), inverseOnSurface = Color(0xFFEEF0F6),
    outline = Color(0xFF5F6578), outlineVariant = Color(0xFFCDCFD3),
    surfaceDim = Color(0xFFE4E7F0), surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White, // --surface-1: tarjetas
    surfaceContainer = Color(0xFFF2F3F8),
    surfaceContainerHigh = Color(0xFFEEF0F6),
    surfaceContainerHighest = Color(0xFFE4E7F0),
)

private val SemiBold = FontWeight.SemiBold // Windows usa 600 casi en todos los títulos

/** Tipografía del sistema con los tamaños de Windows (--fs-*). */
val KdTypography = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = SemiBold),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = SemiBold),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

/** Radios de Windows: --r-xs 4, --r-input 8, --r-card 12, --r-lg 16; 28 para hojas (Material 3). */
val KdShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** "system" sigue al sistema; "light" y "dark" lo fijan (Ajustes › Apariencia). */
@Composable
fun darkFor(theme: String): Boolean = when (theme) {
    "light" -> false
    "dark" -> true
    else -> isSystemInDarkTheme()
}

/** El tema de la app: oscuro o claro según [dark]. */
@Composable
fun KdTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) KdDark else KdLight,
        typography = KdTypography,
        shapes = KdShapes,
        content = content,
    )
}
