// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.core.media

import io.github.kasaco223.kdownloader.core.media.Media.acodec
import io.github.kasaco223.kdownloader.core.media.Media.formats
import io.github.kasaco223.kdownloader.core.media.Media.int
import io.github.kasaco223.kdownloader.core.media.Media.num
import io.github.kasaco223.kdownloader.core.media.Media.res
import io.github.kasaco223.kdownloader.core.media.Media.truthy
import io.github.kasaco223.kdownloader.core.media.Media.vcodec

/**
 * Una opción de descarga con lo que yt-dlp va a elegir: [key] es la opción de la cola (recommended, max, mp3,
 * m4a o h<N>). [size] en bytes; [approx] cuando sale de un bitrate o une dos partes.
 */
data class Tile(
    val key: String,
    val kind: String,
    val container: String,
    val height: Int? = null,
    val fps: Int? = null,
    val vcodec: String? = null,
    val acodec: String? = null,
    val hdr: Boolean = false,
    val size: Long? = null,
    val approx: Boolean = true,
    val available: Boolean = true,
)

/**
 * Las opciones calculadas en Kotlin, sin procesos de simulación (diseño del motor §7). Imitan el `-S` de cada
 * preset; yt-dlp decide igual al bajar y `fallback_best` cubre cualquier diferencia.
 *
 * - [main]: recommended, max, mp3 y m4a, con la fórmula del diseño (vectores de `tiles.py`).
 * - [heights]: «Más calidades», port literal de `media.estimate_heights()` de Windows.
 *
 * Un tamaño que une video y audio es siempre aproximado: el contenedor suma unos KB.
 */
object Tiles {
    const val MP3_V0_BPS = 245_000L

    internal fun vfam(codec: Any?): String? {
        val c = ((codec as? String) ?: "none").lowercase()
        return when {
            c == "none" -> null
            c.startsWith("avc") || c.startsWith("h264") -> "h264"
            c.startsWith("vp9") || c.startsWith("vp09") -> "vp9"
            c.startsWith("av01") || c.startsWith("av1") -> "av1"
            else -> "other"
        }
    }

    internal fun afam(codec: Any?): String? {
        val c = ((codec as? String) ?: "none").lowercase()
        return when {
            c == "none" -> null
            c.startsWith("mp4a") || c.startsWith("aac") -> "aac"
            c.startsWith("opus") -> "opus"
            c.startsWith("mp3") -> "mp3"
            else -> "other"
        }
    }

    private fun hasSize(f: Map<String, Any?>): Int = if (truthy(f["filesize"]) || truthy(f["filesize_approx"])) 1 else 0

    private fun rate(f: Map<String, Any?>, vararg keys: String): Double = keys.firstNotNullOfOrNull { k -> num(f[k])?.takeIf { it != 0.0 } } ?: 0.0

    /** Tamaño de un formato; si yt-dlp no lo trae (HLS), bitrate × duración. */
    internal fun size(f: Map<String, Any?>, duration: Double?): Pair<Long?, Boolean> {
        if (truthy(f["filesize"])) return int(f["filesize"]) to false
        if (truthy(f["filesize_approx"])) return int(f["filesize_approx"]) to true
        val tbr = rate(f, "tbr", "vbr", "abr")
        if (tbr != 0.0 && duration != null && duration != 0.0) return (tbr * 1000 / 8 * duration).toLong() to true
        return null to true
    }

    private fun audioOnly(all: List<Map<String, Any?>>) = all.filter { vcodec(it) == "none" && acodec(it) != "none" }

    private fun isSdr(f: Map<String, Any?>) = if (f["dynamic_range"] == null || f["dynamic_range"] == "SDR") 1 else 0

    private fun codecRank(f: Map<String, Any?>, maxCodec: String): Int {
        val rank = if (maxCodec == "av1") listOf("av1", "vp9.2", "vp9", "h265", "h264", "other") else listOf("vp9", "h265", "h264", "other", "vp9.2", "av1")
        val c = ((f["vcodec"] as? String) ?: "").lowercase()
        val fam = when {
            c.startsWith("vp09.02") || c.startsWith("vp9.2") -> "vp9.2"
            c.startsWith("hev") || c.startsWith("hvc") || c.startsWith("h265") -> "h265"
            else -> vfam(c)
        }
        return rank.size - rank.indexOf(if (fam in rank) fam else "other")
    }

    private fun merge(key: String, container: String, v: Map<String, Any?>, a: Map<String, Any?>?, duration: Double?, hdr: Boolean): Tile {
        val (vs, va) = size(v, duration)
        val (asz, aa) = if (a != null) size(a, duration) else (0L to false)
        val total = ((vs ?: 0L) + (asz ?: 0L)).takeIf { it != 0L }
        return Tile(
            key = key, kind = "video", container = container, height = res(v), fps = int(v["fps"]).toInt().takeIf { it != 0 },
            vcodec = vfam(v["vcodec"]), acodec = afam((a ?: v)["acodec"]), hdr = hdr, size = total,
            approx = a != null || va || aa,
        )
    }

