// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.ui.jobs

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.kasaco223.kdownloader.download.DownloadQueue
import io.github.kasaco223.kdownloader.download.DownloadQueue.EnqueueResult
import io.github.kasaco223.kdownloader.platform.NotificationAccess
import kotlinx.coroutines.launch

/**
 * Un pedido de descarga: enlace, opción y si se acepta repetir una que ya está en Descargas. [prefill] viene del
 * análisis; [urls] es una lista entera (entonces [url] es el enlace de la lista).
 */
data class DownloadRequest(
    val url: String,
    val option: String,
    val allowDuplicate: Boolean = false,
    val prefill: DownloadQueue.Prefill? = null,
    val urls: List<String>? = null,
)

/**
 * Encola una descarga desde un clic, pidiendo antes los permisos que hagan falta (contrato de la app con el
 * motor): en Android 8 y 9, escribir en Descargas (sin él no se encola); desde Android 13, mostrar
 * notificaciones, justo después de encolar (sin él la descarga sigue igual, pero el avance solo se ve en la app).
 *
 * El permiso de notificaciones se pide en cada descarga mientras falte: Android deja de mostrar la pregunta
 * después de dos «No permitir», y desde ahí el aviso de Inicio lleva a los ajustes. [onNotificationPrompt]
 * avisa cuando la pregunta se abre (true) y cuando se cierra (false), siempre antes de [onResult] la primera
 * vez: la hoja de compartir la usa para no cerrarse con la pregunta abierta.
 */
@Composable
fun rememberDownloadStarter(
    enqueue: suspend (DownloadRequest) -> EnqueueResult,
    onNotificationPrompt: (open: Boolean) -> Unit = {},
    onResult: (DownloadRequest, EnqueueResult) -> Unit,
): (DownloadRequest) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<DownloadRequest?>(null) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onNotificationPrompt(false) }

    /** Abre la pregunta del permiso de notificaciones si hace falta; true si se abrió. */
    fun askNotifications(): Boolean {
        if (!NotificationAccess.needsPermission(context)) return false
        NotificationAccess.markAsked(context)
        onNotificationPrompt(true)
        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        return true
    }

    fun run(request: DownloadRequest) {
        scope.launch {
            val result = enqueue(request)
            if (result is EnqueueResult.Queued || (result is EnqueueResult.QueuedMany && result.queued > 0)) askNotifications()
            onResult(request, result)
        }
    }

    val storage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val request = pending
        pending = null
        if (request != null) {
            if (granted) run(request) else onResult(request, EnqueueResult.Rejected("storage_permission"))
        }
    }

    return { request ->
        val needsStorage = Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needsStorage) {
            pending = request
            storage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            run(request)
        }
    }
}
