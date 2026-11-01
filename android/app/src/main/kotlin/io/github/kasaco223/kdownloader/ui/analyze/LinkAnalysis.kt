// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.ui.analyze

import io.github.kasaco223.kdownloader.download.Analysis
import io.github.kasaco223.kdownloader.download.JobError
import io.github.kasaco223.kdownloader.download.MediaAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** El estado del análisis del enlace que se está mirando (Inicio o la hoja de compartir). */
sealed interface AnalysisState {
    data object Idle : AnalysisState

    data class Loading(val url: String, val playlist: Boolean) : AnalysisState

    data class Ready(val url: String, val analysis: Analysis) : AnalysisState

    data class Failed(val url: String, val playlist: Boolean, val error: JobError) : AnalysisState
}

/**
 * Analiza el enlace de una pantalla en el scope de su ViewModel: pedir el mismo enlace otra vez no repite el
 * análisis, uno nuevo cancela al anterior y [clear] lo corta.
 */
class LinkAnalysis(private val scope: CoroutineScope, private val analyzer: MediaAnalyzer) {
    private val _state = MutableStateFlow<AnalysisState>(AnalysisState.Idle)
    val state: StateFlow<AnalysisState> = _state.asStateFlow()
    private var job: Job? = null

    /** [playlist] null: el modo que ya tenía este enlace (al volver a la pantalla), o el que sugiere la URL. */
    fun analyze(url: String, playlist: Boolean? = null, force: Boolean = false) {
        val current = _state.value
        val mode = playlist ?: when {
            current is AnalysisState.Loading && current.url == url -> current.playlist
            current is AnalysisState.Ready && current.url == url -> current.analysis.summary.isPlaylist
            current is AnalysisState.Failed && current.url == url -> current.playlist
            else -> MediaAnalyzer.isPlaylistUrl(url)
        }
        start(url, mode, force)
    }

    private fun start(url: String, playlist: Boolean, force: Boolean) {
        val current = _state.value
        val same = when (current) {
            is AnalysisState.Loading -> current.url == url && current.playlist == playlist
            is AnalysisState.Ready -> current.url == url && current.analysis.summary.isPlaylist == playlist
            is AnalysisState.Failed -> current.url == url && current.playlist == playlist // «Reintentar» usa force
            AnalysisState.Idle -> false
        }
        if (same && !force) return
        job?.cancel()
        _state.value = AnalysisState.Loading(url, playlist)
        job = scope.launch {
            _state.value = when (val r = analyzer.analyze(url, playlist)) {
                is MediaAnalyzer.Result.Ok -> AnalysisState.Ready(url, r.analysis)
                is MediaAnalyzer.Result.Failed -> AnalysisState.Failed(url, playlist, r.error)
            }
        }
    }

    fun retry() {
        (_state.value as? AnalysisState.Failed)?.let { analyze(it.url, it.playlist, force = true) }
    }

    fun clear() {
        job?.cancel()
        job = null
        _state.value = AnalysisState.Idle
    }
}
