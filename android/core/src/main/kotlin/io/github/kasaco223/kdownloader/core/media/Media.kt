// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.core.media

import io.github.kasaco223.kdownloader.core.Urls

/** Una altura disponible del video (la dimensión menor, como `res` de yt-dlp: sirve para los verticales). */
data class HeightInfo(val height: Int, val fps: Int, val hdr: Boolean)

/** Un video de una lista (`--flat-playlist`): sin formatos, solo lo necesario para encolarlo. */
data class PlaylistEntry(val id: String?, val title: String?, val url: String?, val duration: Double?)

/**
 * Resumen del `-J` de yt-dlp para la interfaz, sin URLs de medios. Port de `ytdlp.summarize()`,
 * `ytdlp.is_degraded()` y `media.site_name()` de Windows.
 */
data class MediaSummary(
    val id: String?,
    val title: String,
    val uploader: String,
    val duration: Double?,
    val thumbnail: String?,
    val url: String?,
    val extractor: String?,
    val isLive: Boolean,
    val ageLimit: Int,
    val isPlaylist: Boolean,
    val heights: List<HeightInfo> = emptyList(),
    val entries: List<PlaylistEntry> = emptyList(),
    val totalDuration: Double = 0.0,
) {
    val maxHeight: Int get() = heights.firstOrNull()?.height ?: 0
    val hasVideo: Boolean get() = heights.isNotEmpty()
}

object Media {
    /** Verdadero como en Python: null, false, 0, "" y colecciones vacías son falsos. */
    internal fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty()
        is Collection<*> -> v.isNotEmpty()
        is Map<*, *> -> v.isNotEmpty()
        else -> true
    }

    /** `a or b or …` de Python. */
    internal fun or(vararg v: Any?): Any? = v.firstOrNull { truthy(it) } ?: v.lastOrNull()

    internal fun Map<String, Any?>.text(key: String): String? = this[key]?.let { if (it is String) it else it.toString() }

    internal fun num(v: Any?): Double? = (v as? Number)?.toDouble()?.takeIf { !it.isNaN() }

    /** `int(x or 0)` de Python (trunca hacia cero). */
    internal fun int(v: Any?): Long = if (truthy(v)) (num(v) ?: 0.0).toLong() else 0L

    /** `min(int(height or 0), int(width or 10**6))`. */
    internal fun res(f: Map<String, Any?>): Int = minOf(int(f["height"]), if (truthy(f["width"])) int(f["width"]) else 1_000_000L).toInt()

    internal fun vcodec(f: Map<String, Any?>): String = (or(f["vcodec"], "none") as? String ?: "none")

    internal fun acodec(f: Map<String, Any?>): String = (or(f["acodec"], "none") as? String ?: "none")

    @Suppress("UNCHECKED_CAST")
    internal fun formats(info: Map<String, Any?>): List<Map<String, Any?>> =
        (info["formats"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.map { it as Map<String, Any?> }.orEmpty()

    @Suppress("UNCHECKED_CAST")
    fun summarize(info: Map<String, Any?>): MediaSummary {
        val isList = info["_type"] == "playlist"
        val thumbs = (info["thumbnails"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.map { it as Map<String, Any?> }
            ?.filter { (it["url"]?.toString() ?: "").startsWith("https://") }.orEmpty()
        var thumb = (info["thumbnail"] as? String)?.takeIf { it.startsWith("https://") }
        if (thumb == null && thumbs.isNotEmpty()) {
            thumb = thumbs.maxWithOrNull(compareBy({ num(it["preference"]) ?: 0.0 }, { num(it["width"]) ?: 0.0 }))?.text("url")
        }
        val base = MediaSummary(
            id = info.text("id"),
            title = (or(info["title"], info["id"], "") ?: "").toString(),
            uploader = (or(info["channel"], info["uploader"], "") ?: "").toString(),
            duration = num(info["duration"]),
            thumbnail = thumb,
            url = (or(info["webpage_url"], info["original_url"]) as? String),
            extractor = (or(info["extractor_key"], info["extractor"]) as? String),
            isLive = truthy(info["is_live"]),
            ageLimit = int(info["age_limit"]).toInt(),
            isPlaylist = isList,
        )
        if (isList) {
            val entries = (info["entries"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.map { e ->
                val m = e as Map<String, Any?>
                PlaylistEntry(m.text("id"), (or(m["title"], m["id"]) as? String), (or(m["url"], m["webpage_url"]) as? String), num(m["duration"]))
            }.orEmpty()
            return base.copy(entries = entries, totalDuration = entries.sumOf { it.duration ?: 0.0 })
        }
        val heights = LinkedHashMap<Int, HeightInfo>()
        for (f in formats(info)) {
            if (vcodec(f) == "none" || !truthy(f["height"])) continue
            val h = res(f)
            val cur = heights[h] ?: HeightInfo(h, 0, false)
            heights[h] = cur.copy(
                fps = maxOf(cur.fps, int(f["fps"]).toInt()),
                hdr = cur.hdr || (f["dynamic_range"] != null && f["dynamic_range"] != "SDR"),
            )
        }
        return base.copy(heights = heights.values.sortedByDescending { it.height })
    }

    /** YouTube entregó solo el formato 18 o ≤ 360p: motor viejo o bloqueo (el 403 que originó la app). */
    fun isDegraded(info: Map<String, Any?>): Boolean {
        if (!Urls.isYoutube(info["webpage_url"]?.toString() ?: "") || truthy(info["is_live"])) return false
        val video = formats(info).filter { vcodec(it) != "none" }
        if (video.isEmpty()) return false
        val ids = video.map { it["format_id"].toString() }.toSet()
        val maxH = video.maxOf { res(it) }
        return ids.all { it == "18" } || (maxH <= 360 && (num(info["duration"]) ?: 0.0) > 0 && "18" in ids)
    }

    /** "YouTube", el extractor de yt-dlp, o el dominio si es el genérico. */
    fun siteName(info: Map<String, Any?>, url: String): String {
        val key = info["extractor_key"]?.toString() ?: ""
        if (key.lowercase().startsWith("youtube")) return "YouTube"
        if (key.isNotEmpty() && key.lowercase() != "generic") return key
        return (Urls.host(url) ?: "").removePrefix("www.")
    }
}
