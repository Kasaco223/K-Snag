// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.contracts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VersionCodesTest {
    @Test
    fun splitAbiReadsTheLastDigit() {
        // 1.0.0 estable (base 1000099) y 1.0.0-rc.1 (base 1000001), como en app/build.gradle.kts.
        assertEquals("armeabi-v7a", VersionCodes.splitAbi(10000991))
        assertEquals("arm64-v8a", VersionCodes.splitAbi(10000993))
        assertEquals("x86_64", VersionCodes.splitAbi(10000994))
        assertEquals("arm64-v8a", VersionCodes.splitAbi(10000013))
        assertNull(VersionCodes.splitAbi(10000990)) // 0 = APK sin dividir
        assertNull(VersionCodes.splitAbi(10000992)) // 2 no se usa (x86 de 32 bits no se publica)
    }

    @Test
    fun abiOfFallsBackToTheDeviceForUnsplitBuilds() {
        val pixel = arrayOf("arm64-v8a", "armeabi-v7a", "armeabi")
        assertEquals("arm64-v8a", VersionCodes.abiOf(10000990, pixel))
        assertEquals("armeabi-v7a", VersionCodes.abiOf(10000991, pixel)) // el APK instalado manda
        assertEquals("x86_64", VersionCodes.abiOf(10000990, arrayOf("x86", "x86_64")))
        assertNull(VersionCodes.abiOf(10000990, arrayOf("mips")))
    }

    @Test
    fun releaseCandidatesSortBelowTheirStableVersion() {
        val rc1 = 10000013L
        val rc98 = (1_000_000 + 98) * 10L + 3
        val stable = 10000993L
        assertEquals(true, rc1 < rc98 && rc98 < stable)
    }
}
