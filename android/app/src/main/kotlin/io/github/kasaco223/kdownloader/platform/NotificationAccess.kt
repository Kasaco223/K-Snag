// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.platform

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Si la persona puede ver el avance de las descargas en la notificación. Desde Android 13 hace falta el
 * permiso POST_NOTIFICATIONS; en cualquier versión se pueden apagar las notificaciones de la app o solo el
 * canal de descargas en curso. Sin eso la descarga sigue igual, pero el avance solo se ve dentro de la app.
 */
object NotificationAccess {
    private const val PREFS = "kd_ui"
    private const val KEY_ASKED = "asked_notifications"
    private const val KEY_HIDDEN = "notif_banner_hidden"

    fun permissionGranted(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Hay que pedir el permiso (Android 13+, todavía sin él). */
    fun needsPermission(context: Context): Boolean = !permissionGranted(context)

    /** El avance se ve: permiso, notificaciones de la app y canal de descargas en curso activos. */
    fun progressVisible(context: Context): Boolean {
        if (!permissionGranted(context)) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val channel = context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(NotificationChannels.ACTIVE)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markAsked(context: Context) = prefs(context).edit().putBoolean(KEY_ASKED, true).apply()

    /**
     * El aviso de Inicio: solo si el avance no se ve, si ya se le preguntó a la persona (o es Android 12 o
     * anterior, donde no hay pregunta) y si no lo cerró.
     */
    fun shouldWarn(context: Context): Boolean {
        if (progressVisible(context)) return false
        val p = prefs(context)
        if (p.getBoolean(KEY_HIDDEN, false)) return false
        return Build.VERSION.SDK_INT < 33 || p.getBoolean(KEY_ASKED, false)
    }

    fun hideWarning(context: Context) = prefs(context).edit().putBoolean(KEY_HIDDEN, true).apply()

    /** Abre los ajustes de notificaciones de la app (o su página de información). False si no se pudo. */
    fun openSettings(context: Context): Boolean {
        val intents = listOf(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
        )
        return intents.any { intent ->
            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        }
    }
}
