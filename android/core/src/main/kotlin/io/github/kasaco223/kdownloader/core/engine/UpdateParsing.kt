// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.core.engine

/**
 * Lo que el actualizador de yt-dlp lee de GitHub (diseño del motor §4), sin red ni Android para poder probarlo:
 * el tag de `releases/latest`, la línea de `SHA2-256SUMS`, los candados de `_update_spec` y las pistas de
 * `engine.json` (port de `hints.validate` de Windows).
 */
object UpdateParsing {
    private val TAG_IN_LOCATION = Regex("""^(?:https://github\.com)?/[^/?#]+/[^/?#]+/releases/tag/([^/?#]+)$""")
    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")
    private val PLAYER_CLIENT = Regex("^-?[a-z_]+(,-?[a-z_]+)*$")

    /** El repo de cada canal. */
    fun repo(channel: String): String = if (channel == "stable") "yt-dlp/yt-dlp" else "yt-dlp/yt-dlp-nightly-builds"

    /** El tag de la cabecera `Location` de `/releases/latest`, solo si es una versión de yt-dlp. */
    fun latestTag(location: String?): String? {
        val tag = location?.let { TAG_IN_LOCATION.find(it.trim())?.groupValues?.get(1) } ?: return null
        return tag.takeIf { YtdlpVersion.parse(it) != null }
    }

    /** El SHA-256 de [name] en un `SHA2-256SUMS` (`<hash>  <archivo>` o `<hash> *<archivo>`), en minúsculas. */
    fun sumFor(sums: String, name: String = "yt-dlp"): String? = sums.lineSequence().mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"), limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val file = parts[1].removePrefix("*")
        parts[0].takeIf { file == name && SHA256.matches(it) }?.lowercase()
    }.firstOrNull()

    /**
     * El identificador con el que yt-dlp se compara en `_update_spec` (yt_dlp/update.py): la variante `zip`,
     * Python y la máquina. Ejemplo: `zip Python 3.12.11 (CPython aarch64 64bit) - Linux`.
     */
    fun identifier(python: String, machine: String, is64: Boolean) =
        "zip Python $python (CPython $machine ${if (is64) "64bit" else "32bit"}) - Linux"

    /**
     * El tag al que hay que quedarse según los candados de `_update_spec` (líneas `lockV2 <repo> <tag> <regex>`,
     * y `lock <tag> <regex>` para el repo estable), o null si ninguno aplica. La regex se compara desde el
     * inicio, como `re.match` de Python.
     */
    fun lockedTarget(spec: String, repo: String, identifier: String): String? {
        for (raw in spec.lineSequence()) {
            val line = raw.trim()
            val parts = line.split(Regex("\\s+"), limit = 4)
            val (lockRepo, tag, pattern) = when {
                parts.size == 4 && parts[0] == "lockV2" -> Triple(parts[1], parts[2], parts[3])
                parts.size >= 3 && parts[0] == "lock" -> Triple("yt-dlp/yt-dlp", parts[1], line.split(Regex("\\s+"), limit = 3)[2])
                else -> continue
            }
            if (lockRepo != repo) continue
            val regex = runCatching { Regex("^(?:$pattern)") }.getOrNull() ?: continue
            if (regex.containsMatchIn(identifier)) return tag
        }
        return null
    }

    /** Una respuesta que en realidad es una página HTML (yt-dlp #17550). */
    fun looksLikeHtml(head: ByteArray): Boolean = String(head, Charsets.ISO_8859_1).contains("<!DOCTYPE html", ignoreCase = true)

    /** La salida de `yt-dlp --version`: la última línea no vacía, si es una versión. */
    fun versionOutput(lines: List<String>): String? = lines.lastOrNull { it.isNotBlank() }?.trim()?.takeIf { YtdlpVersion.parse(it) != null }

    /**
     * Prueba 6b: la línea `[debug] JS runtimes:` de `yt-dlp -v`. Falla solo si está y no menciona QuickJS o lo
     * marca `(unsupported)`; si la línea no está, pasa (el formato del encabezado puede cambiar).
     */
    fun jsRuntimeOk(lines: List<String>): Boolean {
        val line = lines.firstOrNull { "[debug] JS runtimes:" in it } ?: return true
        val qjs = line.substringAfter("JS runtimes:").split(",").map { it.trim() }.firstOrNull { it.startsWith("quickjs") } ?: return false
        return "(unsupported)" !in qjs
    }

    /** Las pistas remotas validadas (engine.json); todo lo que no pasa se descarta. */
    data class Hints(
        val minYtdlp: Map<String, String> = emptyMap(),
        val youtubePlayerClient: String? = null,
        val notice: Map<String, String> = emptyMap(),
    )

    fun hints(raw: Any?): Hints {
        val m = raw as? Map<*, *> ?: return Hints()
        if ((m["schema"] as? Number)?.toLong() != 1L) return Hints()
        val mins = (m["min_ytdlp"] as? Map<*, *>)?.let { mm ->
            listOf("nightly", "stable").mapNotNull { ch -> (mm[ch] as? String)?.takeIf { YtdlpVersion.parse(it) != null }?.let { ch to it } }.toMap()
        }.orEmpty()
        val client = (m["youtube_player_client"] as? String)?.takeIf { it.length <= 120 && PLAYER_CLIENT.matches(it) }
        val notice = (m["notice"] as? Map<*, *>)?.let { nm ->
            listOf("es", "en").mapNotNull { lang -> (nm[lang] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { lang to it.take(280) } }.toMap()
        }.orEmpty()
        return Hints(mins, client, notice)
    }

    /** Mínimos de la app cuando no hay pistas (iguales a los de Windows). */
    val MIN_YTDLP = mapOf("nightly" to "2026.09.27.232945", "stable" to "2026.08.19")
}
