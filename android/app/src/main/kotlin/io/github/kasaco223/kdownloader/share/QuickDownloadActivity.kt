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
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kasaco223.kdownloader.KdlApp
import io.github.kasaco223.kdownloader.MainActivity
import io.github.kasaco223.kdownloader.R
import io.github.kasaco223.kdownloader.download.DownloadQueue.EnqueueResult
import io.github.kasaco223.kdownloader.ui.jobs.DownloadRequest
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
                val queue = (application as KdlApp).queue
                val start = rememberDownloadStarter(
                    enqueue = { queue.enqueue(it.url, it.option, it.allowDuplicate) },
                    onResult = { _, result -> onEnqueued(result) },
                )
                QuickDownloadSheet(
                    link = link,
                    favorite = favorite,
                    onDownload = { option -> link.url?.let { start(DownloadRequest(it, option)) } },
                    onOpenApp = ::openApp,
                    onClose = ::finish,
                )
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
            is EnqueueResult.Queued -> R.string.download_queued
            is EnqueueResult.AlreadyInDownloads -> R.string.download_already
            is EnqueueResult.Rejected -> if (result.code == "storage_permission") R.string.download_storage_denied else R.string.download_invalid
        }
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        if (result !is EnqueueResult.Rejected || result.code != "storage_permission") finish()
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
