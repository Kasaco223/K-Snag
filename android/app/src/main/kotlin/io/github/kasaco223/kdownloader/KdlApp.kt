// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader

import android.app.Application
import android.os.StrictMode
import io.github.kasaco223.kdownloader.download.DownloadQueue
import io.github.kasaco223.kdownloader.engine.KdEngine
import io.github.kasaco223.kdownloader.platform.NotificationChannels
import io.github.kasaco223.kdownloader.platform.UiPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * La Application de K-Downloader: arma el motor (uno por proceso) y los canales de notificación. En las
 * compilaciones debug activa StrictMode. La cola de descargas y los ajustes se suman más adelante.
 */
class KdlApp : Application() {
    /** Corrutinas que viven lo que el proceso (el motor, el servicio). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val engine: KdEngine by lazy { KdEngine(this, appScope) }

    /** La cola de descargas: lee kd/jobs.json la primera vez que se usa. */
    val queue: DownloadQueue by lazy { DownloadQueue(this, engine) }

    /** Preferencias de la interfaz (formato principal). */
    val uiPrefs: UiPrefs by lazy { UiPrefs(this) }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) enableStrictMode()
        NotificationChannels.ensure(this)
        engine.warmUp() // solo revisa marcas y hashes, en segundo plano: nunca descomprime ni usa la red
    }

    /** Avisa en logcat (sin cerrar la app) de disco o red en el hilo principal y de recursos sin cerrar. */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .detectActivityLeaks()
                .detectFileUriExposure()
                .penaltyLog()
                .build(),
        )
    }
}
