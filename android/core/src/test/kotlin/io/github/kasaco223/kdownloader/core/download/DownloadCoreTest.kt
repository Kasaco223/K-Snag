// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * La descarga en :core contra los mismos datos que la app de Windows: presets (engine/presets.py), protocolo
 * KDL sobre transcripciones reales de yt-dlp (tests/fixtures/ytdlp) y los errores reales de tests/test_errors.py.
 */
class DownloadCoreTest {
    private val repo = File(System.getProperty("kd.repoRoot") ?: "../..")

    private fun fixture(name: String) = File(repo, "tests/fixtures/ytdlp/$name").readLines(Charsets.UTF_8)

    // ------------------------------------------------------------------ JsonStrict
    @Test
    fun jsonStrictReadsOneValueAndStops() {
        assertEquals(JsonStrict.Decoded(mapOf("a" to 1L), 7), JsonStrict.rawDecode("{\"a\":1}|resto"))
        assertEquals(listOf(1L, 2.5, true, null, "x\"y"), JsonStrict.parse("[1, 2.5, true, null, \"x\\\"y\"]"))
        assertTrue((JsonStrict.parse("{\"speed\": NaN}") as Map<*, *>)["speed"].let { it is Double && it.isNaN() })
        assertEquals(Double.NEGATIVE_INFINITY, JsonStrict.parse("-Infinity"))
        assertEquals("ñ", JsonStrict.parse("\"\\u00f1\""))
        assertEquals(JsonStrict.Decoded(0L, 1), JsonStrict.rawDecode("01x")) // como raw_decode de Python
        for (bad in listOf(" {}", "{", "[1,]", "{\"a\" 1}", "\"a\nb\"", "tru")) {
            val failed = runCatching { JsonStrict.rawDecode(bad) }.isFailure
            assertTrue("debería fallar: $bad", failed)
        }
    }

    // ------------------------------------------------------------------ Kdl (vectores del diseño §13)
    @Test
    fun kdlLinesFromTheDesign() {
        assertNull(Kdl.parseLine("[download] 10% of 5MiB"))
        assertNull(Kdl.parseLine("KDL|pp| {\"status\":\"started\"}"))
        assertNull(Kdl.parseLine("KDL|meta|[1]"))
        assertNull(Kdl.parseLine("KDL|META|{}"))
        assertNull(Kdl.parseLine("KDL|nuevo|{}"))
        assertEquals(KdlEvent.Pp(mapOf("status" to "started")), Kdl.parseLine("xx\rKDL|pp|{\"status\":\"started\"}"))
        assertNull(Kdl.parseLine("KDL|pp|{\"status\":\"finished\"}\rxx")) // vale lo que sigue al último \r
        assertEquals(KdlEvent.File("/data/x.mp4"), Kdl.parseLine("KDL|file|\"/data/x.mp4\"extra"))
        assertNull(Kdl.parseLine("KDL|dl|{\"status\":\"downloading\"}|garbage")) // info dañada → toda la línea
        val dl = Kdl.parseLine("KDL|dl|{\"status\":\"downloading\",\"speed\":NaN}|[1]") as KdlEvent.Dl
        assertTrue(dl.info.isEmpty())
        assertTrue((Kdl.parseLine("KDL|dl|{\"a\":1}|null") as KdlEvent.Dl).info.isEmpty())
        val meta = Kdl.parseLine("KDL|meta|{\"id\":\"aqz-KE-bpKQ\",\"format_id\":\"299+140\",\"filesize_approx\":267891149}") as KdlEvent.Meta
        assertEquals(267891149L, meta.data.long("filesize_approx"))
        // Una URL de la persona que trae "KDL|file|" en medio no inyecta eventos.
        assertNull(Kdl.parseLine("[generic] https://x.com/?a=KDL|file|\"/etc/passwd\""))
    }

