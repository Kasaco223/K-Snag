// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlsTest {
    // ------------------------------------------------------------------ mismos resultados que Windows
    @Test
    fun extractMatchesDesktop() = assertAll(DesktopUrlVectors.EXTRACT) { Urls.extract(it) }

    @Test
    fun coerceMatchesDesktop() = assertAll(DesktopUrlVectors.COERCE) { Urls.coerce(it) }

    @Test
    fun hasListParamMatchesDesktop() = assertAll(DesktopUrlVectors.HAS_LIST_PARAM) { Urls.hasListParam(it) }

    @Test
    fun isPlaylistPageMatchesDesktop() = assertAll(DesktopUrlVectors.IS_PLAYLIST_PAGE) { Urls.isPlaylistPage(it) }

    @Test
    fun guessVideoIdMatchesDesktop() = assertAll(DesktopUrlVectors.GUESS_VIDEO_ID) { Urls.guessVideoId(it) }

    @Test
    fun validateMatchesDesktop() = assertAll(DesktopUrlVectors.VALIDATE) { Urls.validate(it) }

    @Test
    fun isYoutubeMatchesDesktop() = assertAll(DesktopUrlVectors.IS_YOUTUBE) { Urls.isYoutube(it) }

    // ------------------------------------------------------------------ reglas propias de Android
    @Test
    fun extractRejectsLinksLongerThanTheEngineAccepts() {
        val base = "https://example.com/"
        val longest = base + "a".repeat(Urls.MAX_LENGTH - base.length)
        assertEquals(listOf(longest), Urls.extract(longest))
        // Windows lo muestra igual, pero media.validate_url (y el motor) lo rechazan: aquí no se ofrece.
        assertEquals(emptyList<String>(), Urls.extract(longest + "a"))
        assertNull(Urls.normalize(longest + "a"))
    }

    @Test
    fun validateCountsCodePointsLikePython() {
        val base = "https://example.com/"
        val emoji = String(Character.toChars(0x1F600)) // 2 char en Kotlin, 1 carácter en Python
        val atLimit = base + emoji.repeat(Urls.MAX_LENGTH - base.length)
        assertEquals(atLimit, Urls.validate(atLimit))
        assertNull(Urls.validate(atLimit + "a"))
    }

    @Test
    fun normalizeAcceptsOnlyHttpWithADottedHost() {
        assertEquals("https://www.youtube.com/watch?v=aqz-KE-bpKQ", Urls.normalize("https://youtube.com/watch?v=aqz-KE-bpKQ"))
        assertNull(Urls.normalize("http://localhost:8080/"))
        assertNull(Urls.normalize("file:///sdcard/video.mp4"))
        assertNull(Urls.normalize("content://media/external/video/1"))
        assertNull(Urls.normalize("javascript:alert(1)"))
        assertNull(Urls.normalize("kdownloader://download?url=x"))
        assertNull(Urls.normalize(""))
    }

    @Test
    fun extractHandlesTypicalShareTexts() {
        // YouTube (app): solo el enlace corto con ?si=.
        assertEquals(listOf("https://youtu.be/aqz-KE-bpKQ?si=4HrlPx7ZqcRk"), Urls.extract("https://youtu.be/aqz-KE-bpKQ?si=4HrlPx7ZqcRk"))
        // TikTok / Instagram: texto, salto de línea y enlace.
        assertEquals(
            listOf("https://vm.tiktok.com/ZMabc123/"),
            Urls.extract("Mira este video de @alguien en TikTok\nhttps://vm.tiktok.com/ZMabc123/ #fyp"),
        )
        // Título entre comillas tipográficas y enlace al final de la oración.
        assertEquals(
            listOf("https://www.youtube.com/watch?v=aqz-KE-bpKQ"),
            Urls.extract("“Big Buck Bunny”: https://m.youtube.com/watch?v=aqz-KE-bpKQ."),
        )
    }

    @Test
    fun hostsWhereIdna2003AndTheBrowserDisagreeAreRejected() {
        // El navegador da xn--fa-hia.de y xn--a-xmb.gr; java.net.IDN daría fass.de y xn--a-zmb.gr (otro dominio).
        assertEquals(emptyList<String>(), Urls.extract("https://fa\u00DF.de/x"))
        assertEquals(emptyList<String>(), Urls.extract("https://fa%C3%9F.de/x"))
        assertEquals(emptyList<String>(), Urls.extract("https://\u03C2a.gr/"))
        // ZWJ y ZWNJ: el navegador rechaza el enlace; java.net.IDN los borraría (google.com, ab.com).
        assertEquals(emptyList<String>(), Urls.extract("https://goo\u200Dgle.com/"))
        assertEquals(emptyList<String>(), Urls.extract("https://a\u200Cb.com/"))
        // Punycode inválido: el navegador lo rechaza.
        assertEquals(emptyList<String>(), Urls.extract("https://xn--a.com/"))
        assertNull(Urls.normalize("https://XN--A.com/"))
        // Los hosts internacionales normales siguen igual que en el navegador.
        assertEquals(listOf("https://xn--bcher-kva.de/"), Urls.extract("https://B\u00DCCHER.de/"))
        assertEquals(listOf("https://xn--bcher-kva.de/"), Urls.extract("https://xn--bcher-kva.de/"))
        assertEquals(listOf("https://xn--bcher-kva.de/"), Urls.extract("https://XN--BCHER-KVA.de"))
        assertEquals(listOf("https://example.xn--55qx5d/"), Urls.extract("https://example.\u516C\u53F8/"))
    }

    // Con la regex anterior, 40 000 «)» tardaban unos 7 s en la JVM (O(n²)): con estos 500 000 serían minutos.
    // Lineal tarda 1-3 s según la carga de la máquina; el timeout atrapa cualquier regresión cuadrática sin
    // depender de lo ocupada que esté la CI.
    @Test(timeout = 10_000)
    fun longPunctuationRunsTakeLinearTime() {
        // Más largo que MAX_LENGTH: ningún enlace, pero la respuesta llega enseguida.
        assertEquals(emptyList<String>(), Urls.extract("https://a.bc/" + ")".repeat(500_000) + "x"))
        assertEquals(listOf("https://a.bc/"), Urls.extract("https://a.bc/" + ")".repeat(500_000)))
        // Dominio escrito a mano con muchísimas etiquetas: sin StackOverflowError (más largo que MAX_LENGTH).
        assertEquals(emptyList<String>(), Urls.coerce("a.".repeat(250_000)))
        assertEquals(emptyList<String>(), Urls.coerce("a.".repeat(100_000) + "a/x"))
        assertEquals(emptyList<String>(), Urls.coerce("a".repeat(500_000) + ".b"))
        assertEquals(listOf("https://a.a.a.a/x"), Urls.coerce("a.a.a.a/x"))
    }

    @Test
    fun extractKeepsTheOrderAndDropsRepeats() {
        val text = "https://b.com/2 https://a.com/1 https://B.com/2 https://a.com:443/1"
        assertEquals(listOf("https://b.com/2", "https://a.com/1"), Urls.extract(text))
    }

    @Test
    fun extractAndCoerceAcceptNullAndEmpty() {
        assertEquals(emptyList<String>(), Urls.extract(null))
        assertEquals(emptyList<String>(), Urls.extract(""))
        assertEquals(emptyList<String>(), Urls.coerce(null))
    }

    @Test
    fun hostFollowsPythonUrlparse() {
        assertEquals("www.youtube.com", Urls.host("https://WWW.YouTube.com:443/watch?v=x"))
        assertEquals("youtube.com", Urls.host("https://evil.example@youtube.com/"))
        assertEquals("::1", Urls.host("https://[::1]:8080/x"))
        assertNull(Urls.host("youtube.com/watch?v=x"))
        assertNull(Urls.host("https://[::1/x")) // Python: ValueError("Invalid IPv6 URL")
        assertNull(Urls.host(null))
    }

    @Test
    fun youtubeHelpersRejectGarbageWithoutThrowing() {
        val garbage = listOf("", " ", "%", "https://", "https://[", "http://\u0000/", "https://a.b/%", "https://a.b/?%zz=1")
        for (input in garbage) {
            Urls.extract(input)
            Urls.coerce(input)
            Urls.validate(input)
            assertFalse(input, Urls.isYoutube(input))
            Urls.hasListParam(input)
            Urls.isPlaylistPage(input)
            Urls.guessVideoId(input)
        }
        assertTrue(Urls.hasListParam("https://a.b/?%zz=1&list=x"))
    }

    private fun <T> assertAll(cases: List<Pair<String, T>>, actual: (String) -> T) {
        val failures = cases.mapNotNull { (input, expected) ->
            val got = actual(input)
            if (got == expected) null else "  ${quote(input)}\n    esperado: $expected\n    obtenido: $got"
        }
        assertTrue("${failures.size} de ${cases.size} casos no coinciden con Windows:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) if (c.code in 0x20..0x7E) append(c) else append("\\u%04X".format(c.code))
        append('"')
    }
}
