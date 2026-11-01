// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.core.download

/**
 * Lector de JSON estricto para la salida de yt-dlp (diseño del motor §8), como `json.JSONDecoder.raw_decode`
 * de Python: lee **un** valor desde el índice 0 (sin espacios antes), ignora lo que sigue y devuelve dónde
 * terminó. RFC 8259 más `NaN`, `Infinity` y `-Infinity`, que Python escribe en `speed` o `eta`.
 *
 * Tipos: objeto → `Map<String, Any?>` (en orden), arreglo → `List<Any?>`, enteros → `Long`, decimales →
 * `Double`, `true`/`false` → `Boolean`, `null` → null. Profundidad máxima [MAX_DEPTH].
 */
object JsonStrict {
    data class Decoded(val value: Any?, val end: Int)

    class JsonException(message: String) : Exception(message)

    private const val MAX_DEPTH = 64

    fun rawDecode(text: String): Decoded {
        val p = Parser(text)
        val value = p.value(0)
        return Decoded(value, p.pos)
    }

    /** El documento completo (solo espacios después del valor). */
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val value = p.value(0)
        p.skipWs()
        if (p.pos != text.length) throw JsonException("texto después del JSON en ${p.pos}")
        return value
    }

    private class Parser(val s: String) {
        var pos = 0

        fun fail(msg: String): Nothing = throw JsonException("$msg en $pos")

        fun skipWs() {
            while (pos < s.length && s[pos] in " \t\r\n") pos++
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("demasiado anidado")
            if (pos >= s.length) fail("fin inesperado")
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                'N' -> literal("NaN", Double.NaN)
                'I' -> literal("Infinity", Double.POSITIVE_INFINITY)
                else -> if (c == '-' || c in '0'..'9') number() else fail("carácter inesperado '$c'")
            }
        }

        fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("se esperaba $word")
            pos += word.length
            return v
        }

        fun obj(depth: Int): Map<String, Any?> {
            pos++
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') fail("se esperaba una clave")
                val key = str()
                skipWs()
                if (pos >= s.length || s[pos] != ':') fail("se esperaba ':'")
                pos++
                skipWs()
                map[key] = value(depth + 1)
                skipWs()
                if (pos >= s.length) fail("objeto sin cerrar")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> fail("se esperaba ',' o '}'")
                }
            }
        }

        fun arr(depth: Int): List<Any?> {
            pos++
            val list = ArrayList<Any?>()
            skipWs()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                skipWs()
                list.add(value(depth + 1))
                skipWs()
                if (pos >= s.length) fail("arreglo sin cerrar")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> fail("se esperaba ',' o ']'")
                }
            }
        }

        fun str(): String {
            pos++
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) fail("texto sin cerrar")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (pos >= s.length) fail("escape incompleto")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("\\u incompleto")
                                val hex = s.substring(pos, pos + 4)
                                if (!hex.all { it in "0123456789abcdefABCDEF" }) fail("\\u inválido")
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> fail("escape inválido \\$e")
                        }
                    }
                    c.code < 0x20 -> fail("carácter de control en un texto")
                    else -> sb.append(c)
                }
            }
        }

        fun number(): Any {
            val start = pos
            if (s[pos] == '-') {
                pos++
                if (s.startsWith("Infinity", pos)) {
                    pos += "Infinity".length
                    return Double.NEGATIVE_INFINITY
                }
            }
            if (pos >= s.length || s[pos] !in '0'..'9') fail("número inválido")
            if (s[pos] == '0') pos++ else while (pos < s.length && s[pos] in '0'..'9') pos++
            var isDouble = false
            if (pos < s.length && s[pos] == '.') {
                isDouble = true
                pos++
                if (pos >= s.length || s[pos] !in '0'..'9') fail("decimal inválido")
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                isDouble = true
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                if (pos >= s.length || s[pos] !in '0'..'9') fail("exponente inválido")
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            val text = s.substring(start, pos)
            return if (isDouble) text.toDouble() else (text.toLongOrNull() ?: text.toDouble())
        }
    }
}

/** Ayudas para leer los mapas de [JsonStrict] sin repetir conversiones. */
fun Map<String, Any?>.num(key: String): Double? = (this[key] as? Number)?.toDouble()?.takeIf { it.isFinite() }

fun Map<String, Any?>.long(key: String): Long? = num(key)?.toLong()

fun Map<String, Any?>.str(key: String): String? = this[key] as? String
