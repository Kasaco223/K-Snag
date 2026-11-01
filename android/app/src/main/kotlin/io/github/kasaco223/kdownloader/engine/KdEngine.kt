// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.engine

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.storage.StorageManager
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import io.github.kasaco223.kdownloader.BuildConfig
import io.github.kasaco223.kdownloader.contracts.EngineController
import io.github.kasaco223.kdownloader.contracts.EngineError
import io.github.kasaco223.kdownloader.contracts.EnginePhase
import io.github.kasaco223.kdownloader.contracts.EngineStatus
import io.github.kasaco223.kdownloader.contracts.EngineVersions
import io.github.kasaco223.kdownloader.core.engine.EngineErrors
import io.github.kasaco223.kdownloader.core.engine.SelfTestLine
import io.github.kasaco223.kdownloader.core.engine.SessionProbe
import io.github.kasaco223.kdownloader.download.DownloadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * El motor de descargas (diseño del motor §2). No llama al código de youtubedl-android: usa sus paquetes
 * nativos con nuestro propio instalador ([PayloadInstaller]), nuestro lanzador ([PyRunner]) y nuestra copia
 * verificada de yt-dlp ([EngineStore]).
 *
 * - [warmUp] (al abrir la app) solo revisa marcas y hashes: nunca descomprime ni usa la red.
 * - [prepare] arranca el servicio en primer plano si hay trabajo; si Android no lo deja, lo hace aquí mismo.
 * - [ensureReady] es de una sola ejecución a la vez: espacio → descomprimir → terminar → autoprueba.
 */
