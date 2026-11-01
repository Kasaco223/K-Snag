// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.download

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.kasaco223.kdownloader.BuildConfig
import io.github.kasaco223.kdownloader.R
import io.github.kasaco223.kdownloader.contracts.EngineStatus
import io.github.kasaco223.kdownloader.core.Urls
import io.github.kasaco223.kdownloader.core.download.Args
import io.github.kasaco223.kdownloader.core.download.ErrorCatalog
import io.github.kasaco223.kdownloader.core.download.ExitMapper
import io.github.kasaco223.kdownloader.core.download.Kdl
import io.github.kasaco223.kdownloader.core.download.KdlEvent
import io.github.kasaco223.kdownloader.core.download.Presets
import io.github.kasaco223.kdownloader.core.download.ProgressModel
import io.github.kasaco223.kdownloader.core.download.long
import io.github.kasaco223.kdownloader.core.download.str
import io.github.kasaco223.kdownloader.engine.KdEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

/**
 * La cola de descargas (diseño del motor §11, etapa de la primera descarga): una a la vez, en el servicio en
 * primer plano. Guarda los trabajos en kd/jobs.json y lo terminado en kd/history.json. Los reintentos
 * automáticos, el análisis previo y la vuelta tras un cierre llegan en los próximos PR.
 */
class DownloadQueue(private val context: Context, private val engine: KdEngine) {
    sealed interface EnqueueResult {
        data class Queued(val id: String) : EnqueueResult
        data class AlreadyInDownloads(val item: HistoryItem) : EnqueueResult

        /** Una lista: [queued] trabajos nuevos y [skipped] que ya estaban en Descargas o no eran enlaces válidos. */
        data class QueuedMany(val queued: Int, val skipped: Int) : EnqueueResult

        /** [code]: invalid o storage_permission (API 26-28 sin permiso para escribir en Descargas). */
        data class Rejected(val code: String) : EnqueueResult
    }

    /** Lo que ya se sabe por el análisis: la tarjeta muestra título y miniatura antes de que arranque. */
    data class Prefill(val title: String?, val uploader: String?, val duration: Double?, val thumbnail: String?, val thumbId: String?)

    private val dirs = engine.dirs
    private val jobsFile = File(dirs.root, "jobs.json")
    private val historyFile = File(dirs.root, "history.json")
    private val jobsDir = File(dirs.root, "jobs")
    private val lock = Any()
    private var lastSave = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Las miniaturas de trabajos e historial (kd/thumbs). */
    val thumbnails = Thumbnails(File(dirs.root, "thumbs"), scope)

    private val _jobs = MutableStateFlow(loadJobs())
    val jobs: StateFlow<List<Job>> = _jobs.asStateFlow()

    private val _history = MutableStateFlow(JsonFiles.load(historyFile, "items", HistoryItem::fromJson))
    val history: StateFlow<List<HistoryItem>> = _history.asStateFlow()

    private val catalog: ErrorCatalog by lazy {
        context.assets.open("kd/errors_catalog.json").use { ErrorCatalog.parse(it.reader(Charsets.UTF_8).readText()) }
    }
    private val publisher = Publisher(context)

    private class Running(val id: String) {
        @Volatile var run: io.github.kasaco223.kdownloader.engine.PyRunner.Run? = null

        @Volatile var canceled = false
    }

    @Volatile private var running: Running? = null

    /** Solo pruebas (KD_TEST_HOOKS): argumentos extra permitidos (--enable-file-urls, --limit-rate). */
    @Volatile var testExtraArgs: List<String> = emptyList()

    /** Solo pruebas: false = encolar sin arrancar el servicio (la prueba llama a [runJob]). */
    @Volatile var startServiceOnEnqueue: Boolean = true

    fun hasQueued(): Boolean = _jobs.value.any { it.state == "queued" }

