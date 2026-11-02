// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EngineCoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------------ ZipModes
    /** Zip como los del motor: un directorio, un archivo y un enlace (modo Unix en los atributos externos). */
    private fun payloadZip(): File {
        val file = tmp.newFile("libtest.zip.so")
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry("usr/lib/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("usr/lib/libz.so.1.3.1"))
            zip.write(ByteArray(1000) { 7 })
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("usr/lib/libz.so"))
            zip.write("libz.so.1.3.1".toByteArray())
            zip.closeEntry()
        }
        // java.util.zip no escribe atributos Unix: se ponen a mano en el directorio central.
        val bytes = file.readBytes()
        var pos = bytes.size - 22
        while (readInt(bytes, pos) != 0x06054b50) pos--
        var cen = readInt(bytes, pos + 16)
        val modes = mapOf("usr/lib/" to 0x41ed, "usr/lib/libz.so.1.3.1" to 0x81a4, "usr/lib/libz.so" to 0xa1ff)
        repeat(3) {
            val nameLen = readShort(bytes, cen + 28)
            val name = String(bytes, cen + 46, nameLen)
            writeInt(bytes, cen + 38, modes.getValue(name) shl 16)
            cen += 46 + nameLen + readShort(bytes, cen + 30) + readShort(bytes, cen + 32)
        }
        file.writeBytes(bytes)
        return file
    }

    private fun readInt(b: ByteArray, p: Int) =
        (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8) or ((b[p + 2].toInt() and 0xff) shl 16) or (b[p + 3].toInt() shl 24)

    private fun readShort(b: ByteArray, p: Int) = (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8)

    private fun writeInt(b: ByteArray, p: Int, v: Int) {
        for (i in 0..3) b[p + i] = (v ushr (8 * i)).toByte()
    }

    @Test
    fun zipModesReadsKindsAndTotals() {
        val listing = ZipModes.read(payloadZip())
        assertEquals(ZipModes.Kind.DIR, listing.entries.getValue("usr/lib/").kind)
        assertEquals(ZipModes.Kind.FILE, listing.entries.getValue("usr/lib/libz.so.1.3.1").kind)
        assertEquals(ZipModes.Kind.SYMLINK, listing.entries.getValue("usr/lib/libz.so").kind)
        assertEquals(1, listing.regularFiles)
        assertEquals(1000L, listing.regularBytes)
    }

    @Test(expected = java.io.IOException::class)
    fun zipModesRejectsNonZip() {
        ZipModes.read(tmp.newFile("x.so").apply { writeText("not a zip") })
    }

    @Test
    fun linkTargetsMustStayInsideTheFolder() {
        assertEquals("libz.so.1.3.1", ZipModes.safeLinkTarget("libz.so.1.3.1".toByteArray()))
        assertNull(ZipModes.safeLinkTarget("../lib/x".toByteArray()))
        assertNull(ZipModes.safeLinkTarget("/system/lib64/libz.so".toByteArray()))
        assertNull(ZipModes.safeLinkTarget("a//b".toByteArray()))
        assertNull(ZipModes.safeLinkTarget("a\nb".toByteArray()))
        assertNull(ZipModes.safeLinkTarget(ByteArray(0)))
    }

    // ------------------------------------------------------------------ YtdlpVersion
    @Test
    fun versionsCompareLikeDesktop() {
        val stable = YtdlpVersion.parse("2026.08.19")!!
        val nightly = YtdlpVersion.parse("2026.08.19.010203")!!
        val later = YtdlpVersion.parse("2026.10.29.232701")!!
        assertTrue(stable < nightly)
        assertTrue(nightly < later)
        assertEquals(0, later.compareTo(YtdlpVersion.parse(" 2026.10.29.232701 ")!!))
        assertNull(YtdlpVersion.parse("2026.13.01"))
        assertNull(YtdlpVersion.parse("2026.1.1"))
        assertNull(YtdlpVersion.parse("v2026.10.29"))
        assertNull(YtdlpVersion.parse(null))
    }

    // ------------------------------------------------------------------ EngineErrors (vectores del diseño §13)
    @Test
    fun classifiesEngineFailures() {
        assertEquals(
            EngineErrors.INCOMPATIBLE,
            EngineErrors.classify(listOf("CANNOT LINK EXECUTABLE \"/data/app/x/lib/arm64/libffmpeg.so\": \"/data/user/0/x/no_backup/kd/payload/ffmpeg/usr/lib/libwebp.so\" program alignment (4096) cannot be smaller than system page size (16384)")),
        )
        assertEquals(
            EngineErrors.FAILED,
            EngineErrors.classify(listOf("CANNOT LINK EXECUTABLE \"/data/app/x/lib/arm64/libpython.so\": library \"libpython3.12.so.1.0\" not found")),
        )
        assertEquals(EngineErrors.DISK_FULL, EngineErrors.classify(listOf("OSError: [Errno 28] No space left on device")))
        assertEquals(EngineErrors.UNKNOWN, EngineErrors.classify(listOf("algo raro")))
    }

    // ------------------------------------------------------------------ BundledPolicy (vectores del diseño §13)
    private val bundled = YtdlpVersion.parse("2026.10.29.232701")!!

    private fun input(
        active: String? = "2026.10.01.000000",
        activeChannel: String = "nightly",
        activeVerified: Boolean = true,
        previousVerified: Boolean = false,
        wanted: String = "nightly",
        skip: Set<String> = emptySet(),
        offered: Long? = null,
    ) = BundledPolicy.Input(
        active = active?.let { YtdlpVersion.parse(it) },
        activeChannel = activeChannel,
        activeVerified = activeVerified,
        previousVerified = previousVerified,
        bundled = bundled,
        bundledChannel = "nightly",
        wantedChannel = wanted,
        skipVersions = skip,
        bundledOfferedVersionCode = offered,
        versionCode = 10000013,
    )

    @Test
    fun firstStartInstallsTheBundledCopy() {
        assertEquals(BundledPolicy.Decision(BundledPolicy.Action.INSTALL_BUNDLED, true), BundledPolicy.decide(input(active = null)))
    }

    @Test
    fun newerBundleOfTheSameChannelIsAdoptedOncePerVersionCode() {
        assertEquals(BundledPolicy.Action.INSTALL_BUNDLED, BundledPolicy.decide(input()).action)
        assertEquals(BundledPolicy.Decision(BundledPolicy.Action.KEEP, false), BundledPolicy.decide(input(offered = 10000013)))
    }

    @Test
    fun stableUserNeverGetsTheNightlyBundle() {
        assertEquals(BundledPolicy.Decision(BundledPolicy.Action.KEEP, true), BundledPolicy.decide(input(activeChannel = "stable", wanted = "stable")))
    }

    @Test
    fun rollbackIsNeverUndone() {
        assertEquals(BundledPolicy.Action.KEEP, BundledPolicy.decide(input(skip = setOf("2026.10.29.232701"))).action)
    }

    @Test
    fun newerActiveIsKept() {
        assertEquals(BundledPolicy.Action.KEEP, BundledPolicy.decide(input(active = "2026.11.15.000000")).action)
    }

    @Test
    fun corruptActiveFallsBackToPreviousThenBundled() {
        assertEquals(BundledPolicy.Action.USE_PREVIOUS, BundledPolicy.decide(input(activeVerified = false, previousVerified = true)).action)
        assertEquals(BundledPolicy.Action.INSTALL_BUNDLED, BundledPolicy.decide(input(activeVerified = false)).action)
        // aunque la copia del APK sea de otro canal: es lo único que verifica
        assertEquals(BundledPolicy.Action.INSTALL_BUNDLED, BundledPolicy.decide(input(activeVerified = false, wanted = "stable", offered = 10000013)).action)
    }

    // ------------------------------------------------------------------ ProcStat, SessionProbe, SelfTestLine
    @Test
    fun procStatSkipsTheProgramName() {
        assertEquals(ProcStat(4321, 4321, 4321), ProcStat.parse("4321 (python 3) S 100 4321 4321 0 -1 4194560 0"))
        assertEquals(ProcStat(7, 5, 6), ProcStat.parse("7 (a) b) R 1 5 6 0"))
        assertNull(ProcStat.parse("garbage"))
    }

    @Test
    fun ownSessionNeedsMatchingIdsDifferentFromTheApp() {
        val app = ProcStat(1000, 900, 900)
        val probe = SessionProbe.parseKdself(listOf("ruido", "KDSELF 4321 4321 4321 146"))!!
        assertEquals(146, probe.caCount)
        assertEquals(4321, SessionProbe.parseKdpid(listOf("KDPID:4321", "otra cosa")))
        assertTrue(SessionProbe.isOwnSession(probe, 4321, app))
        assertFalse(SessionProbe.isOwnSession(probe, 4320, app))
        assertFalse(SessionProbe.isOwnSession(probe, null, app))
        assertFalse(SessionProbe.isOwnSession(probe.copy(pgrp = 900, sid = 900), 4321, ProcStat(1000, 900, 900)))
        assertFalse(SessionProbe.isOwnSession(probe, 4321, ProcStat(1000, 4321, 4321)))
        assertNull(SessionProbe.parseKdself(listOf("KDSELF 1 2 x 4")))
    }

    @Test
    fun selfTestLinesMatchCiEmulator() {
        assertEquals(
            "PASS {\"ytdlp\":\"2026.10.29.232701\",\"ffmpeg_ok\":true,\"session\":\"session\",\"page_size\":4096,\"abi\":\"x86_64\",\"ca_count\":146}",
            SelfTestLine.pass("2026.10.29.232701", true, "session", 4096, "x86_64", 146),
        )
        assertEquals("FAIL engine_incompatible: a b", SelfTestLine.fail("engine_incompatible", "a\nb"))
        assertTrue(SelfTestLine.pass("a\"b", false, "pid", 16384, "arm64-v8a", 0).contains("\"a\\\"b\""))
    }
}
