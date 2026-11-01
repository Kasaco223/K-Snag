// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.kasaco223.kdownloader.R
import io.github.kasaco223.kdownloader.download.HistoryItem
import io.github.kasaco223.kdownloader.download.Job
import io.github.kasaco223.kdownloader.ui.home.EmptyDownloads
import io.github.kasaco223.kdownloader.ui.jobs.HistoryRow
import io.github.kasaco223.kdownloader.ui.jobs.JobRow
import io.github.kasaco223.kdownloader.ui.jobs.ListHeader

private enum class Filter(val label: Int) {
    ALL(R.string.downloads_filter_all),
    VIDEO(R.string.downloads_filter_video),
    AUDIO(R.string.downloads_filter_audio),
}

/**
 * Pestaña Descargas: lo que está bajando, lo que falló y todo lo terminado (el historial, máximo 500), con
 * filtro de video o audio. Quitar algo de la lista nunca borra el archivo de Descargas/K-Downloader.
 */
@Composable
fun DownloadsScreen(
    padding: PaddingValues,
    jobs: List<Job>,
    history: List<HistoryItem>,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearFinished: () -> Unit,
    onOpenItem: (HistoryItem) -> Unit,
    onRemoveHistory: (String) -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    // Lo terminado está en el historial; aquí van los trabajos que siguen o que necesitan algo.
    val pending = jobs.filter { it.state != "done" }.sortedByDescending { it.createdAt }
    val shown = when (filter) {
        Filter.ALL -> history
        Filter.VIDEO -> history.filterNot { it.isAudio }
        Filter.AUDIO -> history.filter { it.isAudio }
    }
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                    Text(
                        text = stringResource(R.string.tab_downloads),
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.semantics { heading() },
                    )
                    if (history.isNotEmpty()) {
                        Text(
                            text = pluralStringResource(R.plurals.downloads_count, history.size, history.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (pending.isEmpty() && history.isEmpty()) {
                item { EmptyDownloads() }
            }
            if (pending.isNotEmpty()) {
                item {
                    val canClear = pending.any { it.isTerminal } // con error o canceladas
                    ListHeader(stringResource(R.string.downloads_running), if (canClear) stringResource(R.string.downloads_clear) else null, onClearFinished)
                }
                items(pending, key = { "job-" + it.id }) { JobRow(it, onCancel, onRetry, onRemove) }
            }
            if (history.isNotEmpty()) {
                item { ListHeader(stringResource(R.string.downloads_done)) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (f in Filter.entries) {
                            FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(stringResource(f.label)) })
                        }
                    }
                }
                if (shown.isEmpty()) {
                    item {
                        Text(stringResource(R.string.downloads_none_filtered), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(shown, key = { "done-" + it.id }) { HistoryRow(it, onOpenItem, onRemoveHistory) }
                item {
                    Text(
                        text = stringResource(R.string.downloads_history_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                }
            }
        }
    }
}