    // ------------------------------------------------------------------------------------------------ acciones
    suspend fun enqueue(rawUrl: String, option: String, allowDuplicate: Boolean = false, prefill: Prefill? = null): EnqueueResult = withContext(Dispatchers.IO) {
        // Pruebas sin red (KD_TEST_HOOKS, solo debug): archivos locales con --enable-file-urls.
        val testFile = BuildConfig.KD_TEST_HOOKS && rawUrl.startsWith("file:///") && "--enable-file-urls" in testExtraArgs
        val url = if (testFile) rawUrl else Urls.validate(rawUrl)?.let { Urls.normalize(it) ?: it } ?: return@withContext EnqueueResult.Rejected("invalid")
        Presets.optionToChoice(option) ?: return@withContext EnqueueResult.Rejected("invalid")
        if (Build.VERSION.SDK_INT <= 28 && !hasStoragePermission()) return@withContext EnqueueResult.Rejected("storage_permission")
        val key = if (Urls.isYoutube(url)) Urls.guessVideoId(url)?.let { "youtube:$it" } else null
        if (!allowDuplicate && key != null) {
            _history.value.firstOrNull { it.videoKey == key && it.option == option && fileExists(it.contentUri) }
                ?.let { return@withContext EnqueueResult.AlreadyInDownloads(it) }
        }
        val job = newJob(url, option, key, prefill)
        synchronized(lock) {
            _jobs.value = (listOf(job) + _jobs.value).let(::evict)
            save(_jobs.value)
        }
        if (startServiceOnEnqueue) startService()
        EnqueueResult.Queued(job.id)
    }

    /**
     * Toda una lista (como mucho [MAX_BATCH] videos), en orden. Se saltan los enlaces inválidos y, salvo
     * [allowDuplicate], los videos que ya están en Descargas con el mismo formato.
     */
    suspend fun enqueueAll(urls: List<String>, option: String, allowDuplicate: Boolean = false): EnqueueResult = withContext(Dispatchers.IO) {
        Presets.optionToChoice(option) ?: return@withContext EnqueueResult.Rejected("invalid")
        if (Build.VERSION.SDK_INT <= 28 && !hasStoragePermission()) return@withContext EnqueueResult.Rejected("storage_permission")
        val have = _history.value.filter { it.option == option && it.videoKey != null }.map { it.videoKey }.toSet()
        var skipped = 0
        val now = System.currentTimeMillis()
        val jobs = urls.take(MAX_BATCH).mapIndexedNotNull { i, raw ->
            val url = Urls.validate(raw)?.let { Urls.normalize(it) ?: it }
            val key = url?.takeIf { Urls.isYoutube(it) }?.let { Urls.guessVideoId(it) }?.let { "youtube:$it" }
            if (url == null || (!allowDuplicate && key != null && key in have)) {
                skipped++
                null
            } else {
                newJob(url, option, key, null).copy(createdAt = now + i) // el orden de la lista
            }
        }
        if (jobs.isEmpty()) return@withContext EnqueueResult.QueuedMany(0, skipped)
        synchronized(lock) {
            _jobs.value = (jobs.reversed() + _jobs.value).let(::evict)
            save(_jobs.value)
        }
        if (startServiceOnEnqueue) startService()
        EnqueueResult.QueuedMany(jobs.size, skipped)
    }

    private fun newJob(url: String, option: String, key: String?, prefill: Prefill?): Job {
        val id = UUID.randomUUID().toString().replace("-", "").take(8)
        prefill?.thumbId?.let { from ->
            val src = thumbnails.file(from)
            if (src.isFile) runCatching { src.copyTo(thumbnails.file(id), overwrite = true) }
        }
        if (!thumbnails.file(id).exists()) prefill?.thumbnail?.let { thumbnails.fetch(id, it) }
        return Job(
            id = id,
            url = url,
            option = option,
            container = Presets.containerFor(option, Presets.optionToChoice(option)?.height),
            isYoutube = Urls.isYoutube(url),
            site = siteName(url),
            videoKey = key,
            title = prefill?.title?.takeIf { it.isNotBlank() },
            uploader = prefill?.uploader?.takeIf { it.isNotBlank() },
            duration = prefill?.duration?.toLong(),
            thumbnail = prefill?.thumbnail,
        )
    }

    fun cancel(id: String) {
        val r = running
        if (r != null && r.id == id) {
            r.canceled = true
            Thread { r.run?.cancel() }.start()
            return
        }
        update(id) { if (it.isTerminal) it else it.copy(state = "canceled", finishedAt = System.currentTimeMillis()) }
        File(jobsDir, id).deleteRecursively()
    }

    fun retry(id: String) {
        update(id) {
            if (it.state == "error" || it.state == "canceled" || it.state == "interrupted") {
                it.copy(state = "queued", error = null, progress = null, stepIndex = 0, stepTotal = 0, postprocessor = null, finishedAt = null)
            } else {
                it
            }
        }
        startService()
    }

    fun remove(id: String) {
        if (running?.id == id) return
        synchronized(lock) {
            _jobs.value = _jobs.value.filterNot { it.id == id }
            save(_jobs.value)
        }
        File(jobsDir, id).deleteRecursively()
        dropThumbnails(listOf(id))
    }

