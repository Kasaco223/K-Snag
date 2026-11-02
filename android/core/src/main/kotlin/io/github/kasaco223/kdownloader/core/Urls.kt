// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.core

import java.net.IDN
import java.text.Normalizer
import java.util.Locale

/**
 * Enlaces que la persona comparte, pega o abre con `kdownloader://`. Porta las reglas de la app de Windows:
 *
 * - [extract], [coerce], [normalize], [hasListParam], [isPlaylistPage] y [guessVideoId] siguen a
 *   `src/kdownloader/ui/js/actions.js` (`extractUrls`, `coerceUrl`, `normalizeUrl`…), que usa el parser `URL`
 *   del navegador (estándar WHATWG). Aquí se reimplementa la parte de ese estándar que importa para http(s).
 * - [validate] es `media.validate_url`, y [host] e [isYoutube] son `engine/ytdlp.is_youtube`; ambos usan
 *   `urllib.parse` de Python, que interpreta algunos enlaces raros distinto que el navegador.
 *
 * Nunca usa `java.net.URI`: rechaza caracteres que aparecen en enlaces reales (`|`, `{`, `^`) y no coincide con
 * ninguno de los dos parsers de escritorio.
 *
 * Diferencias a propósito con Windows (diseño de Android): [extract] y [normalize] solo aceptan http(s) con un
 * host que tenga un punto y de hasta [MAX_LENGTH] caracteres, lo mismo que el motor aceptaría después. Los
 * hosts IPv6 (`[::1]`) se rechazan: el navegador los escribe sin puntos, así que Windows también los descarta.
 *
 * Los hosts internacionales se convierten con `java.net.IDN` (IDNA2003) y el navegador usa UTS #46 (IDNA2008).
 * Donde los dos darían otro dominio, o el navegador rechazaría el enlace, aquí se rechaza: hosts con ß, ẞ, ς,
 * ZWNJ o ZWJ (tal cual o escritos con %XX) y etiquetas `xn--` cuyo Punycode no es válido. Así nunca se muestra
 * ni se descarga de un dominio distinto del que se compartió.
 */
object Urls {
    /** Largo máximo de un enlace, igual que `media.validate_url`. */
    const val MAX_LENGTH: Int = 4096

    private val YOUTUBE_HOSTS = listOf("youtube.com", "youtu.be", "youtube-nocookie.com")
    private val YOUTUBE_ALIASES = setOf("m.youtube.com", "music.youtube.com", "youtube.com")
    private const val CANONICAL_YOUTUBE = "www.youtube.com"

    // \s de JavaScript (WhiteSpace + LineTerminator). El \s de Java solo cubre ASCII, y los textos compartidos
    // traen a menudo espacios Unicode (NBSP, U+3000…) entre el mensaje y el enlace.
    private const val JS_SPACE =
        "\t\n\u000B\u000C\r \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF"

    /** `URL_RE` de actions.js: `/https?:\/\/[^\s<>"'`]+/gi`. */
    private val URL_IN_TEXT = Regex("https?://[^$JS_SPACE<>\"'`]+", RegexOption.IGNORE_CASE)

    /**
     * Puntuación que cierra una frase, no el enlace (`/[),.;:!?\]}>]+$/` de actions.js). Se quita con trimEnd, en
     * tiempo lineal: esa regex con replace tarda O(n²) ante una tira larga de `)` seguida de otra letra, y
     * cualquier app puede compartir un texto así.
     */
    private const val TRAILING_PUNCTUATION = "),.;:!?]}>"

    /**
     * Dominio escrito a mano sin esquema, como `youtu.be/xyz`: `/^[\w-]+(\.[\w-]+)+\/\S*$/`. Cuantificadores
     * posesivos (mismo resultado, porque las clases no incluyen `.` ni `/`): con `(…)+` normal, java.util.regex
     * recurre una vez por etiqueta y un texto como `a.a.a.…` (unas 2000 etiquetas) termina en StackOverflowError.
     */
    private val BARE_DOMAIN = Regex("[A-Za-z0-9_-]++(?:\\.[A-Za-z0-9_-]++)++/[^$JS_SPACE]*+")

