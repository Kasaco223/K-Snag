// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
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
import io.github.kasaco223.kdownloader.platform.NotificationAccess
import io.github.kasaco223.kdownloader.ui.components.LocalThumbnails
import io.github.kasaco223.kdownloader.ui.home.queuedManyText
import io.github.kasaco223.kdownloader.ui.home.requestFor
import io.github.kasaco223.kdownloader.ui.jobs.rememberDownloadStarter
import io.github.kasaco223.kdownloader.ui.theme.KdTheme
import io.github.kasaco223.kdownloader.ui.theme.darkFor

/**
 * Destino de «Compartir» (texto plano) y de la selección de texto (PROCESS_TEXT). Es pública: todo lo que
 * llega se trata como datos no confiables y solo se muestra el primer enlace http(s) válido.
 *
 * Manifiesto: translúcida, singleTask con taskAffinity vacío y fuera de Recientes; sin screenOrientation
 * (API 26 lo rechaza en actividades translúcidas) ni noHistory.
 */
class QuickDownloadActivity : AppCompatActivity() {
    private val viewModel: QuickDownloadViewModel by viewModels { QuickDownloadViewModel.Factory }

    // La pregunta del permiso de notificaciones está abierta: la hoja espera a que se cierre para cerrarse.
    private var promptOpen = false
    private var finishAfterPrompt = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        promptOpen = savedInstanceState?.getBoolean(STATE_PROMPT_OPEN) ?: false
        finishAfterPrompt = savedInstanceState?.getBoolean(STATE_FINISH_AFTER) ?: false
        enableEdgeToEdge()
        if (savedInstanceState == null) viewModel.reset(IncomingLinks.from(intent))
        (application as KdlApp).engine.prepare() // para que el motor esté listo cuando lleguen las descargas
        setContent {
            val theme by (application as KdlApp).uiPrefs.theme.collectAsStateWithLifecycle()
            KdTheme(dark = darkFor(theme)) {
                val link by viewModel.link.collectAsStateWithLifecycle()
                val favorite by (application as KdlApp).uiPrefs.favorite.collectAsStateWithLifecycle()
                val analysis by viewModel.analysis.state.collectAsStateWithLifecycle()
                val queue = (application as KdlApp).queue
                val start = rememberDownloadStarter(
                    enqueue = { r -> r.urls?.let { queue.enqueueAll(it, r.option, r.allowDuplicate) } ?: queue.enqueue(r.url, r.option, r.allowDuplicate, r.prefill) },
                    onResult = { _, result -> onEnqueued(result) },
                    onNotificationPrompt = ::onNotificationPrompt,
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PROMPT_OPEN, promptOpen)
        outState.putBoolean(STATE_FINISH_AFTER, finishAfterPrompt)
    }

    override fun onStop() {
        super.onStop()
        // Nunca queda escondida detrás de otra app: si la persona sale, la hoja se cierra. Con la pregunta de
        // permisos encima no, porque el resultado tiene que volver a esta hoja.
        if (!isChangingConfigurations && !promptOpen) finish()
    }

    private fun onNotificationPrompt(open: Boolean) {
        promptOpen = open
        if (!open) {
            if (!NotificationAccess.progressVisible(this)) {
                Toast.makeText(this, R.string.notif_off_toast, Toast.LENGTH_LONG).show()
            }
            if (finishAfterPrompt) finish()
        }
    }

    /**
     * La descarga sigue en segundo plano: un aviso corto y la hoja se cierra (si la pregunta de notificaciones
     * está abierta, al responderla). Si el avance no se va a ver, el aviso lo dice.
     */
    private fun onEnqueued(result: EnqueueResult) {
        val queued = result is EnqueueResult.Queued || (result is EnqueueResult.QueuedMany && result.queued > 0)
        val hidden = queued && !promptOpen && !NotificationAccess.progressVisible(this)
        val text = when (result) {
            is EnqueueResult.Queued -> getString(if (hidden) R.string.download_queued_no_notif else R.string.download_queued)
            is EnqueueResult.QueuedMany -> queuedManyText(resources, result) + if (hidden) " " + getString(R.string.notif_off_toast) else ""
            is EnqueueResult.AlreadyInDownloads -> getString(R.string.download_already)
            is EnqueueResult.Rejected -> getString(if (result.code == "storage_permission") R.string.download_storage_denied else R.string.download_invalid)
        }
        Toast.makeText(this, text, if (hidden) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        if (result !is EnqueueResult.Rejected || result.code != "storage_permission") {
            if (promptOpen) finishAfterPrompt = true else finish()
        }
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private companion object {
        const val STATE_PROMPT_OPEN = "kd_prompt_open"
        const val STATE_FINISH_AFTER = "kd_finish_after_prompt"
    }
}
