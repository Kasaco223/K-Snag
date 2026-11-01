// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.share

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kasaco223.kdownloader.R
import io.github.kasaco223.kdownloader.core.Urls
import io.github.kasaco223.kdownloader.ui.analyze.AnalysisState
import io.github.kasaco223.kdownloader.ui.analyze.MediaPreview
import io.github.kasaco223.kdownloader.ui.analyze.isLive
import io.github.kasaco223.kdownloader.ui.analyze.playlistUrls
import io.github.kasaco223.kdownloader.ui.components.BrandHeader
import io.github.kasaco223.kdownloader.ui.formats.FormatChooser

/**
 * Hoja inferior de la actividad translúcida que aparece al compartir: el enlace que recibió, un botón grande con
 * el formato principal y los otros tres como chips. Elegir uno encola la descarga y cierra la hoja; sigue en
 * segundo plano con su notificación.
 */
@Composable
fun QuickDownloadSheet(
    link: SharedLink,
    favorite: String,
    analysis: AnalysisState,
    onRetryAnalysis: () -> Unit,
    onPlaylistMode: (Boolean) -> Unit,
    onDownload: (String) -> Unit,
    onOpenApp: () -> Unit,
    onClose: () -> Unit,
) {
    val title = stringResource(R.string.quick_pane_title)
    val appear = remember { MutableTransitionState(false).apply { targetState = true } }
    Box(Modifier.fillMaxSize()) {
        // Fondo oscurecido: tocar fuera de la hoja la cierra. No es un control (TalkBack usa «Cerrar»).
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                .pointerInput(onClose) { detectTapGestures { onClose() } },
        )
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(top = 24.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AnimatedVisibility(visibleState = appear, enter = slideInVertically { it } + fadeIn()) {
                Surface(
                    modifier = Modifier
                        .widthIn(max = 640.dp)
                        .fillMaxWidth()
                        .semantics { paneTitle = title },
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shadowElevation = 8.dp,
                ) {
                    Column(
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(
                            Modifier
                                .align(Alignment.CenterHorizontally)
                                .size(width = 36.dp, height = 4.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)),
                        )
                        BrandHeader(logoSize = 24.dp, label = stringResource(R.string.quick_header))
                        when (val url = link.url) {
                            null -> NoLinkContent()
                            else -> {
                                LinkContent(url = url, total = link.total)
                                MediaPreview(analysis, onRetryAnalysis, onPlaylistMode, compact = true)
                                val ready = (analysis as? AnalysisState.Ready)?.takeIf { it.url == url }?.analysis
                                FormatChooser(
                                    favorite = favorite,
                                    onPick = onDownload,
                                    enabled = !analysis.isLive,
                                    tiles = ready?.tiles,
                                    more = ready?.more.orEmpty(),
                                    playlistCount = ready?.takeIf { it.summary.isPlaylist }?.playlistUrls?.size,
                                )
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = onOpenApp) { Text(stringResource(R.string.quick_open_app)) }
                            TextButton(onClick = onClose) { Text(stringResource(R.string.common_close)) }
                        }
                    }
                }
            }
        }
    }
}

/** El enlace recibido: el sitio en grande y la dirección completa debajo (se puede seleccionar y copiar). */
@Composable
private fun LinkContent(url: String, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_video), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = Urls.host(url)?.removePrefix("www.")?.removePrefix("m.") ?: url,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            SelectionContainer {
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    if (total > 1) {
        Text(
            text = pluralStringResource(R.plurals.quick_more_links, total, total),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoLinkContent() {
    Text(
        text = stringResource(R.string.quick_no_link_title),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() },
    )
    Text(text = stringResource(R.string.quick_no_link_body), style = MaterialTheme.typography.bodyMedium)
}