    fun clearFinished() {
        val cleared = synchronized(lock) {
            val (done, keep) = _jobs.value.partition { it.isTerminal }
            _jobs.value = keep
            save(keep)
            done.map { it.id }
        }
        dropThumbnails(cleared)
    }

    fun removeHistory(id: String) {
        synchronized(lock) {
            _history.value = _history.value.filterNot { it.id == id }
            JsonFiles.save(historyFile, "items", _history.value, HistoryItem::toJson)
        }
        dropThumbnails(listOf(id))
    }

    /** Borra las miniaturas que ya no usa ni un trabajo ni el historial. */
    private fun dropThumbnails(ids: Collection<String>) {
        val used = synchronized(lock) { _jobs.value.map { it.id }.toSet() + _history.value.map { it.id } }
        thumbnails.delete(ids.filterNot { it in used })
    }

    /** Arranca el servicio si hay algo en cola (al volver a la app, por ejemplo). */
    fun startService() {
        if (!hasQueued()) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java).setAction(DownloadService.ACTION_RUN))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Android no dejó iniciar el servicio: la cola sigue esperando", e)
        }
    }

    // ------------------------------------------------------------------------------------------------ ejecutar
    /** El siguiente en cola, en orden de llegada. */
    fun nextQueued(): Job? = _jobs.value.filter { it.state == "queued" }.minByOrNull { it.createdAt }

    /** Corre un trabajo hasta el final (lo llama el servicio, en un hilo de E/S). */
    suspend fun runJob(job: Job) {
        val r = Running(job.id)
        running = r
        val jobDir = File(jobsDir, job.id)
        try {
            update(job.id) { it.copy(state = "downloading", attempts = it.attempts + 1, progress = null, error = null) }
            val status = engine.ensureReady()
            if (status is EngineStatus.Failed) {
                fail(job.id, engineError(status.error.code, status.error.detail))
                return
            }
            val ytdlp = engine.activeYtdlp() ?: return fail(job.id, engineError("engine_failed", "sin yt-dlp activo"))
            val home = File(jobDir, "home").apply { mkdirs() }
            val temp = File(jobDir, "tmp").apply { mkdirs() }
            val runDir = File(jobDir, "run").apply { mkdirs() }
            val choice = Presets.optionToChoice(job.option) ?: return fail(job.id, catalog.toJobError(catalog.byCode("unknown")))
            val paths = Args.Paths(dirs.cache.apply { mkdirs() }.path, dirs.lib("libqjs.so").path, dirs.lib("libffmpeg.so").path, home.path, temp.path)
            val extra = if (BuildConfig.KD_TEST_HOOKS) testExtraArgs.filter { it == "--enable-file-urls" || it == "--limit-rate" || it.matches(Regex("\\d+[KM]")) } else emptyList()
            val args = listOf(ytdlp.path) + Args.download(paths, job.url, choice, job.isYoutube, forceBest = job.forceBest, extra = extra)
            val model = ProgressModel(job.option, job.isYoutube)
            var lastEmit = 0L
            val result = engine.withLease {
                val run = engine.runner.start(args, runDir, engine.runMode(), onLine = { line ->
                    val ev = Kdl.parseLine(line) ?: return@start
                    val now = System.currentTimeMillis()
                    synchronized(model) { model.onEvent(ev, now) }
                    if (ev !is KdlEvent.Dl || now - lastEmit > 250) {
                        lastEmit = now
                        emitProgress(job.id, model)
                    }
                })
                r.run = run
                if (r.canceled) run.cancel()
                run.await(RUN_TIMEOUT_MS)
            }
            val lines = result.stdout + result.stderr
            val accepted = model.filePath?.let { acceptFile(it, home) }
            val already = ExitMapper.alreadyDownloadedPath(lines)?.let { acceptFile(it, home) }
            when (val outcome = ExitMapper.map(r.canceled, null, result.exitCode, accepted, already, lines)) {
                ExitMapper.Outcome.Canceled -> {
                    update(job.id) { it.copy(state = "canceled", finishedAt = System.currentTimeMillis(), speed = null, eta = null) }
                    jobDir.deleteRecursively()
                }
                is ExitMapper.Outcome.Ok -> publish(job.id, File(outcome.path), jobDir, r)
                is ExitMapper.Outcome.AlreadyDownloaded -> update(job.id) { it.copy(state = "done", progress = 1.0, notes = it.notes + outcome.note) }
                ExitMapper.Outcome.Killed -> fail(job.id, catalog.toJobError(catalog.classify(lines)))
                is ExitMapper.Outcome.Failed -> fail(job.id, errorFor(outcome.code, lines))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error inesperado en el trabajo ${job.id}", e)
            fail(job.id, catalog.toJobError(catalog.classify(listOf(e.toString()))))
        } finally {
            running = null
        }
    }

    private fun publish(id: String, file: File, jobDir: File, r: Running) {
        update(id) { it.copy(state = "processing", postprocessor = "Publish", speed = null, eta = null) }
        val pending = _jobs.value.firstOrNull { it.id == id }?.pendingUri
        try {
            val p = publisher.publish(file, id, pending, isCanceled = { r.canceled }) { uri -> update(id) { it.copy(pendingUri = uri) } }
            val now = System.currentTimeMillis()
            update(id) {
                it.copy(state = "done", progress = 1.0, postprocessor = null, displayName = p.displayName, contentUri = p.uri,
                    mime = p.mime, pendingUri = null, downloaded = p.size, total = p.size, finishedAt = now)
            }
            val job = _jobs.value.first { it.id == id }
            val item = HistoryItem(id, job.url, job.title ?: p.displayName.substringBeforeLast('.'), job.site, job.videoKey,
                job.option, p.displayName, p.uri, p.mime, p.size, now, uploader = job.uploader, duration = job.duration)
            val dropped = synchronized(lock) {
                val all = listOf(item) + _history.value.filterNot { it.id == id }
                _history.value = all.take(500)
                JsonFiles.save(historyFile, "items", _history.value, HistoryItem::toJson)
                all.drop(500).map { it.id }
            }
            dropThumbnails(dropped)
            jobDir.deleteRecursively()
            DownloadNotifications.done(context, item)
        } catch (e: Publisher.Canceled) {
            update(id) { it.copy(state = "canceled", pendingUri = null, finishedAt = System.currentTimeMillis()) }
            jobDir.deleteRecursively()
        } catch (e: IOException) {
            Log.w(TAG, "No se pudo publicar $id", e)
            val enospc = (e.cause as? ErrnoException)?.errno == OsConstants.ENOSPC || e.message.orEmpty().contains("ENOSPC") ||
                e.message.orEmpty().contains("No space left", ignoreCase = true)
            fail(id, catalog.toJobError(catalog.byCode(if (enospc) "disk_full" else "permission", e.toString())))
        } catch (e: SecurityException) {
            fail(id, localError("storage_permission", R.string.jobs_error_storage_title, R.string.jobs_error_storage_body, e.toString()))
        }
    }

    private fun fail(id: String, error: JobError) {
        update(id) { it.copy(state = "error", error = error, speed = null, eta = null, finishedAt = System.currentTimeMillis()) }
        _jobs.value.firstOrNull { it.id == id }?.let { DownloadNotifications.failed(context, it) }
    }

    /** El error de una salida de yt-dlp con el catálogo de Windows (también para el análisis). */
    fun classify(lines: List<String>): JobError = catalog.toJobError(catalog.classify(lines))

    private fun errorFor(code: String?, lines: List<String>): JobError = when (code) {
        null -> catalog.toJobError(catalog.classify(lines))
        "live_unsupported" -> localError(code, R.string.jobs_error_live_title, R.string.jobs_error_live_body, lines.takeLast(20).joinToString("\n"))
        else -> catalog.toJobError(catalog.byCode(code, lines.takeLast(30).joinToString("\n")))
    }

    internal fun engineError(code: String, detail: String): JobError = when (code) {
        "engine_incompatible" -> localError(code, R.string.engine_error_incompatible_title, R.string.engine_error_incompatible_body, detail)
        "disk_full" -> localError(code, R.string.engine_error_disk_full_title, R.string.engine_error_disk_full_generic, detail)
        else -> localError(code, R.string.engine_error_failed_title, R.string.engine_error_failed_body, detail)
    }

    private fun ErrorCatalog.toJobError(c: ErrorCatalog.Classified) =
        JobError(c.code, c.title("es"), c.message("es"), c.title("en"), c.message("en"), c.details)

    /** Error con textos de la app (strings.xml) en español e inglés. */
    internal fun localError(code: String, title: Int, body: Int, detail: String): JobError {
        fun text(lang: String, res: Int) = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(lang))
        }).getString(res)
        return JobError(code, text("es", title), text("es", body), text("en", title), text("en", body), detail)
    }

    @Suppress("UNCHECKED_CAST")
    private fun emitProgress(id: String, model: ProgressModel) {
        val snapshot = synchronized(model) {
            Snapshot(model.state, model.stepIndex, model.stepTotal, model.stepKind, model.postprocessor, model.progress,
                model.downloaded, model.total, model.speed, model.eta, model.meta, model.notes.toList())
        }
        snapshot.meta?.str("thumbnail")?.let { thumbnails.fetch(id, it) }
        update(id, persistNow = false) { j ->
            j.copy(
                state = snapshot.state, stepIndex = snapshot.stepIndex, stepTotal = snapshot.stepTotal,
                stepKind = snapshot.stepKind, postprocessor = snapshot.postprocessor, progress = snapshot.progress ?: j.progress,
                downloaded = snapshot.downloaded, total = snapshot.total, speed = snapshot.speed, eta = snapshot.eta,
                title = snapshot.meta?.str("title") ?: j.title,
                uploader = snapshot.meta?.str("uploader") ?: snapshot.meta?.str("channel") ?: j.uploader,
                thumbnail = snapshot.meta?.str("thumbnail") ?: j.thumbnail,
                duration = snapshot.meta?.long("duration") ?: j.duration,
                expectedSize = snapshot.meta?.let { it.long("filesize") ?: it.long("filesize_approx") } ?: j.expectedSize,
                videoKey = snapshot.meta?.let { m ->
                    val ex = m.str("extractor_key")?.lowercase()
                    val vid = m.str("id")
                    if (ex != null && vid != null) "$ex:$vid" else null
                } ?: j.videoKey,
                notes = (j.notes + snapshot.notes).distinct(),
            )
        }
    }

    private data class Snapshot(
        val state: String, val stepIndex: Int, val stepTotal: Int, val stepKind: String?, val postprocessor: String?,
        val progress: Double?, val downloaded: Long, val total: Long, val speed: Double?, val eta: Long?,
        val meta: Map<String, Any?>?, val notes: List<String>,
    )

    // ------------------------------------------------------------------------------------------------ estado
    private fun update(id: String, persistNow: Boolean = true, f: (Job) -> Job) {
        synchronized(lock) {
            val list = _jobs.value.map { if (it.id == id) f(it) else it }
            _jobs.value = list
            val now = System.currentTimeMillis()
            if (persistNow || now - lastSave > 2_000) save(list)
        }
    }

    private fun save(list: List<Job>) {
        lastSave = System.currentTimeMillis()
        runCatching { JsonFiles.save(jobsFile, "jobs", list, Job::toJson) }.onFailure { Log.w(TAG, "No se pudo guardar jobs.json", it) }
    }

    /** Al abrir: lo que estaba corriendo cuando el proceso se cerró queda «interrumpido» (Reintentar lo retoma). */
    private fun loadJobs(): List<Job> = JsonFiles.load(jobsFile, "jobs", Job::fromJson).map {
        if (it.isActive) it.copy(state = "interrupted", speed = null, eta = null) else it
    }

    /** Los no terminados nunca se borran; de los terminados quedan los 200 más nuevos. */
    private fun evict(list: List<Job>): List<Job> {
        val terminal = list.filter { it.isTerminal }.sortedByDescending { it.createdAt }.drop(200).map { it.id }.toSet()
        if (terminal.isNotEmpty()) {
            val inHistory = _history.value.map { it.id }.toSet()
            thumbnails.delete(terminal.filterNot { it in inHistory })
        }
        return list.filterNot { it.id in terminal }
    }

    /** El archivo final, solo si está dentro de la carpeta home del trabajo y es un archivo normal. */
    private fun acceptFile(path: String, home: File): String? {
        val f = File(path)
        return runCatching {
            // Canónicas las dos: /data/user/0 es un enlace a /data/data.
            val canonical = f.canonicalFile
            val base = home.canonicalPath + File.separator
            val notLink = !java.nio.file.Files.isSymbolicLink(f.toPath())
            canonical.takeIf { it.path.startsWith(base) && it.isFile && notLink }?.path
        }.getOrNull()
    }

    fun fileExists(uri: String): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
    }.getOrDefault(false)

    private fun hasStoragePermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun siteName(url: String): String = when {
        Urls.isYoutube(url) -> "YouTube"
        else -> Urls.host(url)?.removePrefix("www.") ?: ""
    }

    companion object {
        private const val TAG = "DownloadQueue"
        private const val RUN_TIMEOUT_MS = 6L * 60 * 60 * 1000

        /** Tope de una lista (diseño, B.3 #13). */
        const val MAX_BATCH = 500
    }
}