    private val VIDEO_PATH = Regex("^/(shorts|live|embed)/([A-Za-z0-9_-]{6,})")

    /**
     * Todos los enlaces http(s) del texto, en orden, sin repetidos y normalizados con [normalize].
     * Quita la puntuación pegada al final (`(https://youtu.be/x).` → `https://youtu.be/x`).
     */
    fun extract(text: CharSequence?): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        val found = LinkedHashSet<String>()
        for (match in URL_IN_TEXT.findAll(text)) {
            val candidate = match.value.trimEnd { it in TRAILING_PUNCTUATION }
            normalize(candidate)?.let(found::add)
        }
        return found.toList()
    }

    /**
     * Como [extract], pero si el texto no trae ningún enlace con esquema acepta un dominio escrito a mano
     * (`youtu.be/xyz` → `https://youtu.be/xyz`).
     */
    fun coerce(text: CharSequence?): List<String> {
        val urls = extract(text)
        if (urls.isNotEmpty() || text == null) return urls
        val trimmed = text.toString().trim { it.isJsSpace() }
        return if (BARE_DOMAIN.matches(trimmed)) extract("https://$trimmed") else emptyList()
    }

    /**
     * El enlace en forma canónica (esquema y host en minúsculas, sin puerto por defecto, ruta resuelta y con
     * los caracteres especiales codificados; `m.`/`music.`/`youtube.com` pasan a `www.youtube.com`), o null si
     * no es un enlace http(s) válido con un punto en el host y de hasta [MAX_LENGTH] caracteres.
     */
    fun normalize(url: String): String? {
        val parsed = WebUrl.parse(url) ?: return null
        if ('.' !in parsed.host) return null
        val host = if (parsed.host in YOUTUBE_ALIASES) CANONICAL_YOUTUBE else parsed.host
        return parsed.copy(host = host).serialize().takeIf { it.length <= MAX_LENGTH }
    }

    /**
     * `media.validate_url`: la primera palabra del texto si es un enlace http(s) con host y de hasta
     * [MAX_LENGTH] caracteres, sin cambios; si no, null. No exige el punto en el host (`http://localhost/` vale).
     */
    fun validate(url: String?): String? {
        if (url == null) return null
        val stripped = url.trim { it.isPythonSpace() }
        if (stripped.isEmpty()) return null
        val firstSpace = stripped.indexOfFirst { it.isPythonSpace() }
        val token = if (firstSpace < 0) stripped else stripped.substring(0, firstSpace)
        if (token.codePointCount(0, token.length) > MAX_LENGTH) return null
        val split = PythonSplit.of(token) ?: return null
        if (split.scheme != "http" && split.scheme != "https") return null
        return token.takeIf { !split.hostname.isNullOrEmpty() }
    }

    /**
     * El host del enlace como lo entiende `urllib.parse` (`urlparse(url).hostname`): lo que va después de la
     * última `@`, sin el puerto y en minúsculas. Null si no tiene (`youtube.com/x`, sin `//`, no tiene host).
     */
    fun host(url: String?): String? = url?.let { PythonSplit.of(it)?.hostname }

    /** `engine/ytdlp.is_youtube`: el host es youtube.com, youtu.be o youtube-nocookie.com, o un subdominio. */
    fun isYoutube(url: String?): Boolean {
        val host = host(url) ?: return false
        return YOUTUBE_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /** El enlace trae `list=` y no es la página de la lista (`/playlist`): un video que es parte de una lista. */
    fun hasListParam(url: String?): Boolean {
        val parsed = url?.let(WebUrl::parse) ?: return false
        return parsed.queryParams().any { it.first == "list" } && !parsed.path.startsWith("/playlist")
    }

    /** La página de una lista de YouTube (`/playlist?list=…`). */
    fun isPlaylistPage(url: String?): Boolean {
        val parsed = url?.let(WebUrl::parse) ?: return false
        return parsed.path.startsWith("/playlist") && parsed.queryParams().any { it.first == "list" }
    }

    /** El id del video de YouTube que se adivina del enlace (`youtu.be/<id>`, `?v=<id>`, `/shorts/<id>`…), o null. */
    fun guessVideoId(url: String?): String? {
        val parsed = url?.let(WebUrl::parse) ?: return null
        if (parsed.host == "youtu.be") return parsed.path.substring(1).substringBefore('/').ifEmpty { null }
        parsed.queryParams().firstOrNull { it.first == "v" }?.second?.takeIf { it.isNotEmpty() }?.let { return it }
        return VIDEO_PATH.find(parsed.path)?.groupValues?.get(2)
    }

    private fun Char.isJsSpace(): Boolean = this in "\t\n\u000B\u000C\r \u00A0\u1680\u2028\u2029\u202F\u205F\u3000\uFEFF" ||
        this in '\u2000'..'\u200A'

    /** `str.isspace()` de Python (lo que usan `strip()` y `split()` en `validate_url`). */
    private fun Char.isPythonSpace(): Boolean = this in '\u0009'..'\u000D' || this in '\u001C'..' ' ||
        this == '\u0085' || this == '\u00A0' || this == '\u1680' || this in '\u2000'..'\u200A' ||
        this == '\u2028' || this == '\u2029' || this == '\u202F' || this == '\u205F' || this == '\u3000'
}

