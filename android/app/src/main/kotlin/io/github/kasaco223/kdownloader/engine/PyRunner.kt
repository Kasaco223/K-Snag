// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.engine

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import io.github.kasaco223.kdownloader.core.engine.ProcStat
import io.github.kasaco223.kdownloader.core.engine.SessionProbe
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Corre Python (libpython.so de nativeLibraryDir) con el entorno del motor (diseño del motor §3):
 *
 * ```
 * /system/bin/setsid /system/bin/sh -c '<lanzador>' kd <nl>/libpython.so <args…>
 * lanzador: echo "KDPID:$$" >&2; export LD_LIBRARY_PATH="$KD_LIBRARY_PATH"; unset KD_LIBRARY_PATH; exec "$@"
 * ```
 *
 * - `setsid` primero: el hijo de ProcessBuilder nunca es líder de grupo, así que setsid hace exec en el mismo
 *   proceso; `sh` también. KDPID es entonces el PID de Python, que encabeza su propia sesión y su propio
 *   grupo, y cancelar puede detener a Python y a sus hijos (FFmpeg, QuickJS) con una sola señal.
 * - LD_LIBRARY_PATH lo exporta `sh` justo antes del exec: solo Python y sus hijos ven las bibliotecas del
 *   motor, y toybox (setsid, sh) sigue con las del sistema. No se usa `env`: el de toybox toma como opciones
 *   suyas los argumentos de Python (`-c`).
 * - En el modo PID (sin setsid o si la prueba de sesión falla) falta solo `setsid`, y no hay señales al grupo.
 *
 * Nunca manda una señal a un grupo (-pid) sin comprobar antes, en /proc/<pid>/stat, que ese grupo es de esta
 * ejecución.
 */
class PyRunner(private val dirs: EngineDirs) {
    enum class Mode(val label: String) { SESSION("session"), PID("pid") }

    data class Result(
        val exitCode: Int,
        val stdout: List<String>,
        val stderr: List<String>,
        val timedOut: Boolean,
        val canceled: Boolean,
        val pid: Int?,
    ) {
        val ok: Boolean get() = exitCode == 0 && !timedOut && !canceled
        val lastLines: List<String> get() = (stderr + stdout).filterNot { it.startsWith("KDPID:") }.takeLast(30)
    }

    /** Una ejecución en curso: [cancel] manda SIGINT al grupo y SIGKILL a los 5 s. */
    inner class Run internal constructor(
        private val process: Process,
        private val mode: Mode,
        private val out: LineCollector,
        private val err: LineCollector,
    ) {
        @Volatile private var canceled = false

        val pid: Int? get() = SessionProbe.parseKdpid(err.snapshot())

        /** Lo que escribió en stdout hasta ahora. */
        fun stdoutSoFar(): List<String> = out.snapshot()

        /** Grupo propio comprobado: este PID encabeza su grupo y no es el de la app. */
        private fun groupOk(pid: Int): Boolean {
            if (mode != Mode.SESSION) return false
            val stat = readStat(pid) ?: return false
            val app = readStat(Os.getpid()) ?: return false
            return stat.pgrp == pid && stat.pgrp != app.pgrp
        }

        private fun signal(sig: Int) {
            val pid = pid
            try {
                when {
                    pid != null && groupOk(pid) -> Os.kill(-pid, sig)
                    pid != null -> Os.kill(pid, sig)
                    else -> process.destroy()
                }
            } catch (_: ErrnoException) {
                // ESRCH: ya terminó
            }
        }

        fun cancel() {
            canceled = true
            signal(OsConstants.SIGINT)
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                signal(OsConstants.SIGKILL)
                process.destroyForcibly()
            }
        }

