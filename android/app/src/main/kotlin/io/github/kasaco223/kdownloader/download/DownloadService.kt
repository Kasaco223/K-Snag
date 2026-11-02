// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.download

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.kasaco223.kdownloader.KdlApp
import io.github.kasaco223.kdownloader.MainActivity
import io.github.kasaco223.kdownloader.R
import io.github.kasaco223.kdownloader.contracts.EngineStatus
import io.github.kasaco223.kdownloader.platform.NotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Servicio en primer plano (tipo dataSync) del motor y las descargas. No se exporta.
 * - [ACTION_PREPARE]: prepara el motor (descomprimir Python y FFmpeg tarda; sigue aunque la persona salga).
 * - [ACTION_RUN]: corre la cola, una descarga a la vez, hasta vaciarla.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var prepareWork: Job? = null
    private var runWork: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotify = 0L

    // En el hilo principal: qué trabajo sigue en curso (el servicio se detiene cuando ninguno).
    private var preparing = false
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationChannels.ensure(this)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        val app = application as KdlApp
        val initial = if (intent?.action == ACTION_RUN) DownloadNotifications.active(this, null) else preparing(null)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, initial, type)
        when (intent?.action) {
            ACTION_RUN -> if (!running) startRun(app)
            else -> if (!preparing && !running) {
                preparing = true
                prepareWork = scope.launch { prepare(app) }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun prepare(app: KdlApp) {
        val engine = app.engine
        val updates = scope.launch {
            engine.status.collectLatest { s ->
                if (s is EngineStatus.Preparing) notify(preparing(s.progress))
            }
        }
        engine.ensureReady()
        updates.cancel()
        preparing = false
        stopIfIdle()
    }

    private fun startRun(app: KdlApp) {
        running = true
        runWork = scope.launch { runQueue(app) }
    }

    private suspend fun runQueue(app: KdlApp) {
        val queue = app.queue
        val updates = scope.launch {
            queue.jobs.collectLatest { list ->
                val now = System.currentTimeMillis()
                val active = list.firstOrNull { it.isActive }
                if (now - lastNotify >= 1_000 || active == null) {
                    lastNotify = now
                    notify(DownloadNotifications.active(this@DownloadService, active))
                }
            }
        }
        withContext(Dispatchers.IO) {
            while (true) {
                if (!queue.networkAllowed()) break // «Solo con Wi-Fi»: KdlApp avisa cuando vuelve el Wi-Fi
                val job = queue.nextQueued() ?: break
                acquireWakeLock()
                try {
                    queue.runJob(job)
                } finally {
                    releaseWakeLock()
                }
            }
        }
        updates.cancel()
        running = false
        stopIfIdle()
    }

    private fun stopIfIdle() {
        if (preparing || running) return
        val app = application as KdlApp
        if (app.queue.hasQueued() && app.queue.networkAllowed()) { // llegó algo mientras terminaba la vuelta anterior
            startRun(app)
            return
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notify(n: Notification) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, n)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kdownloader:download").apply {
            setReferenceCounted(false)
            acquire(30 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    /**
     * Android 15+: se acabó el tiempo de dataSync (6 h por día). Lo que corre queda «interrumpido» sin perder lo
     * bajado y se retoma la próxima vez que se abra la app.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as KdlApp).queue.interruptActive()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun preparing(progress: Float?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, NotificationChannels.ACTIVE)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.notif_preparing_title))
            .setContentText(getString(R.string.notif_preparing_text))
            .setProgress(100, ((progress ?: 0f) * 100).toInt(), progress == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_PREPARE = "io.github.kasaco223.kdownloader.action.PREPARE"
        const val ACTION_RUN = "io.github.kasaco223.kdownloader.action.RUN"
        const val NOTIFICATION_ID = DownloadNotifications.ACTIVE_ID
    }
}