/** Los campos de `urllib.parse.urlsplit` que usa la app de Windows (Python 3.13). */
private class PythonSplit(val scheme: String, val hostname: String?) {
    companion object {
        private const val SCHEME_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+-."

        /** Null donde Python lanzaría ValueError (corchetes de IPv6 sin cerrar, caracteres que se normalizan raro). */
        fun of(url: String): PythonSplit? {
            var rest = url.trimStart { it <= ' ' }.filterNot { it == '\t' || it == '\r' || it == '\n' }
            var scheme = ""
            val colon = rest.indexOf(':')
            if (colon > 0 && rest[0].isAsciiLetter() && rest.substring(0, colon).all { it in SCHEME_CHARS }) {
                scheme = rest.substring(0, colon).lowercase(Locale.ROOT)
                rest = rest.substring(colon + 1)
            }
            if (!rest.startsWith("//")) return PythonSplit(scheme, null)
            var end = rest.length
            for (delimiter in "/?#") {
                val at = rest.indexOf(delimiter, 2)
                if (at >= 0) end = minOf(end, at)
            }
            val netloc = rest.substring(2, end)
            if (('[' in netloc) != (']' in netloc)) return null
            if (!netlocNormalizesCleanly(netloc)) return null
            val hostinfo = netloc.substringAfterLast('@')
            val hostname = if ('[' in hostinfo) {
                hostinfo.substringAfter('[').substringBefore(']')
            } else {
                hostinfo.substringBefore(':')
            }
            if (hostname.isEmpty()) return PythonSplit(scheme, null)
            val zone = hostname.indexOf('%') // la zona de un IPv6 (fe80::1%eth0) no se pasa a minúsculas
            val lower = if (zone < 0) {
                hostname.lowercase(Locale.ROOT)
            } else {
                hostname.substring(0, zone).lowercase(Locale.ROOT) + hostname.substring(zone)
            }
            return PythonSplit(scheme, lower)
        }

        /** `_checknetloc`: Python rechaza un host no ASCII que con NFKC se convierte en `/ ? # @ :`. */
        private fun netlocNormalizesCleanly(netloc: String): Boolean {
            if (netloc.all { it.code < 0x80 }) return true
            val bare = netloc.filterNot { it in "@:#?" }
            val normalized = Normalizer.normalize(bare, Normalizer.Form.NFKC)
            return normalized == bare || normalized.none { it in "/?#@:" }
        }
    }
}

/**
 * Un enlace http(s) analizado como lo hace el parser `URL` del navegador (estándar WHATWG), solo con lo que
 * importa para http y https. [serialize] devuelve lo mismo que `url.href`.
 */