        fun await(timeoutMs: Long): Result {
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                signal(OsConstants.SIGKILL)
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
            val code = runCatching { process.exitValue() }.getOrDefault(-1)
            val pid = pid
            // Restos del grupo (un FFmpeg huérfano, por ejemplo). Linux no reusa un PID mientras sea el id de
            // un grupo que todavía existe, así que la señal no puede alcanzar a otro proceso.
            if (pid != null && mode == Mode.SESSION) {
                try {
                    Os.kill(-pid, OsConstants.SIGKILL)
                } catch (_: ErrnoException) {
                    // ESRCH: no quedó nadie
                }
            }
            out.join()
            err.join()
            return Result(code, out.snapshot(), err.snapshot(), timedOut = !finished, canceled = canceled, pid = pid)
        }
    }

    /**
     * [args] van después de libpython.so (por ejemplo `-c …` o la ruta de yt-dlp). [onLine] recibe cada línea de
     * stdout y stderr (hasta 64 KiB) en el hilo lector: tiene que ser rápido y no lanzar excepciones.
     */
    fun start(
        args: List<String>,
        runDir: File,
        mode: Mode,
        extraEnv: Map<String, String> = emptyMap(),
        onLine: ((String) -> Unit)? = null,
    ): Run {
        val tmp = File(runDir, "tmp").apply { mkdirs() }
        dirs.home.mkdirs()
        val python = dirs.lib("libpython.so").path
        val launcher = listOf(SH, "-c", LAUNCHER, "kd", python) + args
        val command = when (mode) {
            Mode.SESSION -> listOf(SETSID) + launcher
            Mode.PID -> launcher
        }
        val builder = ProcessBuilder(command).directory(runDir)
        builder.environment().apply {
            remove("LD_LIBRARY_PATH")
            put("KD_LIBRARY_PATH", dirs.libraryPath())
            put("SSL_CERT_FILE", dirs.caFile.path)
            put("PYTHONHOME", dirs.pythonHome().path)
            put("HOME", dirs.home.path)
            put("PYTHONNOUSERSITE", "1")
            put("PYTHONUTF8", "1")
            put("TMPDIR", tmp.path)
            put("PATH", (get("PATH") ?: "/system/bin") + ":" + dirs.nativeLibDir.path)
            putAll(extraEnv)
        }
        val process = builder.start()
        process.outputStream.close() // stdin cerrado: yt-dlp nunca espera una respuesta
        return Run(process, mode, LineCollector(process.inputStream, "stdout", onLine), LineCollector(process.errorStream, "stderr", onLine))
    }

    fun run(args: List<String>, runDir: File, mode: Mode, timeoutMs: Long): Result = start(args, runDir, mode).await(timeoutMs)

    /** libffmpeg.so con el mismo entorno, sin Python en el medio (paso c de la autoprueba). */
    fun ffmpeg(args: List<String>, timeoutMs: Long): Result {
        val builder = ProcessBuilder(listOf(dirs.lib("libffmpeg.so").path) + args).redirectErrorStream(true)
        builder.environment()["LD_LIBRARY_PATH"] = dirs.libraryPath()
        val process = builder.start()
        process.outputStream.close()
        val out = LineCollector(process.inputStream, "ffmpeg")
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        out.join()
        return Result(runCatching { process.exitValue() }.getOrDefault(-1), out.snapshot(), emptyList(), !finished, false, null)
    }

    /** Lee un flujo línea por línea en su propio hilo; guarda las últimas [MAX_LINES] (máximo 4 KiB cada una). */
    class LineCollector(stream: InputStream, name: String, private val onLine: ((String) -> Unit)? = null) {
        private val lines = ArrayDeque<String>()
        private val worker = thread(name = "kd-$name", isDaemon = true) {
            try {
                stream.bufferedReader(Charsets.UTF_8).useLines { seq ->
                    seq.forEach { line ->
                        try {
                            onLine?.invoke(line.take(64 * 1024))
                        } catch (e: RuntimeException) {
                            android.util.Log.w("PyRunner", "Error al procesar una línea", e)
                        }
                        synchronized(lines) {
                            lines.addLast(line.take(4096))
                            if (lines.size > MAX_LINES) lines.removeFirst()
                        }
                    }
                }
            } catch (_: java.io.IOException) {
                // el proceso terminó o se cerró el flujo
            }
        }

        fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

        fun join() = worker.join(5_000)
    }

    companion object {
        const val SETSID = "/system/bin/setsid"
        const val SH = "/system/bin/sh"
        private const val MAX_LINES = 400

        /** Script de `sh -c`: anuncia su PID (el de Python, tras el exec) y le pasa las bibliotecas del motor. */
        const val LAUNCHER =
            "echo \"KDPID:\$\$\" >&2; export LD_LIBRARY_PATH=\"\$KD_LIBRARY_PATH\"; unset KD_LIBRARY_PATH; exec \"\$@\""

        fun sessionToolsPresent(): Boolean = File(SETSID).exists()

        fun readStat(pid: Int): ProcStat? = runCatching { ProcStat.parse(File("/proc/$pid/stat").readText()) }.getOrNull()
    }
}
