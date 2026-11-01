// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.core.media

import io.github.kasaco223.kdownloader.core.download.JsonStrict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Resumen y opciones con el mismo `-J` real que usan las pruebas de Windows (tests/fixtures/info_bbb_min.json).
 * Los números son los de `media.estimate_heights()` en Windows y los de `tiles.py` del diseño.
 */
class MediaTest {
    private val repo = File(System.getProperty("kd.repoRoot") ?: "../..")

    @Suppress("UNCHECKED_CAST")
    private val bbb: Map<String, Any?> by lazy {
        JsonStrict.parse(File(repo, "tests/fixtures/info_bbb_min.json").readText()) as Map<String, Any?>
    }

    private fun format(id: String, vcodec: String, height: Int?, width: Int?, acodec: String = "none", filesize: Long? = null) =
        mapOf("format_id" to id, "vcodec" to vcodec, "acodec" to acodec, "height" to height?.toLong(), "width" to width?.toLong(), "filesize" to filesize)

    private fun info(vararg formats: Map<String, Any?>, url: String = "https://www.youtube.com/watch?v=x", duration: Long = 60) =
        mapOf("id" to "x", "title" to "t", "webpage_url" to url, "duration" to duration, "formats" to formats.toList())

    @Test
    fun summarizeBigBuckBunny() {
        val s = Media.summarize(bbb)
        assertEquals("aqz-KE-bpKQ", s.id)
        assertEquals("Big Buck Bunny 60fps 4K - Official Blender Foundation Short Film", s.title)
        assertEquals("Blender", s.uploader)
        assertEquals(634.0, s.duration!!, 0.0)
        assertEquals("https://i.ytimg.com/vi_webp/aqz-KE-bpKQ/maxresdefault.webp", s.thumbnail)
        assertEquals("https://www.youtube.com/watch?v=aqz-KE-bpKQ", s.url)
        assertFalse(s.isPlaylist)
        assertEquals(
            listOf(HeightInfo(2160, 60, false), HeightInfo(1440, 60, false), HeightInfo(1080, 60, false), HeightInfo(720, 60, false), HeightInfo(360, 30, false)),
            s.heights,
        )
        assertEquals(2160, s.maxHeight)
        assertFalse(Media.isDegraded(bbb))
        assertEquals("YouTube", Media.siteName(bbb, "https://youtu.be/aqz-KE-bpKQ"))
    }

    @Test
    fun mainTilesMatchTheDesign() {
        val s = Media.summarize(bbb)
        val tiles = Tiles.main(bbb, s).associateBy { it.key }
        tiles.getValue("recommended").let {
            assertEquals(267_891_149L, it.size) // 299 + 140
            assertEquals(1080, it.height)
            assertEquals(60, it.fps)
            assertEquals("h264", it.vcodec)
            assertEquals("aac", it.acodec)
            assertTrue(it.approx)
        }
        tiles.getValue("max").let {
            assertEquals(1_372_471_691L, it.size) // 315 + 251
            assertEquals(2160, it.height)
            assertEquals("vp9", it.vcodec)
            assertEquals("opus", it.acodec)
        }
        assertEquals(1_020_740_628L, Tiles.main(bbb, s, maxCodec = "av1").first { it.key == "max" }.size) // 401 + 251
        assertEquals(19_416_250L, tiles.getValue("mp3").size) // 634 s × 245 kbps
        tiles.getValue("m4a").let {
            assertEquals(10_271_496L, it.size) // 140, exacto
            assertFalse(it.approx)
        }
        assertTrue(tiles.values.all { it.available })
    }

    @Test
    fun moreQualitiesMatchDesktopEstimateHeights() {
        val s = Media.summarize(bbb)
        assertEquals(
            listOf("h2160" to 1_372_471_691L, "h1440" to 543_012_951L, "h1080" to 267_891_149L, "h720" to 105_744_808L, "h360" to 47_398_520L),
            Tiles.heights(bbb, s).map { it.key to it.size },
        )
        assertEquals(listOf("mkv", "mkv", "mp4", "mp4", "mp4"), Tiles.heights(bbb, s).map { it.container })
    }

