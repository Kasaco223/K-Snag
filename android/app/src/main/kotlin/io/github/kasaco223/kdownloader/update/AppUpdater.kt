// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import io.github.kasaco223.kdownloader.BuildConfig
import io.github.kasaco223.kdownloader.KdlApp
import io.github.kasaco223.kdownloader.core.download.JsonStrict
import io.github.kasaco223.kdownloader.core.update.AppUpdate
import io.github.kasaco223.kdownloader.engine.EngineUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Actualizaciones de la app (sabor github, solo la versión publicada): busca en los punteros de la web, baja el
 * APK de su ABI verificando tamaño, SHA-256 y que esté firmado con la misma llave, y lo instala con
 * PackageInstaller. Android siempre pide confirmación (no hay actualizaciones silenciosas en v1); la primera vez,
 * además, el permiso «Instalar apps desconocidas» para K-Snag.
 */
class AppUpdater(private val app: KdlApp) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val update: AppUpdate.Available) : State
        data class Downloading(val update: AppUpdate.Available, val progress: Float) : State
        data class NeedsPermission(val update: AppUpdate.Available, val file: File) : State
        data class Installing(val update: AppUpdate.Available) : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var work: Job? = null
    private val prefs = app.getSharedPreferences("kd_update", Context.MODE_PRIVATE)

    /** Solo la versión publicada se actualiza sola: las de prueba (.debug, .qa) son otros paquetes y otra llave. */
    val supported: Boolean = BuildConfig.BUILD_TYPE == "release"

    /** Una vez al día, al abrir la app (si el ajuste está activo). */
    fun checkIfDue() {
        if (!supported || System.currentTimeMillis() - prefs.getLong(KEY_CHECKED, 0) < 24 * 3_600_000L) return
        check()
    }

    fun check() {
        if (!supported || work?.isActive == true) return
        work = app.appScope.launch(Dispatchers.IO) {
            _state.value = State.Checking
            val installed = installedCode()
            val found = AppUpdate.channels(BuildConfig.VERSION_NAME).map { file ->
                fetchPointer(file)?.let { AppUpdate.parse(it, installed, Build.VERSION.SDK_INT) }
            }
            prefs.edit().putLong(KEY_CHECKED, System.currentTimeMillis()).apply()
            _state.value = AppUpdate.pick(found, installed)?.let { State.Available(it) } ?: State.UpToDate
        }
    }

    /** Baja el APK y, si todo coincide, abre la instalación. */
    fun downloadAndInstall(update: AppUpdate.Available) {
        if (work?.isActive == true) return
        work = app.appScope.launch(Dispatchers.IO) {
            try {
                val file = download(update)
                verifySigner(file)
                install(update, file)
            } catch (e: IOException) {
                Log.w(TAG, "No se pudo actualizar", e)
                _state.value = State.Failed(e.message ?: e.toString())
            }
        }
    }

    /** Después de dar el permiso «Instalar apps desconocidas»: sigue con el mismo archivo. */
    fun resumeInstall() {
        val s = _state.value as? State.NeedsPermission ?: return
        app.appScope.launch(Dispatchers.IO) { install(s.update, s.file) }
    }

    /** La pantalla del sistema para dar el permiso a K-Snag. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    internal fun onInstallResult(status: Int, message: String?) {
        if (status != PackageInstaller.STATUS_SUCCESS) {
            _state.value = State.Failed(message ?: "PackageInstaller: $status")
        }
    }

    private fun fetchPointer(file: String): Any? {
        for (base in POINTER_BASES) {
            try {
                val conn = URL(base + file).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 15_000
                    conn.setRequestProperty("User-Agent", EngineUpdater.USER_AGENT)
                    when (conn.responseCode) {
                        200 -> {
                            val bytes = conn.inputStream.use { it.readBytes() }
                            if (bytes.size > 64 * 1024) throw IOException("puntero demasiado grande")
                            return JsonStrict.parse(String(bytes, Charsets.UTF_8))
                        }
                        404 -> return null // todavía no hay versión en ese canal
                        else -> throw IOException("HTTP ${conn.responseCode}")
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.i(TAG, "Puntero $base$file: $e")
            }
        }
        return null
    }

    private fun download(update: AppUpdate.Available): File {
        val dir = File(app.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val target = File(dir, "K-Snag-${update.version}.apk")
        val conn = URL(update.url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", EngineUpdater.USER_AGENT)
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            var lastEmit = 0L
            _state.value = State.Downloading(update, 0f)
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > update.size) throw IOException("el APK pesa más de lo anunciado")
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 300) {
                            lastEmit = now
                            _state.value = State.Downloading(update, total.toFloat() / update.size)
                        }
                    }
                }
            }
            if (total != update.size) throw IOException("tamaño distinto: $total de ${update.size}")
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (sha != update.sha256) throw IOException("SHA-256 distinto")
            return target
        } catch (e: IOException) {
            target.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    /** El APK nuevo está firmado por la misma llave que la app instalada (o una de su historia de rotación). */
    @Suppress("DEPRECATION")
    private fun verifySigner(file: File) {
        val pm = app.packageManager
        fun certs(info: PackageInfo?): Set<String> = when {
            info == null -> emptySet()
            Build.VERSION.SDK_INT >= 28 -> info.signingInfo?.let { si ->
                (if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory).map { sha(it.toByteArray()) }.toSet()
            }.orEmpty()
            else -> info.signatures.orEmpty().map { sha(it.toByteArray()) }.toSet()
        }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = certs(pm.getPackageInfo(app.packageName, flags))
        val archive = pm.getPackageArchiveInfo(file.path, flags)
        if (archive?.packageName != app.packageName) throw IOException("el APK es de otro paquete")
        val newCerts = certs(archive)
        if (newCerts.isEmpty() || installed.isEmpty() || newCerts.none { it in installed }) throw IOException("el APK está firmado con otra llave")
    }

    private fun install(update: AppUpdate.Available, file: File) {
        if (!app.packageManager.canRequestPackageInstalls()) {
            _state.value = State.NeedsPermission(update, file)
            return
        }
        _state.value = State.Installing(update)
        app.queue.interruptActive() // la descarga en curso se retoma sola cuando la app vuelva a abrir
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(file.length())
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(app, id, Intent(app, InstallResultReceiver::class.java), flags)
            session.commit(pending.intentSender)
        }
    }

    private fun installedCode(): Long = runCatching {
        PackageInfoCompat.getLongVersionCode(app.packageManager.getPackageInfo(app.packageName, 0))
    }.getOrDefault(BuildConfig.VERSION_CODE.toLong())

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "AppUpdater"
        private const val KEY_CHECKED = "checked_at"
        private val POINTER_BASES = listOf(
            "https://raw.githubusercontent.com/${AppUpdate.REPO}/master/web/data/",
            "https://ksnagapp.vercel.app/data/",
            "https://kdownloader.vercel.app/data/", // el dominio de antes del cambio de nombre: sigue sirviendo /data/
        )
    }
}
