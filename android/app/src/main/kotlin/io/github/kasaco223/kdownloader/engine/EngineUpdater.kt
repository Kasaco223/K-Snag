// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.engine

import android.os.Process
import android.system.Os
import io.github.kasaco223.kdownloader.BuildConfig
import io.github.kasaco223.kdownloader.core.engine.UpdateParsing
import io.github.kasaco223.kdownloader.core.engine.YtdlpVersion
import io.github.kasaco223.kdownloader.engine.EngineStore.Companion.toHex
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Baja una versión nueva de yt-dlp desde los releases de GitHub (diseño del motor §4, pasos 1 a 6): el tag de
 * `releases/latest` (sin la API, sin cuota), los candados de `_update_spec`, el SHA-256 de `SHA2-256SUMS`, la
 * descarga verificada mientras llega y la prueba de humo. No activa nada: [KdEngine] decide.
 */
class EngineUpdater(private val dirs: EngineDirs, private val runner: PyRunner) {
    sealed interface Result {
        data class UpToDate(val version: String) : Result

        data class Downloaded(val copy: YtdlpCopy) : Result

        data class Failed(val reason: String) : Result
    }

    fun check(active: YtdlpCopy?, channel: String, skip: Set<String>, minimum: String, mode: PyRunner.Mode): Result = try {
        run(active, channel, skip, minimum, mode)
    } catch (e: IOException) {
        Result.Failed(e.message ?: e.toString())
    }

    private fun run(active: YtdlpCopy?, channel: String, skip: Set<String>, minimum: String, mode: PyRunner.Mode): Result {
        val repo = UpdateParsing.repo(channel)
        val latest = UpdateParsing.latestTag(location("https://github.com/$repo/releases/latest"))
            ?: return Result.Failed("sin tag en releases/latest")
        val activeVersion = active?.let { YtdlpVersion.parse(it.version) }
        val sameChannel = active?.channel == channel
        if ((sameChannel && active?.version == latest) || latest in skip) return Result.UpToDate(active?.version ?: latest)

        var target = latest
        val spec = getText("https://github.com/$repo/releases/latest/download/_update_spec", 64 * 1024, allow404 = true)
        if (spec != null) {
            val id = UpdateParsing.identifier(KdEngine.PYTHON_VERSION, Os.uname().machine, Process.is64Bit())
            UpdateParsing.lockedTarget(spec, repo, id)?.let { lock ->
                if (YtdlpVersion.parse(lock)?.let { it < YtdlpVersion.parse(latest)!! } == true) target = lock
            }
        }
        val targetVersion = YtdlpVersion.parse(target) ?: return Result.Failed("tag inválido: $target")
        if (sameChannel && activeVersion != null && targetVersion <= activeVersion) return Result.UpToDate(active.version)

        val sums = getText("https://github.com/$repo/releases/download/$target/SHA2-256SUMS", 64 * 1024, allow404 = false)!!
        val sha = UpdateParsing.sumFor(sums) ?: return Result.Failed("SHA2-256SUMS sin yt-dlp")
        val file = dirs.ytdlpFile(target)
        download("https://github.com/$repo/releases/download/$target/yt-dlp", file, sha)

        // Prueba de humo (6a): --version tiene que decir exactamente el tag.
        val runDir = File(dirs.runs, "update").apply { deleteRecursively(); mkdirs() }
        try {
            val r = runner.run(listOf(file.path, "--version"), runDir, mode, 60_000)
            val said = UpdateParsing.versionOutput(r.stdout)
            if (!r.ok || said != target) {
                file.parentFile?.deleteRecursively()
                return Result.Failed("la prueba falló: ${r.lastLines.takeLast(2)}")
            }
            // 6b: QuickJS. Si no lo reconoce, se descarta, salvo que la activa esté por debajo del mínimo.
            val v = runner.run(listOf(file.path, "-v", "--no-js-runtimes", "--js-runtimes", "quickjs:${dirs.lib("libqjs.so").path}"), runDir, mode, 60_000)
            val belowMinimum = activeVersion == null || activeVersion < (YtdlpVersion.parse(minimum) ?: activeVersion)
            if (!UpdateParsing.jsRuntimeOk(v.stderr + v.stdout) && !belowMinimum) {
                file.parentFile?.deleteRecursively()
                return Result.Failed("quickjs_unsupported")
            }
        } finally {
            runDir.deleteRecursively()
        }
        return Result.Downloaded(YtdlpCopy(target, channel, sha, "github", System.currentTimeMillis()))
    }

    private fun open(url: String, method: String = "GET"): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.useCaches = false
        conn.setRequestProperty("User-Agent", USER_AGENT)
        return conn
    }

    /** La cabecera Location de un 302, sin seguirlo. */
    private fun location(url: String): String? {
        val conn = open(url, "HEAD")
        conn.instanceFollowRedirects = false
        try {
            if (conn.responseCode !in 300..399) throw IOException("HTTP ${conn.responseCode} en $url")
            return conn.getHeaderField("Location")
        } finally {
            conn.disconnect()
        }
    }

    private fun getText(url: String, max: Int, allow404: Boolean): String? {
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code == 404 && allow404) return null
            if (code != 200) throw IOException("HTTP $code en $url")
            val bytes = conn.inputStream.use { input -> input.readNBytesCompat(max + 1) }
            if (bytes.size > max) throw IOException("respuesta demasiado grande: $url")
            if (UpdateParsing.looksLikeHtml(bytes.copyOf(minOf(512, bytes.size)))) throw IOException("HTML en lugar de datos: $url")
            return String(bytes, Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    private fun download(url: String, target: File, sha: String) {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "yt-dlp.part")
        val conn = open(url)
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} en $url")
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            var head: ByteArray? = null
            conn.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (head == null) head = buf.copyOf(minOf(512, n))
                        total += n
                        if (total > MAX_YTDLP) throw IOException("yt-dlp de más de 16 MiB")
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                    out.fd.sync()
                }
            }
            if (head?.let(UpdateParsing::looksLikeHtml) == true) throw IOException("HTML en lugar de yt-dlp")
            val got = digest.digest().toHex()
            if (got != sha) throw IOException("SHA-256 distinto ($got)")
            if (!part.renameTo(target)) throw IOException("no se pudo instalar ${target.path}")
        } catch (e: IOException) {
            part.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (out.size() < n) {
            val r = read(buf, 0, minOf(buf.size, n - out.size()))
            if (r < 0) break
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }

    companion object {
        private const val MAX_YTDLP = 16L * 1024 * 1024
        val USER_AGENT = "K-Downloader-Android/${BuildConfig.VERSION_NAME} (+https://github.com/Kasaco223/K-YoutubeVideoDownloaderHD)"
    }
}
