// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los punteros de actualización con el mismo formato que escribe promote.py. */
class AppUpdateTest {
    private val repo = AppUpdate.REPO

    private fun pointer(version: String, codes: Map<String, Long>, minCode: Long? = null, minSdk: Long = 26): Map<String, Any?> {
        val tag = "android-v$version"
        return mapOf(
            "schema" to 1L, "version" to version, "tag" to tag, "package" to AppUpdate.PACKAGE, "min_sdk" to minSdk,
            "notes_url" to "https://github.com/$repo/releases/tag/$tag", "min_version_code" to minCode,
            "apks" to codes.mapValues { (abi, code) ->
                mapOf("url" to "https://github.com/$repo/releases/download/$tag/K-Downloader-Android-$abi.apk", "sha256" to "b".repeat(64), "size" to 60_000_000L, "version_code" to code)
            },
        )
    }

    private val v100 = pointer("1.0.0", mapOf("armeabi-v7a" to 10000991L, "arm64-v8a" to 10000993L, "x86_64" to 10000994L))
    private val rc1 = 10000013L // 1.0.0-rc.1, arm64

    @Test
    fun picksTheApkOfTheInstalledAbi() {
        val a = AppUpdate.parse(v100, rc1, 34)!!
        assertEquals("1.0.0", a.version)
        assertEquals(10000993L, a.versionCode)
        assertTrue(a.url.endsWith("K-Downloader-Android-arm64-v8a.apk"))
        assertFalse(a.required)
        assertEquals(10000991L, AppUpdate.parse(v100, 10000011L, 34)!!.versionCode) // 32 bits
    }

    @Test
    fun acceptsTheNewApkNameToo() {
        val renamed = v100.toMutableMap()
        renamed["apks"] = (v100["apks"] as Map<*, *>).mapValues { (abi, apk) ->
            (apk as Map<*, *>).toMutableMap().apply {
                put("url", "https://github.com/$repo/releases/download/android-v1.0.0/K-Snag-Android-$abi.apk")
            }
        }
        assertTrue(AppUpdate.parse(renamed, rc1, 34)!!.url.endsWith("K-Snag-Android-arm64-v8a.apk"))
        val other = v100.toMutableMap()
        other["apks"] = (v100["apks"] as Map<*, *>).mapValues { (abi, apk) ->
            (apk as Map<*, *>).toMutableMap().apply {
                put("url", "https://github.com/$repo/releases/download/android-v1.0.0/Otra-App-Android-$abi.apk")
            }
        }
        assertNull(AppUpdate.parse(other, rc1, 34))
    }

    @Test
    fun rejectsAnythingOdd() {
        assertNull(AppUpdate.parse(v100 + ("package" to "otra.app"), rc1, 34))
        assertNull(AppUpdate.parse(v100 + ("tag" to "android-v9.9.9"), rc1, 34))
        assertNull(AppUpdate.parse(pointer("1.0.0", mapOf("arm64-v8a" to 10000993L), minSdk = 35), rc1, 34))
        val badUrl = v100 + ("apks" to mapOf("arm64-v8a" to mapOf("url" to "https://evil.example/x.apk", "sha256" to "b".repeat(64), "size" to 1L, "version_code" to 10000993L)))
        assertNull(AppUpdate.parse(badUrl, rc1, 34))
        assertNull(AppUpdate.parse(pointer("1.0.0", mapOf("x86_64" to 10000994L)), rc1, 34)) // no hay de su ABI
        assertNull(AppUpdate.parse(pointer("1.0.0", mapOf("arm64-v8a" to 10000994L)), rc1, 34)) // dígito de otra ABI
        assertNull(AppUpdate.parse("basura", rc1, 34))
    }

    @Test
    fun onlyNewerVersionsAndRequiredFlag() {
        assertNull(AppUpdate.pick(listOf(AppUpdate.parse(v100, 10000993L, 34)), 10000993L))
        val required = AppUpdate.parse(pointer("1.0.1", mapOf("arm64-v8a" to 10001993L), minCode = 10000994L), 10000993L, 34)!!
        assertTrue(required.required)
        val rc2 = AppUpdate.parse(pointer("1.1.0-rc.2", mapOf("arm64-v8a" to 10100023L)), rc1, 34)
        assertEquals("1.1.0-rc.2", AppUpdate.pick(listOf(AppUpdate.parse(v100, rc1, 34), rc2), rc1)!!.version)
    }

    @Test
    fun rcBuildsAlsoWatchTheRcChannel() {
        assertEquals(listOf("android.json", "android-rc.json"), AppUpdate.channels("1.0.0-rc.1"))
        assertEquals(listOf("android.json"), AppUpdate.channels("1.0.0"))
    }
}
