// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import io.github.kasaco223.kdownloader.R

/**
 * Canales de notificación (contrato B.2). Crearlos es idempotente: Android solo actualiza nombre y descripción.
 * En esta versión solo existe el del trabajo en curso; los de descargas listas, problemas y actualizaciones
 * llegan con sus funciones.
 */
object NotificationChannels {
    /** Trabajo en curso (preparar el motor, descargar): prioridad baja, sin sonido. Notificación id 1. */
    const val ACTIVE = "kd.active"

    fun ensure(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(ACTIVE, context.getString(R.string.notif_channel_active), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.notif_channel_active_desc)
                setShowBadge(false)
            },
        )
    }
}
