// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kasaco223.kdownloader.KdlApp
import io.github.kasaco223.kdownloader.MainActivity
import io.github.kasaco223.kdownloader.R
import io.github.kasaco223.kdownloader.download.DownloadQueue.EnqueueResult
import io.github.kasaco223.kdownloader.ui.components.LocalThumbnails
import io.github.kasaco223.kdownloader.ui.home.queuedManyText
import io.github.kasaco223.kdownloader.ui.home.requestFor
import io.github.kasaco223.kdownloader.ui.jobs.rememberDownloadStarter
import io.github.kasaco223.kdownloader.ui.theme.KdTheme

/**
 * Destino de «Compartir» (texto plano) y de la selección de texto (PROCESS_TEXT). Es pública: todo lo que
 * llega se trata como datos no confiables y solo se muestra el primer enlace http(s) válido.
 *
 * Manifiesto: translúcida, singleTask con taskAffinity vacío y fuera de Recientes; sin screenOrientation
 * (API 26 lo rechaza en actividades translúcidas) ni noHistory.
 */
class QuickDownloadActivity : AppCompatActivity() {
    private val viewModel: QuickDownloadViewModel by viewModels { QuickDownloadViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) viewModel.reset(IncomingLinks.from(intent))
        (application as KdlApp).engine.prepare() // para que el motor esté listo cuando lleguen las descargas
        setContent {
            KdTheme {
                val link by viewModel.link.collectAsStateWithLifecycle()
                val favorite by (application as KdlApp).uiPrefs.favorite.collectAsStateWithLifecycle()
                val analysis by viewModel.analysis.state.collectAsStateWithLifecycle()
                val queue = (application as KdlApp).queue
                val start = rememberDownloadStarter(
                    enqueue = { r -> r.urls?.let { queue.enqueueAll(it, r.option, r.allowDuplicate) } ?: queue.enqueue(r.url, r.option, r.allowDuplicate, r.prefill) },
                    onResult = { _, result -> onEnqueued(result) },
                )
                CompositionLocalProvider(LocalThumbnails provides queue.thumbnails) {
                    QuickDownloadSheet(
                        link = link,
                        favorite = favorite,
                        analysis = analysis,
                        onRetryAnalysis = viewModel.analysis::retry,
                        onPlaylistMode = { playlist -> link.url?.let { viewModel.analysis.analyze(it, playlist) } },
                        onDownload = { option -> link.url?.let { start(requestFor(it, option, analysis)) } },
                        onOpenApp = ::openApp,
                        onClose = ::finish,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.reset(IncomingLinks.from(intent))
    }

    override fun onStop() {
        super.onStop()
        // Nunca queda escondida detrás de otra app: si la persona sale, la hoja se cierra.
        if (!isChangingConfigurations) finish()
    }

    /** La descarga sigue en segundo plano: un aviso corto y la hoja se cierra. */
    private fun onEnqueued(result: EnqueueResult) {
        val text = when (result) {
            is EnqueueResult.Queued -> getString(R.string.download_queued)
            is EnqueueResult.QueuedMany -> queuedManyText(resources, result)
            is EnqueueResult.AlreadyInDownloads -> getString(R.string.download_already)
            is EnqueueResult.Rejected -> getString(if (result.code == "storage_permission") R.string.download_storage_denied else R.string.download_invalid)
        }
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        if (result !is EnqueueResult.Rejected || result.code != "storage_permission") finish()
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
