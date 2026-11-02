// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.core.download

/**
 * Traduce la salida de yt-dlp a un error comprensible (port de `engine/errors.py`), con el mismo
 * `errors_catalog.json` que la app de Windows (MIT, sin cambios). La capa propia de Android
 * (errors_android.json: acciones, casos del teléfono) llega con los reintentos automáticos.
 */
class ErrorCatalog(catalog: Map<String, Any?>) {
    data class Texts(val title: String, val message: String)

    data class Classified(
        val code: String,
        val action: String,
        val auto: String?,
        val texts: Map<String, Texts>,
        /** Lo que se agrega al mensaje del caso general: la última línea limpia. */
        val suffix: String?,
        val details: String,
    ) {
        fun title(lang: String) = (texts[lang] ?: texts.getValue("es")).title

        fun message(lang: String) = (texts[lang] ?: texts.getValue("es")).message + (suffix?.let { " ($it)" } ?: "")
    }

    private data class Rule(val code: String, val match: List<String>, val action: String, val auto: String?, val texts: Map<String, Texts>)

    private val rules: List<Rule>
    private val fallback: Rule
    private val alreadyTokens: List<String>
    val version: Long

    init {
        @Suppress("UNCHECKED_CAST")
        fun rule(m: Map<String, Any?>) = Rule(
            code = m.str("code") ?: error("regla sin código"),
            match = (m["match"] as? List<*>)?.filterIsInstance<String>().orEmpty(),
            action = m.str("action") ?: "none",
            auto = m.str("auto"),
            texts = listOf("es", "en").associateWith { lang ->
                val t = m[lang] as Map<String, Any?>
                Texts(t.str("title").orEmpty(), t.str("message").orEmpty())
            },
        )
        rules = (catalog["rules"] as List<*>).map { @Suppress("UNCHECKED_CAST") rule(it as Map<String, Any?>) }
        @Suppress("UNCHECKED_CAST")
        fallback = rule(catalog["fallback"] as Map<String, Any?>)
        @Suppress("UNCHECKED_CAST")
        alreadyTokens = ((catalog["already_downloaded"] as? Map<String, Any?>)?.get("match") as? List<*>)
            ?.filterIsInstance<String>().orEmpty()
        version = (catalog["version"] as? Number)?.toLong() ?: 0
    }

    fun codes(): List<String> = rules.map { it.code } + fallback.code

    fun classify(lines: List<String>): Classified {
        val rel = relevantLines(lines)
        val haystack = rel.joinToString("\n").lowercase()
        val details = rel.joinToString("\n").takeLast(4000)
        for (r in rules) {
            if (r.match.any { it in haystack }) return Classified(r.code, r.action, r.auto, r.texts, null, details)
        }
        val last = rel.lastOrNull()?.let(::cleanMessage)?.take(200)?.takeIf { it.isNotEmpty() }
        return Classified(fallback.code, fallback.action, null, fallback.texts, last, details)
    }

    /** Un error conocido por código (disk_full, live_unsupported…); el caso general si no está. */
    fun byCode(code: String, details: String = ""): Classified {
        val r = rules.firstOrNull { it.code == code } ?: fallback
        return Classified(code, r.action, r.auto, r.texts, null, details)
    }

    fun isAlreadyDownloaded(lines: List<String>): Boolean =
        lines.any { line -> normalize(line).lowercase().let { l -> alreadyTokens.any { it in l } } }

    companion object {
        private val ANSI = Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]""")
        private val PREFIX = Regex("""^(ERROR|WARNING):\s*(\[[^\]]+\]\s*)?([A-Za-z0-9_-]{6,}:\s*)?""", RegexOption.IGNORE_CASE)
        private val URL = Regex("""\s*(See|Visit)?\s*https?://\S+""", RegexOption.IGNORE_CASE)

        fun parse(json: String): ErrorCatalog {
            @Suppress("UNCHECKED_CAST")
            return ErrorCatalog(JsonStrict.parse(json) as Map<String, Any?>)
        }

        fun normalize(text: String): String = ANSI.replace(text, "").replace('’', '\'').replace('‘', '\'')

        /** Las líneas ERROR: si existen; si no, la cola de la salida. */
        fun relevantLines(lines: List<String>): List<String> {
            val clean = lines.filter { it.isNotBlank() }.map { normalize(it).trim() }
            val errors = clean.filter { it.uppercase().startsWith("ERROR") }
            return errors.ifEmpty { clean.takeLast(30) }
        }

        /** Sin el prefijo `ERROR: [youtube] id:` ni las URLs de la FAQ. */
        fun cleanMessage(line: String): String =
            URL.replace(PREFIX.replace(normalize(line), "").trim(), "").trim(' ', '.', ';')
    }
}
