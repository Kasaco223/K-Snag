// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.download

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.kasaco223.kdownloader.KdlApp
import io.github.kasaco223.kdownloader.MainActivity
import io.github.kasaco223.kdownloader.R
import io.github.kasaco223.kdownloader.core.download.StageLabeler
import io.github.kasaco223.kdownloader.platform.NotificationChannels
import io.github.kasaco223.kdownloader.ui.jobs.stageText
import java.util.Locale

/** Notificaciones de las descargas (diseño del motor §11, tabla de notificaciones). */
object DownloadNotifications {
    const val ACTIVE_ID = 1

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** La del servicio: título, etapa, porcentaje y velocidad; «Cancelar» va a [NotificationActionReceiver]. */
    fun active(context: Context, job: Job?): Notification {
        val b = NotificationCompat.Builder(context, NotificationChannels.ACTIVE)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(context))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (job == null) {
            return b.setContentTitle(context.getString(R.string.notif_downloads_title)).setProgress(0, 0, true).build()
        }
        val stage = StageLabeler.label(job.state, job.stepKind, job.stepIndex, job.stepTotal, job.postprocessor, job.option)
        val parts = mutableListOf(stageText(context.resources, stage))
        job.progress?.let { parts += String.format(Locale.getDefault(), "%d %%", (it * 100).toInt()) }
        job.speed?.takeIf { it > 0 }?.let { parts += Formatter.formatShortFileSize(context, it.toLong()) + "/s" }
        val determinate = job.state == "downloading" && job.progress != null
        val cancel = PendingIntent.getBroadcast(
            context, job.id.hashCode(),
            Intent(context, NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.ACTION_CANCEL)
                .putExtra(NotificationActionReceiver.EXTRA_JOB_ID, job.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return b.setContentTitle(job.title ?: job.url)
            .setContentText(parts.joinToString(" · "))
            .setProgress(100, ((job.progress ?: 0.0) * 100).toInt(), !determinate)
            .addAction(0, context.getString(R.string.notif_action_cancel), cancel)
            .build()
    }

    private fun canNotify(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** «Descarga lista»: tocarla abre el archivo con la app que la persona elija. */
    @SuppressLint("MissingPermission") // canNotify() comprueba POST_NOTIFICATIONS
    fun done(context: Context, item: HistoryItem) {
        if (!canNotify(context)) return
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(item.contentUri), item.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(context, item.id.hashCode(), view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, NotificationChannels.DONE)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.notif_done_title))
            .setContentText(item.displayName)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(item.id.hashCode(), n)
    }

    @SuppressLint("MissingPermission") // canNotify() comprueba POST_NOTIFICATIONS
    fun failed(context: Context, job: Job) {
        if (!canNotify(context)) return
        val lang = if (Locale.getDefault().language == "es") "es" else "en"
        val n = NotificationCompat.Builder(context, NotificationChannels.PROBLEMS)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.notif_failed_title))
            .setContentText(job.error?.title(lang) ?: (job.title ?: job.url))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(job.id.hashCode(), n)
    }
}

/** «Cancelar» de la notificación. No se exporta: solo la propia app le manda el PendingIntent. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL) return
        val id = intent.getStringExtra(EXTRA_JOB_ID) ?: return
        (context.applicationContext as KdlApp).queue.cancel(id)
    }

    companion object {
        const val ACTION_CANCEL = "io.github.kasaco223.kdownloader.action.CANCEL"
        const val EXTRA_JOB_ID = "kd.job_id"
    }
}