    @Test
    fun everyKdlLineOfTheRealTranscriptsParses() {
        for (name in listOf("max_sections", "mp3_sections", "rec_full_unicode", "rec_sections", "uni_full_360")) {
            for (stream in listOf("stdout", "stderr")) {
                for (line in fixture("$name.$stream.txt")) {
                    val ev = Kdl.parseLine(line)
                    if ("KDL|" !in line) {
                        assertNull(line, ev)
                    } else {
                        assertTrue(line, ev is KdlEvent.Dl || ev is KdlEvent.Pp || ev is KdlEvent.File)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ ProgressModel (jobreplay)
    private fun replay(name: String, option: String): Pair<ProgressModel, List<Triple<String, String, Double?>>> {
        val model = ProgressModel(option, isYoutube = true)
        val trace = mutableListOf<Triple<String, String, Double?>>()
        val pps = mutableListOf<String>()
        for (line in fixture("$name.stdout.txt") + fixture("$name.stderr.txt")) {
            val ev = Kdl.parseLine(line) ?: continue
            model.onEvent(ev, now = 0)
            if (ev is KdlEvent.Dl) trace += Triple(ev.info.str("format_id")!!, model.stepKind!!, model.progress)
        }
        return model to trace
    }

    @Test
    fun replayUnicodeFullDownload() {
        val (m, trace) = replay("uni_full_360", "h360")
        assertEquals(listOf("134", "140"), m.formatsSeen)
        assertEquals(2, m.stepTotal)
        assertEquals(0.000121, trace.first().third!!, 1e-6)
        assertEquals(0.9, trace.last { it.second == "video" }.third!!, 1e-9)
        assertEquals(0.900042, trace.first { it.second == "audio" }.third!!, 1e-6)
        assertEquals(0.99, m.progress!!, 1e-9)
        assertEquals("processing", m.state)
        assertEquals("C:\\KD\\out\\uni_full_360\\Soy el Ñandú ｜ La Granja de Zenón 6 [IKLKB49EqS8].mp4", m.filePath)
        // el progreso nunca retrocede
        val p = trace.mapNotNull { it.third }
        assertEquals(p.sorted(), p)
    }

    @Test
    fun replaySectionTranscripts() {
        for ((name, option, kind, ext) in listOf(
            listOf("rec_sections", "recommended", "av", ".mp4"),
            listOf("max_sections", "max", "av", ".mkv"),
            listOf("mp3_sections", "mp3", "audio", ".mp3"),
        )) {
            val (m, trace) = replay(name, option)
            assertEquals(name, 1, m.stepTotal)
            assertEquals(name, kind, trace.last().second)
            assertEquals(name, 0.99, m.progress!!, 1e-9)
            assertTrue(name, m.filePath!!.endsWith(ext))
        }
    }

    // ------------------------------------------------------------------ Presets (engine/presets.py)
    @Test
    fun presetsMatchDesktopByteForByte() {
        assertEquals(
            listOf("-f", "bv+ba", "-S", "lang,vcodec:h264,res:1080,acodec:aac,quality,fps,hdr:12", "--merge-output-format", "mp4", "--remux-video", "mp4"),
            Presets.buildArgs(Presets.Choice("recommended"), isYoutube = true),
        )
        assertEquals(
            listOf("-f", "bv*+ba/b", "-S", "lang,res,fps,hdr:sdr,vcodec:vp9,acodec:opus", "--merge-output-format", "mkv"),
            Presets.buildArgs(Presets.Choice("max"), isYoutube = false),
        )
        assertEquals(listOf("-S", "lang,res,fps,hdr:sdr,acodec:opus"), Presets.buildArgs(Presets.Choice("max"), true, "av1").subList(2, 4))
        assertEquals(listOf("-f", "ba/b", "-x", "--audio-format", "mp3", "--audio-quality", "0"), Presets.buildArgs(Presets.Choice("mp3"), true))
        assertEquals(listOf("-f", "ba[ext=m4a]/ba[acodec^=mp4a]/ba/b", "-x", "--audio-format", "m4a"), Presets.buildArgs(Presets.Choice("m4a"), true))
        assertEquals("lang,res:720,vcodec:h264,acodec:aac,fps,hdr:sdr", Presets.buildArgs(Presets.Choice("height", 720), true)[3])
        assertEquals("mkv", Presets.buildArgs(Presets.Choice("height", 2160), true).last())
        assertEquals(listOf("-f", "bv*+ba/b", "--merge-output-format", "mp4/mkv"), Presets.fallbackArgs(Presets.Choice("recommended")))
        assertEquals(listOf("-o", "%(title).120B [%(id)s].%(ext)s", "--restrict-filenames"), Presets.outputTemplate("simple"))
        for (opt in listOf("recommended", "max", "mp3", "m4a", "h144", "h1080", "h4320")) {
            val c = Presets.optionToChoice(opt)!!
            for (yt in listOf(true, false)) {
                val joined = Presets.buildArgs(c, yt).joinToString(" ")
                assertFalse(opt, "height<" in joined || "[height" in joined || " -- " in " $joined ")
            }
        }
        assertNull(Presets.optionToChoice("best"))
        assertNull(Presets.optionToChoice("h"))
        assertEquals("mkv", Presets.containerFor("h1440", 1440))
        assertEquals("mp4", Presets.containerFor("h1080", 1080))
        assertEquals("mp3", Presets.containerFor("mp3"))
    }

    @Test
    fun downloadCommandEndsWithTheUrlAfterDoubleDash() {
        val paths = Args.Paths("/c/yt-dlp", "/nl/libqjs.so", "/nl/libffmpeg.so", "/j/home", "/j/tmp")
        val args = Args.download(paths, "https://youtu.be/x", Presets.Choice("recommended"), isYoutube = true,
            extra = listOf("--limit-rate", "50K"), playerClient = "visionos")
        assertEquals(listOf("--", "https://youtu.be/x"), args.takeLast(2))
        assertTrue(args.indexOf("--no-quiet") > 0)
        assertEquals("!is_live", args[args.indexOf("--match-filters") + 1])
        assertEquals("quickjs:/nl/libqjs.so", args[args.indexOf("--js-runtimes") + 1])
        assertEquals("youtube:player_client=visionos", args[args.indexOf("--extractor-args") + 1])
        assertTrue(args.indexOf("--limit-rate") > args.indexOf("-S")) // extras después del preset
        val other = Args.download(paths, "https://vimeo.com/1", Presets.Choice("mp3"), isYoutube = false, playerClient = "visionos")
        assertFalse("--extractor-args" in other) // la pista de cliente es solo para YouTube
    }

    // ------------------------------------------------------------------ ErrorCatalog (tests/test_errors.py)
    @Test
    fun classifiesTheSameRealLinesAsDesktop() {
        val catalog = ErrorCatalog.parse(File(repo, "src/kdownloader/engine/errors_catalog.json").readText())
        @Suppress("UNCHECKED_CAST")
        val vectors = JsonStrict.parse(javaClass.getResource("/desktop_error_vectors.json")!!.readText()) as Map<String, Any?>
        val samples = vectors["samples"] as List<*>
        assertTrue(samples.size > 50)
        for (s in samples) {
            @Suppress("UNCHECKED_CAST")
            val v = s as Map<String, Any?>
            val lines = (v["lines"] as List<*>).map { it as String }
            val got = catalog.classify(lines)
            assertEquals(lines.toString(), v["code"], got.code)
            assertEquals(lines.toString(), v["action"], got.action)
            assertEquals(lines.toString(), v["auto"], got.auto)
            v.str("message_es")?.let { assertEquals(it, got.message("es")) }
        }
    }

    // ------------------------------------------------------------------ ExitMapper (diseño §9) y etiquetas
    @Test
    fun exitMappingFirstMatchWins() {
        val none = emptyList<String>()
        assertEquals(ExitMapper.Outcome.Canceled, ExitMapper.map(true, ExitMapper.Stop.DISK_FULL, 0, "/f", null, none))
        assertEquals(ExitMapper.Outcome.Failed("disk_full"), ExitMapper.map(false, ExitMapper.Stop.DISK_FULL, 1, null, null, none))
        assertEquals(ExitMapper.Outcome.AlreadyDownloaded(), ExitMapper.map(false, ExitMapper.Stop.DUPLICATE, 1, null, null, none))
        assertEquals(ExitMapper.Outcome.Failed("stalled"), ExitMapper.map(false, ExitMapper.Stop.STALLED, 1, null, null, none))
        assertEquals(ExitMapper.Outcome.Ok("/f"), ExitMapper.map(false, null, 0, "/f", null, none))
        assertEquals(ExitMapper.Outcome.Ok("/g", alreadyDownloaded = true), ExitMapper.map(false, null, 0, null, "/g", none))
        assertEquals(
            ExitMapper.Outcome.Failed("live_unsupported"),
            ExitMapper.map(false, null, 0, null, null, listOf("[download] Directo does not pass filter (!is_live), skipping ..")),
        )
        assertEquals(ExitMapper.Outcome.Failed("ffmpeg_missing"), ExitMapper.map(false, null, 0, null, null, listOf("ERROR: ffmpeg is not installed")))
        assertEquals(ExitMapper.Outcome.Failed("unknown"), ExitMapper.map(false, null, 0, null, null, none))
        assertEquals(ExitMapper.Outcome.Killed, ExitMapper.map(false, null, 137, null, null, none))
        assertEquals(ExitMapper.Outcome.Failed(null), ExitMapper.map(false, null, 1, null, null, none))
        assertEquals("/h/a [x].mp4", ExitMapper.alreadyDownloadedPath(listOf("[download] /h/a [x].mp4 has already been downloaded")))
    }

    @Test
    fun stageLabels() {
        assertEquals(StageLabeler.Stage("jobs_step_video", 1, 2), StageLabeler.label("downloading", "video", 1, 2, null, "recommended"))
        assertEquals(StageLabeler.Stage("jobs_step_av"), StageLabeler.label("downloading", "av", 1, 1, null, "recommended"))
        assertEquals("jobs_pp_merger", StageLabeler.label("processing", null, 2, 2, "Merger", "recommended").key)
        assertEquals("jobs_pp_extract_audio_m4a", StageLabeler.label("processing", null, 1, 1, "ExtractAudio", "m4a").key)
        assertEquals("jobs_pp_publish", StageLabeler.label("processing", null, 1, 1, "Publish", "mp3").key)
        assertEquals("jobs_pp_other", StageLabeler.label("processing", null, 1, 1, "Nuevo", "mp3").key)
    }
}
