// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.core.update

/**
 * La actualización de la app (sabor github): lee los punteros que publica `promote.py` (web/data/android.json y,
 * para las versiones de prueba, android-rc.json) y decide si hay una versión nueva para el APK instalado. Solo
 * acepta APK de los Releases de este repo, del mismo paquete y con SHA-256; Android exige además la misma firma.
 */
object AppUpdate {
    const val REPO = "Kasaco223/K-YoutubeVideoDownloaderHD"
    const val PACKAGE = "io.github.kasaco223.kdownloader"
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val VERSION = Regex("""^\d{1,3}\.\d{1,2}\.\d{1,2}(-rc\.[1-9]\d?)?$""")

    /** El dígito final del versionCode dice qué APK está instalado (app/build.gradle.kts). */
    val ABI_BY_DIGIT = mapOf(1 to "armeabi-v7a", 3 to "arm64-v8a", 4 to "x86_64")

    data class Available(
        val version: String,
        val versionCode: Long,
        val url: String,
        val sha256: String,
        val size: Long,
        val notesUrl: String?,
        /** El puntero marca que la versión instalada ya no sirve (min_version_code). */
        val required: Boolean,
    )

    /** Un puntero leído con JsonStrict; null si no pasa la validación. */
    fun parse(raw: Any?, installedCode: Long, sdk: Int): Available? {
        val m = raw as? Map<*, *> ?: return null
        if ((m["schema"] as? Number)?.toLong() != 1L || m["package"] != PACKAGE) return null
        val version = (m["version"] as? String)?.takeIf { VERSION.matches(it) } ?: return null
        val tag = "android-v$version"
        if (m["tag"] != tag) return null
        val minSdk = (m["min_sdk"] as? Number)?.toInt() ?: return null
        if (sdk < minSdk) return null
        val abi = ABI_BY_DIGIT[(installedCode % 10).toInt()] ?: return null
        val apk = (m["apks"] as? Map<*, *>)?.get(abi) as? Map<*, *> ?: return null
        val url = apk["url"] as? String ?: return null
        if (url != "https://github.com/$REPO/releases/download/$tag/K-Downloader-Android-$abi.apk") return null
        val sha = (apk["sha256"] as? String)?.takeIf { SHA256.matches(it) } ?: return null
        val size = (apk["size"] as? Number)?.toLong()?.takeIf { it in 1..MAX_APK } ?: return null
        val code = (apk["version_code"] as? Number)?.toLong() ?: return null
        if (code % 10 != installedCode % 10) return null // otra ABI: nunca
        val minCode = (m["min_version_code"] as? Number)?.toLong()
        val notes = (m["notes_url"] as? String)?.takeIf { it.startsWith("https://github.com/$REPO/releases/tag/") }
        return Available(version, code, url, sha, size, notes, required = minCode != null && installedCode < minCode)
    }

    /** La mejor de las versiones candidatas que sea más nueva que la instalada, o null. */
    fun pick(candidates: List<Available?>, installedCode: Long): Available? =
        candidates.filterNotNull().filter { it.versionCode > installedCode }.maxByOrNull { it.versionCode }

    /** Las versiones de prueba (-rc.N) también miran el canal rc; las estables, solo el estable. */
    fun channels(installedVersion: String): List<String> =
        if ("-rc." in installedVersion.substringBefore("-debug").substringBefore("-qa")) listOf("android.json", "android-rc.json") else listOf("android.json")

    const val MAX_APK = 200L * 1024 * 1024
}