    @Test
    fun audioOnlyMediaHasNoVideoTiles() {
        val i = info(format("a", "none", null, null, acodec = "opus", filesize = 1000), url = "https://soundcloud.com/x/y")
        val tiles = Tiles.main(i, Media.summarize(i)).associateBy { it.key }
        assertFalse(tiles.getValue("recommended").available)
        assertFalse(tiles.getValue("max").available)
        assertTrue(tiles.getValue("mp3").available)
        assertEquals(1000L, tiles.getValue("m4a").size)
        assertTrue(tiles.getValue("m4a").approx) // Opus → AAC
    }

    @Test
    fun directFileWithoutCodecsOffersEverything() {
        val i = mapOf("id" to "prueba", "title" to "prueba", "webpage_url" to "https://example.com/v.mp4", "formats" to listOf(mapOf("format_id" to "0", "url" to "https://example.com/v.mp4", "ext" to "mp4")))
        val tiles = Tiles.main(i, Media.summarize(i))
        assertTrue(tiles.all { it.available })
        assertNull(tiles.first { it.key == "recommended" }.size)
    }

    @Test
    fun verticalVideoUsesTheSmallerSide() {
        val i = info(format("v", "avc1.640028", 1920, 1080, filesize = 5000), format("a", "none", null, null, acodec = "mp4a.40.2", filesize = 100))
        val s = Media.summarize(i)
        assertEquals(1080, s.maxHeight)
        assertEquals(5100L, Tiles.main(i, s).first { it.key == "recommended" }.size)
    }

    @Test
    fun degradedOnlyForYoutubeWithFormat18OrLow() {
        assertTrue(Media.isDegraded(info(format("18", "avc1", 360, 640, acodec = "mp4a"))))
        assertTrue(Media.isDegraded(info(format("18", "avc1", 360, 640, acodec = "mp4a"), format("134", "avc1", 360, 640))))
        assertFalse(Media.isDegraded(info(format("18", "avc1", 360, 640, acodec = "mp4a"), format("137", "avc1", 1080, 1920))))
        assertFalse(Media.isDegraded(info(format("18", "avc1", 360, 640, acodec = "mp4a"), url = "https://vimeo.com/1")))
        assertFalse(Media.isDegraded(info(format("134", "avc1", 360, 640)))) // original de baja calidad, sin el 18
    }

    @Test
    fun summarizePlaylist() {
        val list = mapOf(
            "_type" to "playlist", "id" to "PL1", "title" to "Mi lista", "uploader" to "Canal",
            "webpage_url" to "https://www.youtube.com/playlist?list=PL1",
            "entries" to listOf(
                mapOf("id" to "a", "title" to "Uno", "url" to "https://www.youtube.com/watch?v=a", "duration" to 60L),
                mapOf("id" to "b", "url" to "https://www.youtube.com/watch?v=b", "duration" to null),
                "basura",
            ),
        )
        val s = Media.summarize(list)
        assertTrue(s.isPlaylist)
        assertEquals(2, s.entries.size)
        assertEquals("b", s.entries[1].title) // sin título: el id
        assertEquals(60.0, s.totalDuration, 0.0)
        assertTrue(Tiles.main(list, s).all { it.available && it.size == null })
    }

    @Test
    fun thumbnailIsOnlyHttps() {
        val s = Media.summarize(
            mapOf(
                "thumbnail" to "http://insecure/x.jpg",
                "thumbnails" to listOf(
                    mapOf("url" to "https://a/small.jpg", "width" to 120L),
                    mapOf("url" to "https://a/big.jpg", "width" to 1280L),
                    mapOf("url" to "http://a/huge.jpg", "width" to 4000L),
                ),
            ),
        )
        assertEquals("https://a/big.jpg", s.thumbnail)
        assertNull(Media.summarize(mapOf("thumbnail" to "file:///x")).thumbnail)
    }
}