class KdEngine(private val context: Context, private val scope: CoroutineScope) : EngineController {
    val dirs = EngineDirs(context)
    val runner = PyRunner(dirs)
    private val versionCode: Long = installedVersionCode(context)
    private val installer = PayloadInstaller(dirs, versionCode)
    private val store = EngineStore(context, dirs)

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.NotReady)
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val mutex = Mutex()
    private var preparing: Deferred<EngineStatus>? = null
    private var warmUpJob: Deferred<Boolean>? = null

    /** Ejecuciones en curso (autoprueba, y más adelante descargas y análisis): «Reparar» espera a que no haya. */
    private val leases = AtomicInteger(0)

    @Volatile private var state = EngineState()

    fun warmUp() {
        warmUpJob = scope.async(Dispatchers.IO) {
            val ready = runCatching { quickCheck() }.getOrNull()
            if (ready != null && _status.value == EngineStatus.NotReady) _status.value = EngineStatus.Ready(ready)
            ready != null
        }
    }

    override fun prepare() {
        scope.launch {
            if (warmUpJob?.await() == true) return@launch
            if (_status.value == EngineStatus.NotReady) startPrepare()
        }
    }

    override fun retrySetup() {
        scope.launch {
            if (_status.value is EngineStatus.Failed || _status.value == EngineStatus.NotReady) startPrepare()
        }
    }

    /** El trabajo corre en DownloadService (primer plano); si Android no deja iniciarlo, corre aquí. */
    private fun startPrepare() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java).setAction(DownloadService.ACTION_PREPARE))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "No se pudo iniciar el servicio; se prepara en el proceso", e)
            scope.launch { ensureReady() }
        } catch (e: SecurityException) {
            Log.w(TAG, "No se pudo iniciar el servicio; se prepara en el proceso", e)
            scope.launch { ensureReady() }
        }
    }

    suspend fun ensureReady(): EngineStatus {
        val job = mutex.withLock {
            preparing?.takeIf { it.isActive } ?: scope.async(Dispatchers.IO) { runPrepare() }.also { preparing = it }
        }
        return job.await()
    }

    override suspend fun repair(): Result<Unit> {
        if (leases.get() > 0) return Result.failure(EngineException(EngineError(EngineErrors.BUSY, "motor en uso")))
        mutex.withLock {
            if (preparing?.isActive == true) return Result.failure(EngineException(EngineError(EngineErrors.BUSY, "preparando")))
            installer.invalidate()
            state = EngineState.load(dirs.stateFile).copy(selfTest = null, sessionMode = null)
            EngineState.save(dirs.stateFile, state)
            dirs.caFile.delete()
            _status.value = EngineStatus.NotReady
        }
        return when (val s = ensureReady()) {
            is EngineStatus.Ready -> Result.success(Unit)
            is EngineStatus.Failed -> Result.failure(EngineException(s.error))
            else -> Result.failure(EngineException(EngineError(EngineErrors.UNKNOWN, s.toString())))
        }
    }

    /** La copia activa de yt-dlp, o null si el motor no está listo. */
    fun activeYtdlp(): File? = state.active?.let(store::file)?.takeIf { _status.value is EngineStatus.Ready }

    /** Modo de lanzamiento decidido por la autoprueba. */
    fun runMode(): PyRunner.Mode = if (state.sessionMode == PyRunner.Mode.SESSION.label) PyRunner.Mode.SESSION else PyRunner.Mode.PID

    /** Corre [block] marcando el motor como en uso (para «Reparar»). */
    suspend fun <T> withLease(block: suspend () -> T): T {
        leases.incrementAndGet()
        try {
            return block()
        } finally {
            leases.decrementAndGet()
        }
    }

    /**
     * Autoprueba para la CI (`MainActivity` con el extra kdl.selftest): prepara el motor y devuelve la línea que
     * va a logcat con el tag [SELFTEST_TAG] (ver scripts/ci_emulator.sh).
     */
    suspend fun selfTestLine(): String = when (val s = ensureReady()) {
        is EngineStatus.Ready -> SelfTestLine.pass(
            ytdlp = s.versions.ytdlp,
            ffmpegOk = true,
            session = s.versions.sessionMode,
            pageSize = Os.sysconf(OsConstants._SC_PAGESIZE),
            abi = File(dirs.nativeLibDir.path).name.let { ABI_DIRS[it] ?: it },
            caCount = state.selfTest?.caCount ?: 0,
        )
        is EngineStatus.Failed -> SelfTestLine.fail(s.error.code, s.error.detail)
        else -> SelfTestLine.fail(EngineErrors.UNKNOWN, s.toString())
    }

    // ------------------------------------------------------------------------------------------------ preparar
    private fun quickCheck(): EngineVersions? {
        val loaded = EngineState.load(dirs.stateFile)
        val ok = PayloadInstaller.NAMES.all(installer::isInstalled) &&
            store.verifies(loaded.active) &&
            dirs.caFile.exists() && EngineStore.sha256(dirs.caFile.inputStream()) == BuildConfig.CACERT_SHA256 &&
            loaded.selfTest?.let { it.ok && it.key == selfTestKey(loaded) } == true &&
            loaded.bundledOfferedVersionCode == versionCode // una versión nueva de la app pasa por ensureActive
        if (!ok) return null
        state = loaded
        return versions(loaded)
    }

    private fun runPrepare(): EngineStatus {
        var phase = EnginePhase.CHECK_SPACE
        val result = try {
            _status.value = EngineStatus.Preparing(phase, null)
            val missing = PayloadInstaller.NAMES.filterNot(installer::isInstalled)
            if (missing.isNotEmpty()) {
                val unpacked = missing.sumOf(installer::installedSize)
                val need = unpacked + EXTRA_SPACE
                if (!reserve(need)) {
                    return publish(EngineStatus.Failed(EngineError(EngineErrors.DISK_FULL, "faltan $need bytes"), phase, need))
                }
                phase = EnginePhase.UNPACK
                var written = 0L
                var lastStep = -1
                _status.value = EngineStatus.Preparing(phase, 0f)
                for (name in missing) {
                    installer.install(name) { n ->
                        written += n
                        val step = (written * 100 / unpacked).toInt()
                        if (step != lastStep) {
                            lastStep = step
                            _status.value = EngineStatus.Preparing(EnginePhase.UNPACK, (written.toFloat() / unpacked).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            phase = EnginePhase.FINISH
            _status.value = EngineStatus.Preparing(phase, null)
            installCaBundle()
            var next = store.ensureActive(EngineState.load(dirs.stateFile), versionCode)
            EngineState.save(dirs.stateFile, next)
            phase = EnginePhase.SELF_TEST
            if (next.selfTest?.let { it.ok && it.key == selfTestKey(next) } != true) {
                _status.value = EngineStatus.Preparing(phase, null)
                next = withLeaseBlocking { selfTest(next) }
                EngineState.save(dirs.stateFile, next)
            }
            state = next
            EngineStatus.Ready(versions(next))
        } catch (e: SelfTestFailure) {
            EngineStatus.Failed(e.error, phase)
        } catch (e: IOException) {
            val code = EngineErrors.classify(listOfNotNull(e.message)).takeIf { it != EngineErrors.UNKNOWN } ?: EngineErrors.FAILED
            EngineStatus.Failed(EngineError(code, e.message ?: e.toString()), phase)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Error al preparar el motor", e)
            EngineStatus.Failed(EngineError(EngineErrors.UNKNOWN, e.toString()), phase)
        }
        return publish(result)
    }

    private fun publish(status: EngineStatus): EngineStatus {
        _status.value = status
        if (status is EngineStatus.Failed) Log.w(TAG, "Motor: ${status.error.code} en ${status.phase}: ${status.error.detail}")
        return status
    }

    /** Hay [bytes] libres para la app en el disco de noBackupFilesDir (pide liberar caché si hace falta). */
    private fun reserve(bytes: Long): Boolean {
        val sm = context.getSystemService(StorageManager::class.java) ?: return true
        return try {
            val uuid = sm.getUuidForPath(dirs.root.apply { mkdirs() })
            if (sm.getAllocatableBytes(uuid) < bytes) return false
            sm.allocateBytes(uuid, bytes)
            true
        } catch (e: IOException) {
            Log.w(TAG, "Sin espacio para el motor", e)
            false
        }
    }

    private fun installCaBundle() {
        val target = dirs.caFile
        if (target.exists() && EngineStore.sha256(target.inputStream()) == BuildConfig.CACERT_SHA256) return
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "cacert.pem.part")
        context.assets.open("kd/cacert.pem").use { input -> part.outputStream().use { input.copyTo(it) } }
        if (EngineStore.sha256(part.inputStream()) != BuildConfig.CACERT_SHA256) {
            part.delete()
            throw IOException("cacert.pem del APK no coincide con su SHA-256")
        }
        if (!part.renameTo(target)) throw IOException("No se pudo instalar ${target.path}")
    }

    private fun selfTestKey(s: EngineState): String = listOf(
        versionCode,
        installer.zipFile("python").length(),
        installer.zipFile("ffmpeg").length(),
        s.active?.version,
        SELFTEST_PATCH,
    ).joinToString(":")

    /**
     * Autoprueba (diseño del motor §2, paso SELF_TEST): (a) sesión y SSL de Python, (b) yt-dlp --version,
     * (c) ffmpeg -version. Cada paso tiene su timeout y un reintento.
     */
    private fun selfTest(s: EngineState): EngineState {
        val runDir = File(dirs.runs, "selftest").apply { deleteRecursively(); mkdirs() }
        try {
            val probeCode = "import os,ssl;c=ssl.create_default_context();" +
                "print('KDSELF',os.getpid(),os.getpgrp(),os.getsid(0),len(c.get_ca_certs()))"
            val app = PyRunner.readStat(Os.getpid())
            var mode = PyRunner.Mode.PID
            var caCount: Int? = null
            if (PyRunner.sessionToolsPresent() && app != null) {
                val r = retryOnce { runner.run(listOf("-c", probeCode), runDir, PyRunner.Mode.SESSION, PROBE_TIMEOUT) }
                val probe = SessionProbe.parseKdself(r.stdout)
                if (r.ok && probe != null && SessionProbe.isOwnSession(probe, SessionProbe.parseKdpid(r.stderr), app)) {
                    mode = PyRunner.Mode.SESSION
                    caCount = probe.caCount
                } else {
                    Log.w(TAG, "Sin sesión propia (modo PID): ${r.lastLines.takeLast(3)}")
                }
            }
            if (caCount == null) {
                val r = retryOnce { runner.run(listOf("-c", probeCode), runDir, PyRunner.Mode.PID, PROBE_TIMEOUT) }
                caCount = SessionProbe.parseKdself(r.stdout)?.caCount?.takeIf { r.ok } ?: throw failure(r)
            }
            if (caCount < 100) Log.w(TAG, "Pocos certificados raíz cargados: $caCount")

            val active = s.active ?: throw SelfTestFailure(EngineError(EngineErrors.FAILED, "sin yt-dlp activo"))
            val ytdlp = retryOnce { runner.run(listOf(store.file(active).path, "--version"), runDir, mode, YTDLP_TIMEOUT) }
            if (!ytdlp.ok || ytdlp.stdout.lastOrNull()?.trim() != active.version) throw failure(ytdlp)

            val ffmpeg = retryOnce { runner.ffmpeg(listOf("-hide_banner", "-version"), FFMPEG_TIMEOUT) }
            if (!ffmpeg.ok) throw failure(ffmpeg)

            return s.copy(
                selfTest = SelfTestRecord(selfTestKey(s), true, caCount, System.currentTimeMillis()),
                sessionMode = mode.label,
            )
        } finally {
            runDir.deleteRecursively()
        }
    }

    private fun retryOnce(block: () -> PyRunner.Result): PyRunner.Result {
        val first = block()
        return if (first.ok) first else block()
    }

    private fun failure(r: PyRunner.Result): SelfTestFailure {
        val lines = r.lastLines
        val code = when {
            r.timedOut -> EngineErrors.TIMEOUT
            else -> EngineErrors.classify(lines).let { if (it == EngineErrors.UNKNOWN) EngineErrors.FAILED else it }
        }
        val detail = lines.lastOrNull { it.isNotBlank() } ?: "código ${r.exitCode}"
        return SelfTestFailure(EngineError(code, detail))
    }

    private fun <T> withLeaseBlocking(block: () -> T): T {
        leases.incrementAndGet()
        try {
            return block()
        } finally {
            leases.decrementAndGet()
        }
    }

    private fun versions(s: EngineState) = EngineVersions(
        ytdlp = s.active?.version ?: "?",
        ytdlpChannel = s.active?.channel ?: "?",
        ytdlpPrevious = s.previous?.version,
        ffmpeg = FFMPEG_VERSION,
        python = PYTHON_VERSION,
        quickjs = QUICKJS_VERSION,
        youtubedlAndroid = BuildConfig.YOUTUBEDL_ANDROID_VERSION,
        sessionMode = s.sessionMode ?: PyRunner.Mode.PID.label,
    )

    private class SelfTestFailure(val error: EngineError) : Exception(error.detail)

    class EngineException(val error: EngineError) : Exception("${error.code}: ${error.detail}")

    companion object {
        const val TAG = "KdEngine"
        const val SELFTEST_TAG = "KDL-SELFTEST"

        /** Versiones de los paquetes de youtubedl-android 0.18.1 (third_party/components.json). */
        const val FFMPEG_VERSION = "7.1.1"
        const val PYTHON_VERSION = "3.12.11"
        const val QUICKJS_VERSION = "2025-04-26"

        /** Sube cuando cambia la autoprueba o el instalador: obliga a repetirla. */
        private const val SELFTEST_PATCH = 1
        private const val EXTRA_SPACE = 40L shl 20
        private const val PROBE_TIMEOUT = 60_000L
        private const val YTDLP_TIMEOUT = 60_000L
        private const val FFMPEG_TIMEOUT = 30_000L
        private val ABI_DIRS = mapOf("arm64" to "arm64-v8a", "arm" to "armeabi-v7a", "x86_64" to "x86_64", "x86" to "x86")

        private fun installedVersionCode(context: Context): Long = try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
            PackageInfoCompat.getLongVersionCode(info)
        } catch (_: Exception) {
            BuildConfig.VERSION_CODE.toLong()
        }
    }
}
