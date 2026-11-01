// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.ui.formats

import android.content.Context
import android.text.format.Formatter
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.kasaco223.kdownloader.R
import io.github.kasaco223.kdownloader.core.media.Tile
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

/** "4K60", "1080p", "1440p60 HDR". */
fun tileQuality(t: Tile): String? {
    val h = t.height ?: return null
    val base = when {
        h >= 4320 -> "8K"
        h >= 2160 -> "4K"
        else -> "${h}p"
    }
    val fps = t.fps?.takeIf { it > 30 }?.toString() ?: ""
    return base + fps + if (t.hdr) " HDR" else ""
}

/** "≈ 268 MB" o "10 MB"; null sin tamaño. */
fun tileSize(context: Context, t: Tile): String? = t.size?.let { (if (t.approx) "≈ " else "") + Formatter.formatShortFileSize(context, it) }

/** "MP4 · 1080p60 · ≈ 268 MB" (contenedor, calidad y tamaño, lo que se sepa). */
fun tileDetail(context: Context, t: Tile): String =
    listOfNotNull(t.container.uppercase(), tileQuality(t), tileSize(context, t)).joinToString(" · ")

/**
 * Elegir el formato con un toque: el botón grande baja en el formato principal ([favorite], se cambia en
 * Ajustes) y los chips ofrecen los otros tres. Inicio y la hoja de compartir usan el mismo.
 *
 * Con el análisis ([tiles]) se ve la calidad y el tamaño reales, lo que no existe queda deshabilitado (un audio
 * no tiene video) y [more] agrega «Más calidades». [playlistCount]: es una lista y se baja entera.
 */
@Composable
fun FormatChooser(
    favorite: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tiles: List<Tile>? = null,
    more: List<Tile> = emptyList(),
    playlistCount: Int? = null,
) {
    val context = LocalContext.current
    fun tile(key: String) = tiles?.firstOrNull { it.key == key }
    fun usable(key: String) = tile(key)?.available ?: true
    val bigKey = if (usable(favorite)) favorite else UiPrefs.OPTIONS.firstOrNull { usable(it) } ?: favorite
    val big = formatInfo(bigKey)
    val bigTile = tile(bigKey)
    val bigDetail = when {
        playlistCount != null -> pluralStringResource(R.plurals.format_playlist_detail, playlistCount, stringResource(big.label), playlistCount)
        bigTile != null && (bigTile.height != null || bigTile.size != null) -> tileDetail(context, bigTile)
        else -> stringResource(big.detail)
    }
    var showMore by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val bigEnabled = enabled && usable(bigKey)
        GradientButton(onClick = { onPick(bigKey) }, enabled = bigEnabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (bigEnabled) 0.2f else 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_downloads), contentDescription = null)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(big.action), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                Text(
                    text = if (bigKey == favorite) stringResource(R.string.format_favorite_detail, bigDetail) else bigDetail,
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
                if (option == bigKey) continue
                val info = formatInfo(option)
                val t = tile(option)
                val extra = if (t != null && playlistCount == null) listOfNotNull(tileQuality(t), tileSize(context, t)) else emptyList()
                FormatChip(
                    text = (listOf(stringResource(info.label)) + extra).joinToString(" · "),
                    icon = info.icon,
                    enabled = enabled && usable(option),
                    onClick = { onPick(option) },
                )
            }
        }
        if (more.isNotEmpty() && playlistCount == null) {
            TextButton(onClick = { showMore = !showMore }) {
                Text(stringResource(R.string.format_more))
                Icon(
                    painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(if (showMore) 270f else 90f),
                )
            }
            if (showMore) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (t in more) {
                        FormatChip(
                            text = listOfNotNull(tileQuality(t), t.container.uppercase(), tileSize(context, t)).joinToString(" · "),
                            icon = R.drawable.ic_video,
                            enabled = enabled,
                            onClick = { onPick(t.key) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FormatChip(text: String, @DrawableRes icon: Int, enabled: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(text) },
        leadingIcon = { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
        shape = RoundedCornerShape(20.dp),
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            leadingIconContentColor = MaterialTheme.colorScheme.tertiary,
        ),
    )
}