    /** Las cuatro opciones principales. Una lista ([MediaSummary.isPlaylist]) no tiene tamaños. */
    fun main(info: Map<String, Any?>, summary: MediaSummary, maxCodec: String = "vp9"): List<Tile> {
        if (summary.isPlaylist) {
            return listOf(Tile("recommended", "video", "mp4"), Tile("max", "video", "mkv"), Tile("mp3", "audio", "mp3"), Tile("m4a", "audio", "m4a"))
        }
        val all = formats(info)
        val duration = summary.duration
        val video = all.filter { vcodec(it) != "none" && res(it) > 0 }
        val audio = audioOnly(all)
        fun bestAudio(prefer: String) = (audio.filter { afam(it["acodec"]) == prefer }.ifEmpty { audio })
            .maxWithOrNull(compareBy({ num(it["language_preference"]) ?: 0.0 }, { hasSize(it) }, { rate(it, "tbr", "abr") }))

        val tiles = mutableListOf<Tile>()
        // Un archivo directo (extractor genérico) suele no decir sus códecs: se ofrece todo, sin tamaños.
        val unknown = all.any { it["vcodec"] == null && it["acodec"] == null }
        val canVideo = summary.hasVideo && video.isNotEmpty()
        if (canVideo) {
            val pool = video.filter { vfam(it["vcodec"]) == "h264" }.ifEmpty { video }
            val pick = pool.filter { res(it) <= 1080 }
                .maxWithOrNull(compareBy({ res(it) }, { num(it["fps"]) ?: 0.0 }, { hasSize(it) }, { rate(it, "tbr") }))
                ?: pool.minWith(compareBy { res(it) })
            val aud = if (acodec(pick) == "none") bestAudio("aac") else null
            tiles += merge("recommended", "mp4", pick, aud, duration, isSdr(pick) == 0)
            val best = video.maxWith(
                compareBy({ res(it) }, { num(it["fps"]) ?: 0.0 }, { isSdr(it) }, { codecRank(it, maxCodec) }, { hasSize(it) }, { rate(it, "tbr") }),
            )
            val bestAud = if (acodec(best) == "none") bestAudio("opus") else null
            tiles += merge("max", "mkv", best, bestAud, duration, isSdr(best) == 0)
        } else {
            tiles += Tile("recommended", "video", "mp4", available = unknown)
            tiles += Tile("max", "video", "mkv", available = unknown)
        }
        val hasAudio = unknown || all.any { acodec(it) != "none" }
        val mp3Size = when {
            duration != null && duration != 0.0 -> (duration * MP3_V0_BPS / 8).toLong()
            else -> bestAudio("opus")?.let { size(it, null).first }
        }
        tiles += Tile("mp3", "audio", "mp3", acodec = "mp3", size = mp3Size.takeIf { hasAudio }, approx = true, available = hasAudio)
        val m4a = bestAudio("aac")
        val (m4aSize, m4aApprox) = if (m4a != null) size(m4a, duration) else (null to true)
        tiles += Tile(
            "m4a", "audio", "m4a", acodec = "aac", size = m4aSize.takeIf { hasAudio },
            // Otro códec (Opus) se convierte a AAC: el tamaño cambia.
            approx = m4aApprox || afam(m4a?.get("acodec")) != "aac", available = hasAudio,
        )
        return tiles
    }

    /** «Más calidades»: cada altura disponible con su tamaño aproximado (port de `estimate_heights`). */
    fun heights(info: Map<String, Any?>, summary: MediaSummary, maxCodec: String = "vp9"): List<Tile> {
        val all = formats(info)
        val duration = summary.duration
        val audio = audioOnly(all)
        fun bestAudio(prefer: String) = (audio.filter { afam(it["acodec"]) == prefer }.ifEmpty { audio })
            .maxWithOrNull(compareBy({ hasSize(it) }, { rate(it, "tbr", "abr") }))

        return summary.heights.mapNotNull { h ->
            val height = h.height
            val container = if (height <= 1080) "mp4" else "mkv"
            val prefer = if (height <= 1080) "h264" else if (maxCodec == "vp9") "vp9" else "av1"
            val cands = all.filter { vcodec(it) != "none" && res(it) == height }
            val pool = cands.filter { vfam(it["vcodec"]) == prefer }.ifEmpty { cands }
            val video = pool.maxWithOrNull(compareBy({ num(it["fps"]) ?: 0.0 }, { hasSize(it) }, { rate(it, "tbr") })) ?: return@mapNotNull null
            val aud = if (acodec(video) == "none") bestAudio(if (container == "mp4") "aac" else "opus") else null
            val vs = size(video, duration).first
            val asz = aud?.let { size(it, duration).first } ?: 0L
            Tile(
                key = "h$height", kind = "video", container = container, height = height,
                fps = int(video["fps"]).toInt().takeIf { it != 0 }, vcodec = vfam(video["vcodec"]),
                acodec = afam((aud ?: video)["acodec"]), hdr = h.hdr,
                size = ((vs ?: 0L) + asz).takeIf { it != 0L }, approx = true,
            )
        }
    }
}