private data class WebUrl(
    val scheme: String,
    val username: String,
    val password: String,
    val host: String,
    val port: Int?,
    val path: String,
    val query: String?,
    val fragment: String?,
) {
    fun serialize(): String = buildString {
        append(scheme).append("://")
        if (username.isNotEmpty() || password.isNotEmpty()) {
            append(username)
            if (password.isNotEmpty()) append(':').append(password)
            append('@')
        }
        append(host)
        if (port != null) append(':').append(port)
        append(path)
        if (query != null) append('?').append(query)
        if (fragment != null) append('#').append(fragment)
    }

    /** `url.searchParams`: pares nombre/valor ya decodificados (application/x-www-form-urlencoded). */
    fun queryParams(): List<Pair<String, String>> {
        if (query.isNullOrEmpty()) return emptyList()
        return query.split('&').filter { it.isNotEmpty() }.map { pair ->
            val eq = pair.indexOf('=')
            val name = if (eq < 0) pair else pair.substring(0, eq)
            val value = if (eq < 0) "" else pair.substring(eq + 1)
            Percent.decodeLenient(name.replace('+', ' ')) to Percent.decodeLenient(value.replace('+', ' '))
        }
    }

    companion object {
        private const val FORBIDDEN_IN_HOST = "#%/:<>?@[\\]^|"

        /** Donde IDNA2003 (java.net.IDN) y el navegador (UTS #46) no coinciden: ß, ẞ, ς, ZWNJ y ZWJ. */
        private const val IDNA_DEVIATIONS = "\u00DF\u1E9E\u03C2\u200C\u200D"

        fun parse(input: String): WebUrl? {
            // Quita los controles C0 y el espacio de los bordes (como el navegador: no cualquier espacio Unicode,
            // por eso la lambda) y los tab/saltos de línea de en medio.
            @Suppress("TrimLambda")
            val s = input.trim { it <= ' ' }.filterNot { it == '\t' || it == '\n' || it == '\r' }
            val colon = s.indexOf(':')
            if (colon <= 0) return null
            val scheme = s.substring(0, colon).lowercase(Locale.ROOT)
            if (scheme != "http" && scheme != "https") return null

            // En http(s) da igual cuántas / o \ siguen a "http:".
            var i = colon + 1
            while (i < s.length && (s[i] == '/' || s[i] == '\\')) i++
            var end = i
            while (end < s.length && s[end] !in "/\\?#") end++
            val authority = s.substring(i, end)

            // Usuario y contraseña: todo lo que va antes de la última @.
            val at = authority.lastIndexOf('@')
            var username = ""
            var password = ""
            if (at >= 0) {
                val userinfo = authority.substring(0, at)
                val sep = userinfo.indexOf(':')
                username = Percent.encode(if (sep < 0) userinfo else userinfo.substring(0, sep), Percent.USERINFO)
                password = if (sep < 0) "" else Percent.encode(userinfo.substring(sep + 1), Percent.USERINFO)
            }
            val hostPort = if (at >= 0) authority.substring(at + 1) else authority
            if (hostPort.startsWith('[')) return null // IPv6: nunca tiene un punto (ver Urls)
            val portSep = hostPort.indexOf(':')
            val host = parseHost(if (portSep < 0) hostPort else hostPort.substring(0, portSep)) ?: return null
            val port = if (portSep < 0) null else parsePort(hostPort.substring(portSep + 1)) ?: return null
            val defaultPort = if (scheme == "http") 80 else 443
            val effectivePort = port?.takeIf { it >= 0 && it != defaultPort }

            val rest = s.substring(end)
            val hash = rest.indexOf('#')
            val beforeHash = if (hash < 0) rest else rest.substring(0, hash)
            val fragment = if (hash < 0) null else Percent.encode(rest.substring(hash + 1), Percent.FRAGMENT)
            val question = beforeHash.indexOf('?')
            val rawPath = if (question < 0) beforeHash else beforeHash.substring(0, question)
            val query = if (question < 0) null else Percent.encode(beforeHash.substring(question + 1), Percent.SPECIAL_QUERY)

            return WebUrl(scheme, username, password, host, effectivePort, parsePath(rawPath), query, fragment)
        }

        /** El puerto, -1 si está vacío ("ejemplo.com:"), o null si no es un número válido (o pasa de 65535). */
        private fun parsePort(raw: String): Int? {
            if (raw.isEmpty()) return -1
            if (!raw.all { it in '0'..'9' }) return null
            val digits = raw.trimStart('0').ifEmpty { "0" }
            if (digits.length > 5) return null
            return digits.toInt().takeIf { it <= 65535 }
        }

        private fun parseHost(raw: String): String? {
            if (raw.isEmpty()) return null
            val decoded = Percent.decodeStrict(raw) ?: return null
            if (decoded.any { it in IDNA_DEVIATIONS }) return null
            val ascii = if (decoded.all { it.code < 0x80 }) {
                decoded
            } else {
                try {
                    IDN.toASCII(decoded, IDN.ALLOW_UNASSIGNED)
                } catch (ignored: IllegalArgumentException) {
                    return null
                }
            }.lowercase(Locale.ROOT)
            if (ascii.isEmpty()) return null
            if (ascii.any { it <= ' ' || it == '\u007F' || it in FORBIDDEN_IN_HOST }) return null
            if (ascii.split('.').any(::isInvalidAceLabel)) return null
            return if (Ipv4.endsInNumber(ascii)) Ipv4.parse(ascii) else ascii
        }

        /**
         * Una etiqueta `xn--` con Punycode inválido, o que no vuelve a dar lo mismo: IDN.toUnicode la devuelve sin
         * cambios. El navegador rechaza esos hosts, e IDN.toASCII deja pasar las etiquetas que ya son ASCII.
         */
        private fun isInvalidAceLabel(label: String): Boolean =
            label.startsWith("xn--") && IDN.toUnicode(label, IDN.ALLOW_UNASSIGNED) == label

        /** Ruta: \ cuenta como /, se resuelven . y .., y se codifica lo que el estándar codifica. */
        private fun parsePath(raw: String): String {
            if (raw.isEmpty()) return "/"
            val segments = raw.substring(1).replace('\\', '/').split('/')
            val out = ArrayList<String>(segments.size)
            segments.forEachIndexed { index, segment ->
                val last = index == segments.lastIndex
                when {
                    isDoubleDot(segment) -> {
                        if (out.isNotEmpty()) out.removeAt(out.lastIndex)
                        if (last) out.add("")
                    }
                    isSingleDot(segment) -> if (last) out.add("")
                    else -> out.add(Percent.encode(segment, Percent.PATH))
                }
            }
            return "/" + out.joinToString("/")
        }

        private fun isSingleDot(segment: String) = segment == "." || segment.equals("%2e", ignoreCase = true)

        private fun isDoubleDot(segment: String) = when (segment.lowercase(Locale.ROOT)) {
            "..", ".%2e", "%2e.", "%2e%2e" -> true
            else -> false
        }
    }
}

