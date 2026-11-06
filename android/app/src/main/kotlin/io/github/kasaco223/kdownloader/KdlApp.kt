// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader

import android.app.Application
import android.os.StrictMode
import io.github.kasaco223.kdownloader.download.DownloadQueue
import io.github.kasaco223.kdownloader.download.MediaAnalyzer
import io.github.kasaco223.kdownloader.engine.KdEngine
import io.github.kasaco223.kdownloader.platform.NotificationChannels
import io.github.kasaco223.kdownloader.platform.UiPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import io.github.kasaco223.kdownloader.engine.HintsStore

/**
 * La Application de K-Snag: arma el motor (uno por proceso) y los canales de notificación. En las
 * compilaciones debug activa StrictMode. La cola de descargas y los ajustes se suman más adelante.
 */
class KdlApp : Application() {
    /** Corrutinas que viven lo que el proceso (el motor, el servicio). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val engine: KdEngine by lazy { KdEngine(this, appScope) }

    /** La cola de descargas: lee kd/jobs.json la primera vez que se usa. */
    val queue: DownloadQueue by lazy { DownloadQueue(this, engine, uiPrefs) }

    /** El análisis previo de los enlaces (uno a la vez). */
    val analyzer: MediaAnalyzer by lazy { MediaAnalyzer(engine, queue, uiPrefs) }

    /** Las actualizaciones de la app (Releases de GitHub). */
    val updater: io.github.kasaco223.kdownloader.update.AppUpdater by lazy { io.github.kasaco223.kdownloader.update.AppUpdater(this) }

    /** Las pistas remotas del motor (engine.json). */
    val hints: HintsStore by lazy { HintsStore(java.io.File(engine.dirs.root, "hints.json"), engine) }

    /** Con «Solo con Wi-Fi», cuando aparece una red sin límite de datos la cola arranca sola (mientras viva el proceso). */
    private fun watchUnmeteredNetwork() {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val request = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            .build()
        runCatching {
            cm.registerNetworkCallback(
                request,
                object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        if (uiPrefs.wifiOnly.value) queue.startService()
                    }
                },
            )
        }
    }

    /**
     * Mantenimiento al volver a la app, en segundo plano: pistas del motor (una vez al día) y, si toca y no hay
     * descargas, buscar una versión nueva de yt-dlp. Con «Solo con Wi-Fi», la búsqueda espera una red sin límite.
     */
    fun onForeground() {
        if (BuildConfig.SELF_UPDATE && uiPrefs.autoUpdateApp.value) updater.checkIfDue()
        appScope.launch(Dispatchers.IO) {
            hints.refreshIfDue()
            val channel = uiPrefs.engineChannel.value
            val metered = getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
            if (uiPrefs.autoUpdateEngine.value && engine.updateDue(channel) && queue.jobs.value.none { it.isActive } &&
                !(uiPrefs.wifiOnly.value && metered)
            ) {
                engine.updateYtdlp(channel)
            }
        }
    }

    /** Preferencias de la interfaz (formato principal). */
    val uiPrefs: UiPrefs by lazy { UiPrefs(this) }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) enableStrictMode()
        NotificationChannels.ensure(this)
        engine.warmUp() // solo revisa marcas y hashes, en segundo plano: nunca descomprime ni usa la red
        appScope.launch(Dispatchers.IO) { hints } // las pistas guardadas, para la cola y el análisis
        watchUnmeteredNetwork()
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
