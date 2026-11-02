// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.core.download

/**
 * Protocolo `KDL|…` que imprime yt-dlp con --progress-template y --print (port de `engine/progress.py`):
 *
 * ```
 * KDL|dl|<progreso>|<info>     progreso de un formato
 * KDL|pp|<posproceso>          inicio/fin de un posprocesador (Merger, ExtractAudio…)
 * KDL|file|"<ruta final>"      archivo terminado (after_move)
 * KDL|meta|<datos>             solo Android: título, id, tamaño esperado… antes de bajar (before_dl)
 * ```
 */
sealed interface KdlEvent {
    data class Dl(val data: Map<String, Any?>, val info: Map<String, Any?>) : KdlEvent
    data class Pp(val data: Map<String, Any?>) : KdlEvent
    data class File(val path: String) : KdlEvent
    data class Meta(val data: Map<String, Any?>) : KdlEvent
}

object Kdl {
    const val TEMPLATE_DL = "download:KDL|dl|%(progress.{status,downloaded_bytes,total_bytes,total_bytes_estimate," +
        "speed,eta,elapsed,fragment_index,fragment_count})j|%(info.{format_id,vcodec,acodec,height,ext})j"
    const val TEMPLATE_PP = "postprocess:KDL|pp|%(progress.{status,postprocessor})j"
    const val PRINT_FILE = "after_move:KDL|file|%(filepath)j"
    const val PRINT_META = "before_dl:KDL|meta|%(.{id,title,uploader,channel,thumbnail,duration,extractor_key," +
        "webpage_url,format_id,ext,filesize,filesize_approx,is_live})j"

    /**
     * El evento de una línea, o null si no es del protocolo o está dañada. Solo cuenta `KDL|` al inicio (tras
     * el último `\r`): yt-dlp repite la URL de la persona, y una URL con "KDL|file|…" no puede inyectar eventos.
     */
    @Suppress("UNCHECKED_CAST")
    fun parseLine(raw: String): KdlEvent? {
        val line = raw.trimEnd('\r', '\n').substringAfterLast('\r').trimStart()
        if (!line.startsWith("KDL|")) return null
        val rest = line.substring(4)
        val bar = rest.indexOf('|')
        if (bar < 0) return null
        val kind = rest.substring(0, bar)
        val payload = rest.substring(bar + 1)
        return try {
            when (kind) {
                "dl" -> {
                    val (data, end) = JsonStrict.rawDecode(payload)
                    if (data !is Map<*, *>) return null
                    val tail = payload.substring(end)
                    val info: Map<String, Any?> = if (tail.startsWith("|")) {
                        when (val i = JsonStrict.rawDecode(tail.substring(1)).value) {
                            is Map<*, *> -> i as Map<String, Any?>
                            else -> emptyMap()
                        }
                    } else {
                        emptyMap()
                    }
                    KdlEvent.Dl(data as Map<String, Any?>, info)
                }
                "pp" -> (JsonStrict.rawDecode(payload).value as? Map<*, *>)?.let { KdlEvent.Pp(it as Map<String, Any?>) }
                "file" -> (JsonStrict.rawDecode(payload).value as? String)?.takeIf { it.isNotEmpty() }?.let { KdlEvent.File(it) }
                "meta" -> (JsonStrict.rawDecode(payload).value as? Map<*, *>)?.let { KdlEvent.Meta(it as Map<String, Any?>) }
                else -> null
            }
        } catch (e: JsonStrict.JsonException) {
            null
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** "video", "audio" o "av" según los códecs del formato que se está bajando. */
    fun streamKind(info: Map<String, Any?>): String {
        val v = info.str("vcodec") ?: "none"
        val a = info.str("acodec") ?: "none"
        return when {
            v != "none" && a != "none" -> "av"
            v != "none" -> "video"
            else -> "audio"
        }
    }
}