/** Hosts que terminan en número: el estándar los interpreta como IPv4 (`0x7f.1` → `127.0.0.1`). */
private object Ipv4 {
    fun endsInNumber(host: String): Boolean {
        val parts = host.split('.').toMutableList()
        if (parts.last().isEmpty()) {
            if (parts.size == 1) return false
            parts.removeAt(parts.lastIndex)
        }
        val last = parts.last()
        if (last.isNotEmpty() && last.all { it in '0'..'9' }) return true
        return parseNumber(last) != null
    }

    /** Null si no es un IPv4 válido (el navegador rechaza el enlace). */
    fun parse(host: String): String? {
        val parts = host.split('.').toMutableList()
        if (parts.last().isEmpty() && parts.size > 1) parts.removeAt(parts.lastIndex)
        if (parts.size > 4) return null
        val numbers = parts.map { parseNumber(it) ?: return null }
        if (numbers.dropLast(1).any { it > 255 }) return null
        val lastLimit = 1L shl (8 * (5 - numbers.size))
        if (numbers.last() >= lastLimit) return null
        var address = numbers.last()
        numbers.dropLast(1).forEachIndexed { index, n -> address += n shl (8 * (3 - index)) }
        return (3 downTo 0).joinToString(".") { ((address shr (8 * it)) and 0xFF).toString() }
    }

