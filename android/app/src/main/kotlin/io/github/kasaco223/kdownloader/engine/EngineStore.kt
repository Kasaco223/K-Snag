// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.engine

import android.content.Context
import io.github.kasaco223.kdownloader.BuildConfig
import io.github.kasaco223.kdownloader.R
import io.github.kasaco223.kdownloader.core.engine.BundledPolicy
import io.github.kasaco223.kdownloader.core.engine.YtdlpVersion
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Las copias verificadas de yt-dlp (diseño del motor §4). En esta versión solo instala la que trae el APK
 * (res/raw/ytdlp, fijada por SHA-256 en engine-pins.properties); las actualizaciones desde GitHub, la vuelta
 * atrás y la lista de versiones descartadas llegan con el actualizador.
 */
class EngineStore(private val context: Context, private val dirs: EngineDirs) {
    val bundled: YtdlpVersion = YtdlpVersion.parse(BuildConfig.YTDLP_BUNDLED_VERSION)
        ?: error("YTDLP_BUNDLED_VERSION inválida: ${BuildConfig.YTDLP_BUNDLED_VERSION}")

    fun file(copy: YtdlpCopy): File = dirs.ytdlpFile(copy.version)

    fun verifies(copy: YtdlpCopy?): Boolean =
        copy != null && runCatching { sha256(file(copy).inputStream()) == copy.sha256 }.getOrDefault(false)

    /** Deja una copia activa que verifica y devuelve el estado nuevo (sin guardarlo). */
    fun ensureActive(state: EngineState, versionCode: Long): EngineState {
        val decision = BundledPolicy.decide(
            BundledPolicy.Input(
                active = state.active?.let { YtdlpVersion.parse(it.version) },
                activeChannel = state.active?.channel,
                activeVerified = verifies(state.active),
                previousVerified = verifies(state.previous),
                bundled = bundled,
                bundledChannel = BuildConfig.YTDLP_BUNDLED_CHANNEL,
                // Hasta que exista el ajuste «Canal del motor», el canal es el de la copia del APK.
                wantedChannel = state.active?.channel ?: BuildConfig.YTDLP_BUNDLED_CHANNEL,
                skipVersions = state.skipVersions,
                bundledOfferedVersionCode = state.bundledOfferedVersionCode,
                versionCode = versionCode,
            ),
        )
        var next = when (decision.action) {
            BundledPolicy.Action.KEEP -> state
            BundledPolicy.Action.USE_PREVIOUS -> state.copy(active = state.previous, previous = null)
            BundledPolicy.Action.INSTALL_BUNDLED -> {
                val copy = installBundled()
                val keep = state.active?.takeIf { verifies(it) && it.version != copy.version }
                state.copy(active = copy, previous = keep ?: state.previous?.takeIf { verifies(it) })
            }
        }
        if (decision.markOffered) next = next.copy(bundledOfferedVersionCode = versionCode)
        prune(next)
        return next
    }

    /** Copia res/raw/ytdlp a ytdlp/<versión>/yt-dlp calculando el SHA-256 mientras copia. */
    private fun installBundled(): YtdlpCopy {
        val version = BuildConfig.YTDLP_BUNDLED_VERSION
        val target = dirs.ytdlpFile(version)
        val part = File(target.parentFile, "yt-dlp.part")
        target.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        context.resources.openRawResource(R.raw.ytdlp).use { input ->
            FileOutputStream(part).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
                out.fd.sync()
            }
        }
        val got = digest.digest().toHex()
        if (got != BuildConfig.YTDLP_BUNDLED_SHA256) {
            part.delete()
            throw IOException("El yt-dlp del APK no coincide con su SHA-256 ($got)")
        }
        if (!part.renameTo(target)) throw IOException("No se pudo instalar ${target.path}")
        return YtdlpCopy(version, BuildConfig.YTDLP_BUNDLED_CHANNEL, got, "bundled", System.currentTimeMillis())
    }

    /** Borra las versiones que no son ni la activa ni la anterior. */
    private fun prune(state: EngineState) {
        val keep = setOfNotNull(state.active?.version, state.previous?.version)
        dirs.ytdlp.listFiles()?.filter { it.isDirectory && it.name !in keep }?.forEach { it.deleteRecursively() }
    }

    companion object {
        fun sha256(input: InputStream): String = input.use {
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
            digest.digest().toHex()
        }

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
