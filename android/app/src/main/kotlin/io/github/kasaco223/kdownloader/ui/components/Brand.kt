// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kasaco223.kdownloader.R

/**
 * Los degradados de la app de Windows (tokens.css). El de la marca termina en coral y solo decora (logo, barra de
 * progreso); los botones con texto usan el del CTA, donde el blanco tiene más de 5:1 en toda la superficie.
 */
object KdBrushes {
    val brand = Brush.linearGradient(listOf(Color(0xFF6366F1), Color(0xFF8B5CF6), Color(0xFFF97362)))
    val brandHorizontal = Brush.horizontalGradient(listOf(Color(0xFF6366F1), Color(0xFF8B5CF6), Color(0xFFF97362)))
    val cta = Brush.linearGradient(listOf(Color(0xFF5A54E6), Color(0xFF7C3AED)))
}

/** Botón principal con el degradado del CTA. El contenido va en blanco. */
@Composable
fun GradientButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(18.dp),
    minHeight: Dp = 56.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = minHeight)
            .clip(shape)
            .then(
                if (enabled) Modifier.background(KdBrushes.cta) else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    ) {
        CompositionLocalProvider(LocalContentColor provides if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant) {
            content()
        }
    }
}

/** El logo y el nombre, para la cabecera de Inicio y de la hoja de compartir. */
@Composable
fun BrandHeader(modifier: Modifier = Modifier, logoSize: Dp = 36.dp, label: String? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(logoSize))
        Text(
            text = label ?: "K-Snag",
            style = if (label == null) MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.labelLarge,
            color = if (label == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Barra de progreso con el degradado de la marca; [progress] null = en espera (barra tenue). */
@Composable
fun BrandProgressBar(progress: Float?, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        if (progress != null) {
            Box(
                Modifier
                    .fillMaxWidth(progress.coerceIn(0.02f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(height / 2))
                    .background(KdBrushes.brandHorizontal),
            )
        }
    }
}

/** Etiqueta chica sobre una miniatura (duración, calidad). */
@Composable
fun OverlayBadge(text: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (accent) Color(0xE66366F1) else Color(0xB3000000))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
    )
}
