// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.engine

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kasaco223.kdownloader.BuildConfig
import io.github.kasaco223.kdownloader.KdlApp
import io.github.kasaco223.kdownloader.contracts.EngineStatus
import io.github.kasaco223.kdownloader.core.engine.EngineErrors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Pruebas del motor en el emulador, sin red (diseño del motor §13, pruebas 1 a 4, más la de 16 KB).
 * Corren en orden: la primera prepara el motor desde cero y las demás lo usan.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EngineTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KdlApp
    private val engine = app.engine
    private val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)

    private fun ready(): EngineStatus.Ready {
        val status = runBlocking { withTimeout(300_000) { engine.ensureReady() } }
        assertTrue("El motor no quedó listo: $status", status is EngineStatus.Ready)
        return status as EngineStatus.Ready
    }

    @Test
    fun t0_sixteenKbPagesReportIncompatibleUntilM5() {
        assumeTrue("Solo en celulares con páginas de 16 KB", pageSize == 16_384L)
        val status = runBlocking { withTimeout(300_000) { engine.ensureReady() } }
        assertTrue("$status", status is EngineStatus.Failed)
        assertEquals(EngineErrors.INCOMPATIBLE, (status as EngineStatus.Failed).error.code)
    }

    @Test
    fun t1_preparesFromScratchAndVerifiesEverything() {
        assumeTrue("El motor necesita páginas de 4 KB hasta M5", pageSize == 4096L)
        val versions = ready().versions
        assertEquals(BuildConfig.YTDLP_BUNDLED_VERSION, versions.ytdlp)
        val dirs = engine.dirs
        assertEquals(BuildConfig.CACERT_SHA256, EngineStore.sha256(dirs.caFile.inputStream()))
        // Los enlaces sin versión que taparían a las bibliotecas del sistema no existen (youtubedl-android #318).
        for (name in listOf("libcrypto.so", "libssl.so", "libz.so", "liblzma.so")) {
            val path = File(dirs.payloadDir("python"), "usr/lib/$name").path
            val missing = try {
                Os.lstat(path)
                false
            } catch (e: ErrnoException) {
                e.errno == OsConstants.ENOENT
            }
            assertTrue("No debería existir $path", missing)
        }
        // Con todo instalado, volver a preparar solo revisa marcas y hashes.
        val start = System.nanoTime()
        ready()
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("La segunda preparación tardó $ms ms", ms < 2_000)
    }

    @Test
    fun t2_runnerGivesPythonItsOwnSessionAndEnvironment() {
        assumeTrue(pageSize == 4096L)
        ready()
        // Con toybox setsid (Android 8+), Python tiene que quedar en su propia sesión: si no, cancelar no detiene
        // a FFmpeg ni a QuickJS. Que caiga al modo PID es un error a corregir, no un respaldo aceptable aquí.
        if (PyRunner.sessionToolsPresent()) assertEquals(PyRunner.Mode.SESSION, engine.runMode())
        val ownCgroup = File("/proc/self/cgroup").readText().trim()
        val code = "import os;print(os.getpid(),os.getpgrp(),os.getsid(0));" +
            "print(os.environ['SSL_CERT_FILE']);print(os.environ['TMPDIR']);" +
            "print(open('/proc/self/cgroup').read().strip().replace(chr(10),'|'))"
        val runDir = File(engine.dirs.runs, "test-runner").apply { deleteRecursively(); mkdirs() }
        repeat(20) { i ->
            val r = engine.runner.run(listOf("-c", code), runDir, engine.runMode(), 60_000)
            assertTrue("Ejecución $i: ${r.lastLines}", r.ok)
            val (pid, pgrp, sid) = r.stdout[0].split(' ').map { it.toInt() }
            assertEquals(r.pid, pid)
            if (engine.runMode() == PyRunner.Mode.SESSION) {
                assertEquals(pid, pgrp)
                assertEquals(pid, sid)
            }
            assertEquals(engine.dirs.caFile.path, r.stdout[1])
            assertEquals(File(runDir, "tmp").path, r.stdout[2])
            // Los hijos quedan en el cgroup de la app: Android los cierra junto con ella.
            assertEquals(ownCgroup.replace('\n', '|'), r.stdout[3])
        }
        runDir.deleteRecursively()
    }

    @Test
    fun t3_cancelStopsPythonAndItsFfmpegChild() {
        assumeTrue(pageSize == 4096L)
        ready()
        assumeTrue("Solo con sesión propia (el modo PID no detiene a los hijos)", engine.runMode() == PyRunner.Mode.SESSION)
        val runDir = File(engine.dirs.runs, "test-cancel").apply { deleteRecursively(); mkdirs() }
        val ffmpeg = engine.dirs.lib("libffmpeg.so").path
        val code = "import os,subprocess,time;" +
            "p=subprocess.Popen([os.environ['KD_FFMPEG'],'-hide_banner','-loglevel','error','-f','lavfi'," +
            "'-i','sine=frequency=440:duration=600','-f','null','-']);print('started',p.pid,flush=True);time.sleep(600)"
        val run = engine.runner.start(listOf("-c", code), runDir, engine.runMode(), mapOf("KD_FFMPEG" to ffmpeg))
        val deadline = System.currentTimeMillis() + 60_000
        while (run.stdoutSoFar().none { it.startsWith("started") } && System.currentTimeMillis() < deadline) Thread.sleep(200)
        assertTrue("FFmpeg no arrancó: ${run.stdoutSoFar()}", run.stdoutSoFar().any { it.startsWith("started") })
        run.cancel()
        val result = run.await(10_000)
        assertTrue(result.canceled)
        var left = ffmpegProcesses(ffmpeg)
        val until = System.currentTimeMillis() + 6_000
        while (left.isNotEmpty() && System.currentTimeMillis() < until) {
            Thread.sleep(200)
            left = ffmpegProcesses(ffmpeg)
        }
        assertTrue("Quedaron procesos de FFmpeg: $left", left.isEmpty())
        runDir.deleteRecursively()
    }

    @Test
    fun t4_repairReinstallsInTheSameProcess() {
        assumeTrue(pageSize == 4096L)
        ready()
        val lib = File(engine.dirs.payloadDir("python"), "usr/lib/libpython3.12.so.1.0")
        assertTrue(lib.delete())
        val result = runBlocking { withTimeout(300_000) { engine.repair() } }
        assertTrue("Reparar falló: ${result.exceptionOrNull()}", result.isSuccess)
        assertTrue(lib.exists())
        assertTrue(engine.status.value is EngineStatus.Ready)
        assertFalse(engine.activeYtdlp() == null)
    }

    /** PIDs de procesos de esta app cuyo programa es libffmpeg.so. */
    private fun ffmpegProcesses(ffmpeg: String): List<Int> {
        val uid = Os.getuid()
        return File("/proc").listFiles().orEmpty().mapNotNull { dir ->
            val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
            val owner = runCatching { Os.stat(dir.path).st_uid }.getOrNull()
            val cmd = runCatching { File(dir, "cmdline").readText() }.getOrNull()
            pid.takeIf { owner == uid && cmd != null && cmd.startsWith(ffmpeg) }
        }
    }
}
