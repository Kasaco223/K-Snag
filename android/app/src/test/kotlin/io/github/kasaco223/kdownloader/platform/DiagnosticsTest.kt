// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticsTest {
    private val pixel = Diagnostics(
        versionName = "1.0.0-rc.1-debug",
        versionCode = 10000013,
        flavor = "github",
        buildType = "debug",
        sourceCommit = "3d0e907c1b2a3d4e5f60718293a4b5c6d7e8f901",
        androidRelease = "16",
        apiLevel = 36,
        manufacturer = "Google",
        model = "Pixel 8",
        deviceAbis = listOf("arm64-v8a", "armeabi-v7a", "armeabi"),
        pageSizeBytes = 16384,
    )

    @Test
    fun reportHasTheFieldsNeededToDebugAPhone() {
        assertEquals(
            """
            K-Snag para Android 1.0.0-rc.1-debug (10000013)
            Compilación: github · debug · commit 3d0e907c1b2a3d4e5f60718293a4b5c6d7e8f901
            Android 16 (API 36) · Google Pixel 8 · APK arm64-v8a · página 16384 B
            ABI del celular: arm64-v8a, armeabi-v7a, armeabi
            """.trimIndent(),
            pixel.report(),
        )
    }

    @Test
    fun reportNeverContainsLinks() {
        val report = pixel.report()
        assertFalse(report, report.contains("://"))
        assertFalse(report, report.contains("http"))
    }

    @Test
    fun localBuildsSayWhatIsUnknown() {
        val local = pixel.copy(versionCode = 10000010, sourceCommit = "dev", deviceAbis = emptyList())
        assertNull(local.apkAbi)
        assertNull(local.knownCommit)
        val report = local.report()
        assertEquals(true, "commit desconocido" in report)
        assertEquals(true, "APK sin dividir" in report)
        assertEquals(true, "ABI del celular: desconocida" in report)
    }

    @Test
    fun deviceDoesNotRepeatTheBrand() {
        assertEquals("Google Pixel 8", pixel.device)
        assertEquals("samsung SM-S918B", pixel.copy(manufacturer = "samsung", model = "SM-S918B").device)
        assertEquals("Xiaomi 14", pixel.copy(manufacturer = "Xiaomi", model = "Xiaomi 14").device)
        assertEquals("Pixel 8", pixel.copy(manufacturer = "", model = "Pixel 8").device)
    }
}
