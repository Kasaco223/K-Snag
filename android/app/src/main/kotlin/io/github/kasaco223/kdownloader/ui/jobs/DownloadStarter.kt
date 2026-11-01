// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.ui.jobs

import android.Manifest
import android.content.Context
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
import io.github.kasaco223.kdownloader.download.DownloadQueue.EnqueueResult
import kotlinx.coroutines.launch

/** Un pedido de descarga: enlace, opción y si se acepta repetir una que ya está en Descargas. */
data class DownloadRequest(val url: String, val option: String, val allowDuplicate: Boolean = false)

/**
 * Encola una descarga desde un clic, pidiendo antes los permisos que hagan falta (contrato de la app con el
 * motor): en Android 8 y 9, escribir en Descargas (sin él no se encola); desde Android 13, después de la
 * primera descarga y una sola vez, mostrar notificaciones (sin él la descarga sigue, solo sin avisos).
 */
@Composable
fun rememberDownloadStarter(
    enqueue: suspend (DownloadRequest) -> EnqueueResult,
    onResult: (DownloadRequest, EnqueueResult) -> Unit,
): (DownloadRequest) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<DownloadRequest?>(null) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun afterQueued() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) return
        prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun run(request: DownloadRequest) {
        scope.launch {
            val result = enqueue(request)
            onResult(request, result)
            if (result is EnqueueResult.Queued) afterQueued()
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

private const val PREFS = "kd_ui"
private const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"
