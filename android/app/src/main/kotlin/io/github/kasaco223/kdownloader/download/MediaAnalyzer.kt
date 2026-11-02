// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.download

import android.util.Log
import io.github.kasaco223.kdownloader.BuildConfig
import io.github.kasaco223.kdownloader.R
import io.github.kasaco223.kdownloader.contracts.EngineStatus
import io.github.kasaco223.kdownloader.core.Urls
import io.github.kasaco223.kdownloader.core.download.Args
import io.github.kasaco223.kdownloader.core.download.JsonStrict
import io.github.kasaco223.kdownloader.core.media.Media
import io.github.kasaco223.kdownloader.core.media.MediaSummary
import io.github.kasaco223.kdownloader.core.media.Tile
import io.github.kasaco223.kdownloader.core.media.Tiles
import io.github.kasaco223.kdownloader.engine.KdEngine
import io.github.kasaco223.kdownloader.engine.PyRunner
import io.github.kasaco223.kdownloader.platform.UiPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Lo que se sabe de un enlace antes de bajarlo. [token] identifica su miniatura (`p<token>` en kd/thumbs). */
data class Analysis(
    val token: String,
    val input: String,
    val summary: MediaSummary,
    val site: String,
    val isYoutube: Boolean,
    val degraded: Boolean,
    val warnings: List<String>,
    val tiles: List<Tile>,
    val more: List<Tile>,
) {
    val thumbId: String get() = "p$token"

    /** La URL para la cola: la de la página que informó yt-dlp, o la que se pegó. */
    val url: String get() = summary.url?.takeIf { Urls.validate(it) != null } ?: input

    fun tile(key: String): Tile? = tiles.firstOrNull { it.key == key } ?: more.firstOrNull { it.key == key }
}

/**
 * El análisis previo (diseño del motor §7): `yt-dlp -J` con `--no-playlist`, o `--flat-playlist` para una lista,
 * con stdout a kd/probes/<token>/info.json y 120 s como máximo. Uno a la vez: el nuevo cancela al anterior. Las
 * opciones se calculan en Kotlin ([Tiles]), sin más procesos.
 *
 * Todavía no: actualizar yt-dlp ante un análisis degradado y reusar el info.json en la descarga (llegan con la
 * robustez del motor).
 */
class MediaAnalyzer(private val engine: KdEngine, private val queue: DownloadQueue, private val prefs: UiPrefs) {
    sealed interface Result {
        data class Ok(val analysis: Analysis) : Result
        data class Failed(val error: JobError) : Result
    }

    private val dirs = engine.dirs
    private val probes = File(dirs.root, "probes")
    private val lock = Mutex()

    @Volatile private var active: Job? = null

    /** Solo pruebas (KD_TEST_HOOKS): `--enable-file-urls` para analizar un archivo local. */
    @Volatile var testExtraArgs: List<String> = emptyList()

    suspend fun analyze(rawUrl: String, playlist: Boolean = false): Result {
        val testFile = BuildConfig.KD_TEST_HOOKS && rawUrl.startsWith("file:///") && "--enable-file-urls" in testExtraArgs
        val url = if (testFile) rawUrl else Urls.validate(rawUrl) ?: return Result.Failed(queue.classify(listOf("ERROR: Unsupported URL: $rawUrl")))
        val me = currentCoroutineContext()[Job]
        active?.takeIf { it != me }?.cancel()
        active = me
        return lock.withLock {
            withContext(Dispatchers.IO) {
                val first = run(url, playlist, testFile)
                // YouTube dio solo 360p: puede ser un yt-dlp viejo. Se actualiza (si toca) y se analiza otra vez.
                val degraded = (first as? Result.Ok)?.analysis?.degraded == true
                if (degraded && prefs.autoUpdateEngine.value && engine.updateYtdlp(prefs.engineChannel.value) is KdEngine.UpdateState.Updated) {
                    run(url, playlist, testFile)
                } else {
                    first
                }
            }
        }
    }

