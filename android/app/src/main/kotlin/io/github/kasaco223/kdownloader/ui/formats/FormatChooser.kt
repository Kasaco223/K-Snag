// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.ui.formats

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.kasaco223.kdownloader.R
import io.github.kasaco223.kdownloader.platform.UiPrefs
import io.github.kasaco223.kdownloader.ui.components.GradientButton

/** Cómo se presenta cada formato: ícono, verbo del botón grande, detalle y nombre corto (chip, listas). */
data class FormatInfo(
    val option: String,
    @param:DrawableRes val icon: Int,
    @param:StringRes val action: Int,
    @param:StringRes val detail: Int,
    @param:StringRes val label: Int,
)

fun formatInfo(option: String): FormatInfo = when (option) {
    "max" -> FormatInfo(option, R.drawable.ic_sparkle, R.string.format_action_max, R.string.format_detail_max, R.string.option_max)
    "mp3" -> FormatInfo(option, R.drawable.ic_music, R.string.format_action_mp3, R.string.format_detail_mp3, R.string.option_mp3)
    "m4a" -> FormatInfo(option, R.drawable.ic_headphones, R.string.format_action_m4a, R.string.format_detail_m4a, R.string.option_m4a)
    else -> FormatInfo("recommended", R.drawable.ic_video, R.string.format_action_recommended, R.string.format_detail_recommended, R.string.option_recommended)
}

/** El ícono de lo que quedó en un archivo: audio o video. */
@DrawableRes
fun fallbackIcon(option: String): Int = formatInfo(option).icon

/**
 * Elegir el formato con un toque: el botón grande baja en el formato principal ([favorite], se cambia en
 * Ajustes) y los chips ofrecen los otros tres. Inicio y la hoja de compartir usan el mismo.
 */
@Composable
fun FormatChooser(favorite: String, onPick: (String) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val fav = formatInfo(favorite)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GradientButton(onClick = { onPick(fav.option) }, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (enabled) 0.2f else 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_downloads), contentDescription = null)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(fav.action), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                Text(
                    text = stringResource(R.string.format_favorite_detail, stringResource(fav.detail)),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.88f),
                )
            }
        }
        Text(
            text = stringResource(R.string.format_other),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (option in UiPrefs.OPTIONS) {
                if (option == fav.option) continue
                val info = formatInfo(option)
                AssistChip(
                    onClick = { onPick(option) },
                    enabled = enabled,
                    label = { Text(stringResource(info.label)) },
                    leadingIcon = { Icon(painterResource(info.icon), contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                    shape = RoundedCornerShape(20.dp),
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        leadingIconContentColor = MaterialTheme.colorScheme.tertiary,
                    ),
                )
            }
        }
    }
}