    private fun parseNumber(part: String): Long? {
        if (part.isEmpty()) return null
        var digits = part
        var radix = 10
        if (digits.length >= 2 && (digits.startsWith("0x") || digits.startsWith("0X"))) {
            digits = digits.substring(2)
            radix = 16
        } else if (digits.length >= 2 && digits.startsWith('0')) {
            digits = digits.substring(1)
            radix = 8
        }
        // Solo dígitos ASCII de la base (toLongOrNull aceptaría "+1" o dígitos de otros alfabetos).
        if (!digits.all { it.code < 0x80 && Character.digit(it, radix) >= 0 }) return null
        val significant = digits.trimStart('0')
        if (significant.isEmpty()) return 0
        // Un número así no cabe en un IPv4: parse() lo rechaza, igual que el navegador, sin desbordar el Long.
        if (significant.length > 12) return Long.MAX_VALUE
        return significant.toLong(radix)
    }
}

/** Codificación %XX del estándar WHATWG (conjuntos de caracteres por parte del enlace). */
private object Percent {
    // Además de estos, siempre se codifican los controles C0 (< 0x20) y todo lo que pasa de '~' (0x7E).
    const val FRAGMENT = " \"<>`"
    const val SPECIAL_QUERY = " \"#<>'"
    const val PATH = " \"#<>?`{}"
    const val USERINFO = " \"#<>?`{}/:;=@[\\]^|"

    private const val HEX = "0123456789ABCDEF"

    fun encode(text: String, set: String): String {
        if (text.all { it.code in 0x20..0x7E && it !in set }) return text
        val out = StringBuilder(text.length + 16)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.code in 0x20..0x7E && c !in set -> {
                    out.append(c)
                    i++
                }
                c.code < 0x80 -> {
                    appendByte(out, c.code)
                    i++
                }
                else -> {
                    val (codePoint, length) = codePointAt(text, i)
                    utf8(codePoint).forEach { appendByte(out, it.toInt() and 0xFF) }
                    i += length
                }
            }
        }
        return out.toString()
    }

    private fun appendByte(out: StringBuilder, b: Int) {
        out.append('%').append(HEX[b shr 4]).append(HEX[b and 0xF])
    }

    /** El code point en [index] y cuántos char ocupa. Un surrogate suelto vale U+FFFD, como en el navegador. */
    private fun codePointAt(text: String, index: Int): Pair<Int, Int> {
        val c = text[index]
        return when {
            Character.isHighSurrogate(c) && index + 1 < text.length && Character.isLowSurrogate(text[index + 1]) ->
                Character.toCodePoint(c, text[index + 1]) to 2
            Character.isSurrogate(c) -> 0xFFFD to 1
            else -> c.code to 1
        }
    }

    private fun utf8(codePoint: Int): ByteArray = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)

    /** El texto como bytes, con cada %XX válido convertido en su byte. */
    private fun bytes(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%' && i + 2 < text.length && hexValue(text[i + 1]) >= 0 && hexValue(text[i + 2]) >= 0) {
                out.write(hexValue(text[i + 1]) * 16 + hexValue(text[i + 2]))
                i += 3
            } else {
                val (codePoint, length) = codePointAt(text, i)
                val encoded = utf8(codePoint)
                out.write(encoded, 0, encoded.size)
                i += length
            }
        }
        return out.toByteArray()
    }

    /** %XX → bytes → UTF-8; los %XX inválidos quedan tal cual y los bytes inválidos pasan a U+FFFD. */
    fun decodeLenient(text: String): String = if ('%' !in text) text else String(bytes(text), Charsets.UTF_8)

    /** Como [decodeLenient], pero null si los bytes no son UTF-8 válido (un host así no se puede usar). */
    fun decodeStrict(text: String): String? {
        if ('%' !in text) return text
        val decoder = Charsets.UTF_8.newDecoder()
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes(text))).toString()
        } catch (ignored: java.nio.charset.CharacterCodingException) {
            null
        }
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
