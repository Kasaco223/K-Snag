// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
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
import io.github.kasaco223.kdownloader.core.engine.YtdlpVersion
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
    private val updater = EngineUpdater(dirs, runner)

    /** Lo que pasó con la última búsqueda de una versión nueva de yt-dlp (Ajustes › Motor). */
    sealed interface UpdateState {
        data object Idle : UpdateState
        data object Checking : UpdateState
        data class UpToDate(val version: String) : UpdateState
        data class Updated(val version: String) : UpdateState
        data class RolledBack(val version: String) : UpdateState
        data object Busy : UpdateState
        data class Failed(val reason: String) : UpdateState
    }

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update.asStateFlow()
    private val updateLock = Mutex()

    /** Las pistas remotas (engine.json), que pone [HintsStore]. */
    @Volatile var hints: io.github.kasaco223.kdownloader.core.engine.UpdateParsing.Hints = io.github.kasaco223.kdownloader.core.engine.UpdateParsing.Hints()

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
            sweepOrphans()
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

    /** El mínimo de yt-dlp para [channel]: el de engine.json o el de la app. */
    fun minimum(channel: String): String =
        hints.minYtdlp[channel] ?: io.github.kasaco223.kdownloader.core.engine.UpdateParsing.MIN_YTDLP.getValue(channel)

    /** Si toca buscar una versión nueva: 24 h desde la última vez, otro canal o por debajo del mínimo. */
    fun updateDue(channel: String): Boolean {
        val s = state
        val active = s.active ?: return false
        val below = YtdlpVersion.parse(active.version)?.let { it < YtdlpVersion.parse(minimum(channel))!! } == true
        val wait = if (below) 3_600_000L else 24 * 3_600_000L
        return active.channel != channel || System.currentTimeMillis() - s.checkedAt > wait
    }

    /**
     * Busca y activa una versión nueva de yt-dlp (diseño del motor §4). Nunca con una descarga o un análisis
     * corriendo ([UpdateState.Busy]). La anterior queda para «Volver a la versión anterior».
     */
    suspend fun updateYtdlp(channel: String): UpdateState = updateLock.withLock {
        if (ensureReady() !is EngineStatus.Ready) return@withLock UpdateState.Failed("motor no listo").also { _update.value = it }
        if (leases.get() > 0) return@withLock UpdateState.Busy.also { _update.value = it }
        _update.value = UpdateState.Checking
        val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
            leases.incrementAndGet()
            try {
                updater.check(state.active, channel, state.skipVersions, minimum(channel), runMode())
            } finally {
                leases.decrementAndGet()
            }
        }
        val now = System.currentTimeMillis()
        val out = when (result) {
            is EngineUpdater.Result.UpToDate -> {
                save(state.copy(checkedAt = now, lastError = null))
                UpdateState.UpToDate(result.version)
            }
            is EngineUpdater.Result.Failed -> {
                save(state.copy(checkedAt = now, lastError = result.reason))
                UpdateState.Failed(result.reason)
            }
            is EngineUpdater.Result.Downloaded -> {
                val prev = state.active
                var next = state.copy(active = result.copy, previous = prev, checkedAt = now, lastError = null,
                    skipVersions = state.skipVersions.filter { v -> YtdlpVersion.parse(v)?.let { it > YtdlpVersion.parse(result.copy.version)!! } == true }.toSet())
                // La prueba de humo ya corrió: la autoprueba sigue valiendo para la versión nueva.
                next = next.copy(selfTest = next.selfTest?.copy(key = selfTestKey(next)))
                save(next)
                pruneVersions()
                _status.value = EngineStatus.Ready(versions(next))
                UpdateState.Updated(result.copy.version)
            }
        }
        _update.value = out
        out
    }

    /** Vuelve a la copia anterior; la que se deja queda en la lista de descartadas (no vuelve sola). */
    suspend fun rollbackYtdlp(): UpdateState = updateLock.withLock {
        if (leases.get() > 0) return@withLock UpdateState.Busy.also { _update.value = it }
        val prev = state.previous?.takeIf(store::verifies) ?: return@withLock UpdateState.Failed("sin versión anterior").also { _update.value = it }
        val current = state.active
        var next = state.copy(active = prev, previous = current, skipVersions = state.skipVersions + listOfNotNull(current?.version))
        next = next.copy(selfTest = next.selfTest?.copy(key = selfTestKey(next)))
        save(next)
        _status.value = EngineStatus.Ready(versions(next))
        UpdateState.RolledBack(prev.version).also { _update.value = it }
    }

    private fun save(next: EngineState) {
        state = next
        EngineState.save(dirs.stateFile, next)
    }

    /** Borra las copias que no son ni la activa ni la anterior (solo sin ejecuciones en curso). */
    private fun pruneVersions() {
        if (leases.get() > 0) return
        val keep = setOfNotNull(state.active?.version, state.previous?.version)
        dirs.ytdlp.listFiles()?.filter { it.isDirectory && it.name !in keep }?.forEach { it.deleteRecursively() }
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

    /**
     * Al arrancar el proceso: mata los Python, FFmpeg o QuickJS nuestros que quedaron vivos de un proceso anterior
     * de la app (mismo UID, ejecutable dentro de nuestra carpeta de bibliotecas). Nunca a la app misma.
     */
    private fun sweepOrphans() {
        val me = Os.getpid()
        val uid = android.os.Process.myUid()
        val names = listOf("/libpython.so", "/libffmpeg.so", "/libffprobe.so", "/libqjs.so")
        File("/proc").listFiles { f -> f.name.all(Char::isDigit) }?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            if (pid == me) return@forEach
            runCatching {
                val status = File(dir, "status").readLines()
                val procUid = status.firstOrNull { it.startsWith("Uid:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull()
                if (procUid != uid) return@forEach
                val cmd0 = File(dir, "cmdline").readText().substringBefore('\u0000')
                if (names.any { cmd0.endsWith(it) } && context.packageName in cmd0) {
                    Log.w(TAG, "Proceso huérfano $pid ($cmd0): se termina")
                    Os.kill(pid, OsConstants.SIGKILL)
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ preparar
    private fun quickCheck(): EngineVersions? {
        val loaded = EngineState.load(dirs.stateFile)
        val ok = PayloadInstaller.NAMES.all(installer::isInstalled) &&
            store.verifies(loaded.active) &&
            dirs.caFile.exists() && EngineStore.sha256(dirs.caFile.inputStream()) == BuildConfig.CACERT_SHA256 &&
            loaded.selfTest?.let { it.ok && it.key == selfTestKey(loaded) } == true &&
            loaded.bundledOfferedVersionCode == versionCode && // una versión nueva de la app pasa por ensureActive
            lib16kOk()
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
            setupLib16k()
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

    /**
     * Páginas de 16 KB (Android 15+): las libwebp del paquete de FFmpeg están alineadas a 4 KB y no cargan. El APK
     * trae copias propias (libkd_*.so, app/src/main/cpp); aquí se enlazan con su nombre original en lib16k, que
     * va primero en LD_LIBRARY_PATH. Con páginas de 4 KB no se usa (lib16k no existe).
     */
    private fun setupLib16k() {
        val dir = dirs.lib16k
        dir.deleteRecursively()
        if (Os.sysconf(OsConstants._SC_PAGESIZE) <= 4096L) return
        val pairs = LIB16K.map { (name, apkName) -> name to dirs.lib(apkName) }
        if (pairs.any { !it.second.isFile }) {
            Log.w(TAG, "Páginas de 16 KB sin los reemplazos de libwebp en ${dirs.nativeLibDir}")
            return
        }
        dir.mkdirs()
        for ((name, target) in pairs) Os.symlink(target.path, File(dir, name).path)
    }

    /** lib16k está como tiene que estar para este tamaño de página y esta instalación de la app. */
    private fun lib16kOk(): Boolean {
        if (Os.sysconf(OsConstants._SC_PAGESIZE) <= 4096L) return !dirs.lib16k.exists()
        return LIB16K.all { (name, apkName) ->
            runCatching { Os.readlink(File(dirs.lib16k, name).path) == dirs.lib(apkName).path }.getOrDefault(false)
        }
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
        private const val SELFTEST_PATCH = 3 // 2: lanzador sin env; 3: lib16k (libwebp de 16 KB)
        private const val EXTRA_SPACE = 40L shl 20
        private const val PROBE_TIMEOUT = 60_000L
        private const val YTDLP_TIMEOUT = 60_000L
        private const val FFMPEG_TIMEOUT = 30_000L
        /** Reemplazos de 16 KB: nombre que busca FFmpeg → archivo del APK. */
        private val LIB16K = listOf("libwebp.so" to "libkd_webp.so", "libwebpmux.so" to "libkd_webpmux.so", "libsharpyuv.so" to "libkd_sharpyuv.so")
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