    private suspend fun run(url: String, playlist: Boolean, testFile: Boolean): Result {
        val status = engine.ensureReady()
        if (status is EngineStatus.Failed) return Result.Failed(queue.engineError(status.error.code, status.error.detail))
        val ytdlp = engine.activeYtdlp() ?: return Result.Failed(queue.engineError("engine_failed", "sin yt-dlp activo"))
        cleanOld()
        val token = UUID.randomUUID().toString().replace("-", "").take(10)
        val dir = File(probes, token).apply { mkdirs() }
        try {
            val out = File(dir, "info.json")
            val paths = Args.Paths(dirs.cache.apply { mkdirs() }.path, dirs.lib("libqjs.so").path, dirs.lib("libffmpeg.so").path, dir.path, File(dir, "tmp").path)
            val args = buildList {
                add(ytdlp.path)
                addAll(Args.base(paths, engine.hints.youtubePlayerClient.takeIf { Urls.isYoutube(url) }))
                addAll(if (playlist) listOf("--yes-playlist", "--flat-playlist") else listOf("--no-playlist"))
                add("-J")
                if (testFile) add("--enable-file-urls")
                add("--")
                add(url)
            }
            val result = engine.withLease {
                val run = engine.runner.start(args, File(dir, "run").apply { mkdirs() }, engine.runMode(), stdoutFile = out)
                try {
                    runInterruptible { run.await(TIMEOUT_MS) }
                } catch (e: CancellationException) {
                    Thread { run.cancel() }.start()
                    throw e
                }
            }
            val lines = result.stderr.filterNot { it.startsWith("KDPID:") }
            if (result.timedOut) {
                return Result.Failed(queue.localError("timeout", R.string.analyze_timeout_title, R.string.analyze_timeout_body, lines.takeLast(20).joinToString("\n")))
            }
            if (result.exitCode != 0 || !out.isFile || out.length() == 0L) return Result.Failed(queue.classify(lines))
            if (out.length() > MAX_BYTES) return Result.Failed(queue.classify(lines + INVALID))
            @Suppress("UNCHECKED_CAST")
            val info = try {
                JsonStrict.parse(out.readText()) as? Map<String, Any?>
            } catch (e: JsonStrict.JsonException) {
                null
            } catch (e: OutOfMemoryError) {
                null
            } ?: return Result.Failed(queue.classify(lines + INVALID))
            return Result.Ok(build(token, url, info, lines))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun build(token: String, url: String, info: Map<String, Any?>, lines: List<String>): Analysis {
        val summary = Media.summarize(info)
        val isYoutube = Urls.isYoutube(summary.url ?: url)
        val warnings = lines.filter { it.startsWith("WARNING") }
        val solveFailed = isYoutube && warnings.any { "n challenge solving failed" in it || "signature solving failed" in it }
        summary.thumbnail?.let { queue.thumbnails.fetch("p$token", it) }
        return Analysis(
            token = token,
            input = url,
            summary = summary,
            site = Media.siteName(info, url),
            isYoutube = isYoutube,
            degraded = Media.isDegraded(info) || solveFailed,
            warnings = warnings.take(5).map { it.removePrefix("WARNING: ").take(300) },
            tiles = Tiles.main(info, summary),
            more = if (summary.isPlaylist) emptyList() else Tiles.heights(info, summary),
        )
    }

    /** Restos de análisis cortados (el proceso se cerró a mitad) y miniaturas de análisis de más de un día. */
    private fun cleanOld() {
        val now = System.currentTimeMillis()
        probes.listFiles()?.filter { now - it.lastModified() > 30 * 60_000L }?.forEach { it.deleteRecursively() }
        val stale = queue.thumbnails.file("x").parentFile?.listFiles { f -> f.name.startsWith("p") && now - f.lastModified() > 24 * 3_600_000L }
        stale?.forEach { if (!it.delete()) Log.i(TAG, "No se pudo borrar ${it.name}") }
    }

    companion object {
        private const val TAG = "MediaAnalyzer"
        private const val TIMEOUT_MS = 120_000L
        private const val MAX_BYTES = 32L * 1024 * 1024
        private const val INVALID = "ERROR: respuesta inválida del motor"

        /** Los enlaces de listas se analizan como lista; un video con `&list=` ofrece la lista aparte. */
        fun isPlaylistUrl(url: String): Boolean = Urls.isPlaylistPage(url)
    }
}
