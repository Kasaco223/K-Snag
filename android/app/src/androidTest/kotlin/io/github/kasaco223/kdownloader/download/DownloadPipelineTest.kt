// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.download

import android.Manifest
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kasaco223.kdownloader.KdlApp
import io.github.kasaco223.kdownloader.contracts.EngineStatus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * La descarga de punta a punta sin red (diseño del motor §13, pruebas 5 y 8): el FFmpeg del motor genera un
 * video corto, yt-dlp lo "descarga" como file:// (--enable-file-urls, solo en debug) y la cola lo publica en
 * Descargas/K-Downloader.
 */
@RunWith(AndroidJUnit4::class)
class DownloadPipelineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as KdlApp
    private val queue = app.queue
    private val published = mutableListOf<String>()
    private lateinit var fixture: File

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT <= 28) {
            // Respaldo de `adb install -g` (ci_emulator.sh): se espera a que el comando termine.
            instrumentation.uiAutomation.executeShellCommand("pm grant ${app.packageName} ${Manifest.permission.WRITE_EXTERNAL_STORAGE}")
                .use { pfd -> java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() } }
            Thread.sleep(1_000)
        }
        val status = runBlocking { withTimeout(300_000) { app.engine.ensureReady() } }
        assertTrue("$status", status is EngineStatus.Ready)
        val dir = File(app.cacheDir, "fixtures").apply { mkdirs() }
        fixture = File(dir, "prueba kd.mp4")
        if (!fixture.exists()) {
            val r = app.engine.runner.ffmpeg(
                listOf(
                    "-hide_banner", "-loglevel", "error", "-y",
                    "-f", "lavfi", "-i", "testsrc=duration=2:size=320x240:rate=15",
                    "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", fixture.path,
                ),
                120_000,
            )
            assertTrue("FFmpeg no generó el video de prueba: ${r.lastLines}", r.ok && fixture.length() > 0)
        }
        queue.testExtraArgs = listOf("--enable-file-urls")
        queue.startServiceOnEnqueue = false // sin servicio: la prueba corre el trabajo con runJob, igual que él
    }

    @After
    fun tearDown() {
        queue.testExtraArgs = emptyList()
        queue.startServiceOnEnqueue = true
        for (uri in published) runCatching { app.contentResolver.delete(Uri.parse(uri), null, null) }
    }

    private fun download(option: String): Job {
        val result = runBlocking { queue.enqueue(Uri.fromFile(fixture).toString(), option, allowDuplicate = true) }
        assertTrue("$result", result is DownloadQueue.EnqueueResult.Queued)
        val id = (result as DownloadQueue.EnqueueResult.Queued).id
        runBlocking { withTimeout(240_000) { queue.runJob(queue.jobs.value.first { it.id == id }) } }
        val job = queue.jobs.value.firstOrNull { it.id == id }
        assertNotNull(job)
        job!!.contentUri?.let { published += it }
        assertEquals("Error: ${job.error?.code} ${job.error?.details}", "done", job.state)
        return job
    }

    private fun mimeAndName(uri: String): Pair<String?, String?> {
        val cols = arrayOf(MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DISPLAY_NAME)
        return app.contentResolver.query(Uri.parse(uri), cols, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) to c.getString(1) else null
        } ?: (null to null)
    }

    @Test
    fun videoIsDownloadedAndPublishedToDownloads() {
        val job = download("recommended")
        assertEquals("video/mp4", job.mime)
        assertTrue(job.displayName!!, job.displayName.endsWith(".mp4"))
        assertTrue(job.total > 0)
        if (Build.VERSION.SDK_INT >= 29) {
            val (mime, name) = mimeAndName(job.contentUri!!)
            assertEquals("video/mp4", mime)
            assertEquals(job.displayName, name)
        }
        // La carpeta privada del trabajo se borra después de publicar, y queda en el historial.
        assertTrue(!File(app.engine.dirs.root, "jobs/${job.id}").exists())
        assertTrue(queue.history.value.any { it.id == job.id && it.contentUri == job.contentUri })
    }

    @Test
    fun analyzingALocalFileGivesTitleAndFormats() {
        app.analyzer.testExtraArgs = listOf("--enable-file-urls")
        try {
            val r = runBlocking { withTimeout(180_000) { app.analyzer.analyze(Uri.fromFile(fixture).toString()) } }
            assertTrue("$r", r is MediaAnalyzer.Result.Ok)
            val a = (r as MediaAnalyzer.Result.Ok).analysis
            assertTrue(a.summary.title, a.summary.title.contains("prueba"))
            assertTrue(a.tiles.first { it.key == "recommended" }.available)
            assertTrue(a.tiles.first { it.key == "mp3" }.available)
            // La carpeta del análisis no queda.
            assertTrue(File(app.engine.dirs.root, "probes").listFiles().orEmpty().isEmpty())
        } finally {
            app.analyzer.testExtraArgs = emptyList()
        }
    }

    @Test
    fun prefillGivesTheJobItsTitleBeforeItStarts() {
        val prefill = DownloadQueue.Prefill("Título del análisis", "Canal", 12.0, null, null)
        val r = runBlocking { queue.enqueue(Uri.fromFile(fixture).toString(), "mp3", allowDuplicate = true, prefill = prefill) }
        val id = (r as DownloadQueue.EnqueueResult.Queued).id
        val job = queue.jobs.value.first { it.id == id }
        assertEquals("Título del análisis", job.title)
        assertEquals(12L, job.duration)
        queue.cancel(id)
        queue.remove(id)
    }

    @Test
    fun mp3IsConvertedAndPublished() {
        val job = download("mp3")
        assertEquals("audio/mpeg", job.mime)
        assertTrue(job.displayName!!, job.displayName.endsWith(".mp3"))
    }

    @Test
    fun sameNameTwiceGetsANumberInsteadOfOverwriting() {
        assumeTrue("MediaStore (Android 10+)", Build.VERSION.SDK_INT >= 29)
        val publisher = Publisher(app)
        val src = File(app.cacheDir, "kd-dup-test.mp4").apply { writeBytes(fixture.readBytes()) }
        val first = publisher.publish(src, "dupjob01", null, { false }) {}
        val second = publisher.publish(src, "dupjob02", null, { false }) {}
        published += listOf(first.uri, second.uri)
        assertEquals("kd-dup-test.mp4", first.displayName)
        assertEquals("kd-dup-test (1).mp4", second.displayName)
        src.delete()
    }
}
