// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.core.engine

import io.github.kasaco223.kdownloader.core.download.JsonStrict
import io.github.kasaco223.kdownloader.core.download.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El actualizador de yt-dlp y los reintentos automáticos, sin red. */
class UpdateParsingTest {
    private val sha = "e70a37d2" + "0".repeat(56)

    @Test
    fun latestTagComesFromTheRedirect() {
        assertEquals("2026.10.29.232701", UpdateParsing.latestTag("https://github.com/yt-dlp/yt-dlp-nightly-builds/releases/tag/2026.10.29.232701"))
        assertEquals("2026.08.19", UpdateParsing.latestTag("https://github.com/yt-dlp/yt-dlp/releases/tag/2026.08.19"))
        assertNull(UpdateParsing.latestTag("https://github.com/yt-dlp/yt-dlp/releases/tag/v1.0"))
        assertNull(UpdateParsing.latestTag("https://github.com/login?return_to=/releases/tag/2026.08.19"))
        assertNull(UpdateParsing.latestTag(null))
    }

    @Test
    fun sumsFindOnlyTheExactFile() {
        val sums = """
            ${"a".repeat(64)}  yt-dlp.exe
            $sha  yt-dlp
            ${"b".repeat(64)}  yt-dlp_linux
        """.trimIndent()
        assertEquals(sha, UpdateParsing.sumFor(sums))
        assertEquals(sha, UpdateParsing.sumFor("${sha.uppercase()} *yt-dlp"))
        assertNull(UpdateParsing.sumFor("abc  yt-dlp"))
        assertNull(UpdateParsing.sumFor("${"a".repeat(64)}  yt-dlp_macos"))
    }

    @Test
    fun locksMatchFromTheStartLikePython() {
        val id = UpdateParsing.identifier("3.12.11", "aarch64", true)
        assertEquals("zip Python 3.12.11 (CPython aarch64 64bit) - Linux", id)
        val spec = """
            # Comentario
            lock 2022.08.18.36 .+ Python 3\.6
            lockV2 yt-dlp/yt-dlp 2023.11.16 (?!win_x86_exe).+ Python 3\.7
            lockV2 yt-dlp/yt-dlp-nightly-builds 2026.01.01.000000 zip Python 3\.12\.11
            lockV2 yt-dlp/yt-dlp-nightly-builds 2026.02.02.000000 Python
        """.trimIndent()
        assertEquals("2026.01.01.000000", UpdateParsing.lockedTarget(spec, "yt-dlp/yt-dlp-nightly-builds", id))
        assertNull(UpdateParsing.lockedTarget(spec, "yt-dlp/yt-dlp", id)) // Python 3.12, no 3.6/3.7
        assertEquals("2022.08.18.36", UpdateParsing.lockedTarget(spec, "yt-dlp/yt-dlp", "zip Python 3.6.9 (CPython x86_64 64bit) - Linux"))
    }

    @Test
    fun smokeChecks() {
        assertEquals("2026.10.29.232701", UpdateParsing.versionOutput(listOf("", "2026.10.29.232701", "")))
        assertNull(UpdateParsing.versionOutput(listOf("Traceback (most recent call last):")))
        assertTrue(UpdateParsing.jsRuntimeOk(listOf("[debug] JS runtimes: quickjs-2025-04-26")))
        assertFalse(UpdateParsing.jsRuntimeOk(listOf("[debug] JS runtimes: quickjs (unsupported)")))
        assertFalse(UpdateParsing.jsRuntimeOk(listOf("[debug] JS runtimes: none")))
        assertTrue(UpdateParsing.jsRuntimeOk(listOf("[debug] Python 3.12.11"))) // sin la línea: pasa
        assertTrue(UpdateParsing.looksLikeHtml("\n<!DOCTYPE html><html>".toByteArray()))
        assertFalse(UpdateParsing.looksLikeHtml("PK\u0003\u0004".toByteArray()))
    }

    @Test
    fun hintsKeepOnlyValidFields() {
        val h = UpdateParsing.hints(
            JsonStrict.parse(
                """{"schema":1,"min_ytdlp":{"nightly":"2026.09.27.232945","stable":"nada"},
                   "youtube_player_client":"default,-web_safari","notice":{"es":"  Hola  ","en":""}}""",
            ),
        )
        assertEquals(mapOf("nightly" to "2026.09.27.232945"), h.minYtdlp)
        assertEquals("default,-web_safari", h.youtubePlayerClient)
        assertEquals(mapOf("es" to "Hola"), h.notice)
        assertNull(UpdateParsing.hints(JsonStrict.parse("""{"schema":1,"youtube_player_client":"a; rm -rf"}""")).youtubePlayerClient)
        assertEquals(UpdateParsing.Hints(), UpdateParsing.hints(JsonStrict.parse("""{"schema":2}""")))
    }

    @Test
    fun retryPolicyFollowsTheCatalog() {
        assertEquals(RetryPolicy.Decision.Retry("update_retry", updateEngine = true), RetryPolicy.decide("http_403", "update_retry", 1, emptyList()))
        assertEquals(RetryPolicy.Decision.GiveUp, RetryPolicy.decide("http_403", "update_retry", 2, listOf("update_retry")))
        assertEquals(RetryPolicy.Decision.Retry("fallback_best", forceBest = true), RetryPolicy.decide("merge_failed", null, 1, emptyList()))
        val net = (0 until 4).map { n -> RetryPolicy.decide("network", "retry_network", n + 1, List(n) { "retry_network" }) }
        assertEquals(listOf(5_000L, 20_000L, 60_000L), net.take(3).map { (it as RetryPolicy.Decision.Retry).delayMs })
        assertEquals(RetryPolicy.Decision.GiveUp, net[3])
        assertEquals(RetryPolicy.Decision.GiveUp, RetryPolicy.decide("private", null, 1, emptyList()))
        assertEquals(RetryPolicy.Decision.GiveUp, RetryPolicy.decide("network", "retry_network", RetryPolicy.MAX_ATTEMPTS, emptyList()))
    }
}
