// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.ui.analyze

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kasaco223.kdownloader.R
import io.github.kasaco223.kdownloader.core.Urls
import io.github.kasaco223.kdownloader.download.Analysis
import io.github.kasaco223.kdownloader.download.DownloadQueue
import io.github.kasaco223.kdownloader.ui.components.DurationBadge
import io.github.kasaco223.kdownloader.ui.components.Thumb

/** Lo que la cola necesita saber de un análisis para mostrar la tarjeta enseguida. */
fun Analysis.prefill() = DownloadQueue.Prefill(summary.title, summary.uploader, summary.duration, summary.thumbnail, thumbId)

/** Una lista que se bajaría entera ([DownloadQueue.MAX_BATCH] como mucho). */
val Analysis.playlistUrls: List<String> get() = summary.entries.mapNotNull { it.url }.take(DownloadQueue.MAX_BATCH)

/** El análisis terminó en un directo: no se puede bajar todavía. */
val AnalysisState.isLive: Boolean get() = (this as? AnalysisState.Ready)?.analysis?.summary?.isLive == true

/**
 * La tarjeta del enlace mientras se analiza y cuando ya se sabe qué es: miniatura, título, canal y duración, o
 * la lista con sus videos. Los avisos (calidad baja, directo) y los errores van acá; el selector de formato,
 * debajo, funciona sin esperar.
 */
@Composable
fun MediaPreview(
    state: AnalysisState,
    onRetry: () -> Unit,
    onPlaylistMode: (Boolean) -> Unit,
    compact: Boolean = false,
) {
    when (state) {
        AnalysisState.Idle -> Unit
        is AnalysisState.Loading -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(if (compact) 72.dp else 112.dp)
                    .aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.analyze_loading), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.analyze_loading_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        is AnalysisState.Failed -> Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            val lang = if (LocalConfiguration.current.locales[0].language == "es") "es" else "en"
            Text(state.error.title(lang), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            Text(state.error.message(lang), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onRetry) {
                Icon(painterResource(R.drawable.ic_retry), contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.analyze_retry), modifier = Modifier.padding(start = 6.dp))
            }
        }
        is AnalysisState.Ready -> {
            val a = state.analysis
            if (a.summary.isPlaylist) PlaylistPreview(a, onSingle = { onPlaylistMode(false) }) else SinglePreview(a, compact, onWholeList = { onPlaylistMode(true) })
        }
    }
}

@Composable
private fun SinglePreview(a: Analysis, compact: Boolean, onWholeList: () -> Unit) {
    val s = a.summary
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (compact) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Thumb(a.thumbId, R.drawable.ic_video, Modifier.width(112.dp).aspectRatio(16f / 9f), corner = 10.dp, iconSize = 22.dp) {
                    DurationBadge(s.duration?.toLong())
                }
                TitleBlock(a, Modifier.weight(1f))
            }
        } else {
            Thumb(a.thumbId, R.drawable.ic_video, Modifier.fillMaxWidth().aspectRatio(16f / 9f), corner = 14.dp, iconSize = 36.dp) {
                DurationBadge(s.duration?.toLong())
            }
            TitleBlock(a)
        }
        if (s.isLive) Callout(stringResource(R.string.analyze_live), error = true)
        if (a.degraded) Callout(stringResource(R.string.analyze_degraded))
        if (Urls.hasListParam(a.input)) {
            TextButton(onClick = onWholeList) { Text(stringResource(R.string.analyze_whole_list)) }
        }
    }
}

@Composable
private fun TitleBlock(a: Analysis, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(a.summary.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            text = listOf(a.summary.uploader, a.site).filter { it.isNotBlank() }.distinct().joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PlaylistPreview(a: Analysis, onSingle: () -> Unit) {
    val s = a.summary
    val count = s.entries.size
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val total = s.totalDuration.toLong().takeIf { it > 0 }?.let { DateUtils.formatElapsedTime(it) }
        Text(
            text = listOfNotNull(pluralStringResource(R.plurals.analyze_playlist_videos, count, count), total, s.uploader.takeIf { it.isNotBlank() }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        s.entries.take(3).forEachIndexed { i, e ->
            Text("${i + 1}. ${e.title ?: e.id ?: ""}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (count > DownloadQueue.MAX_BATCH) Callout(stringResource(R.string.analyze_playlist_cap, DownloadQueue.MAX_BATCH))
        if (Urls.guessVideoId(a.input) != null) {
            TextButton(onClick = onSingle) { Text(stringResource(R.string.analyze_only_video)) }
        }
    }
}

@Composable
private fun Callout(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}
