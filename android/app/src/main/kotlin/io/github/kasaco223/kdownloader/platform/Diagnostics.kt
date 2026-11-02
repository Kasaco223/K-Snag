// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.platform

import io.github.kasaco223.kdownloader.contracts.VersionCodes

/**
 * Lo que muestra la tarjeta «Diagnóstico» y copia «Copiar detalles». Solo datos del celular y de la app:
 * nunca enlaces, nombres de archivo ni nada que la persona haya escrito o compartido.
 * Se arma en [DeviceDiagnostics.collect]; esta clase no toca Android, así se prueba en la JVM.
 */
data class Diagnostics(
    val versionName: String,
    /** versionCode del APK instalado (por ABI), no BuildConfig.VERSION_CODE. */
    val versionCode: Long,
    val flavor: String,
    val buildType: String,
    val sourceCommit: String,
    val androidRelease: String,
    val apiLevel: Int,
    val manufacturer: String,
    val model: String,
    val deviceAbis: List<String>,
    val pageSizeBytes: Long,
) {
    /** ABI del APK instalado según el dígito final del versionCode; null = APK sin dividir (compilación local). */
    val apkAbi: String? get() = VersionCodes.splitAbi(versionCode)

    /** "Google Pixel 8", sin repetir la marca si el modelo ya la trae ("Pixel 8" vs "samsung SM-S918B"). */
    val device: String
        get() = when {
            manufacturer.isBlank() -> model.trim()
            model.trim().startsWith(manufacturer.trim(), ignoreCase = true) -> model.trim()
            else -> "${manufacturer.trim()} ${model.trim()}".trim()
        }

    /** El commit del código fuente, o null si es una compilación local sin commit conocido. */
    val knownCommit: String? get() = sourceCommit.takeIf(SourceLinks::isCommit)

    /**
     * Texto de «Copiar detalles», siempre en español y con el mismo formato que los detalles de las
     * descargas (así se lee igual en cualquier reporte).
     */
    fun report(): String = listOf(
        "K-Snag para Android $versionName ($versionCode)",
        "Compilación: $flavor · $buildType · commit ${knownCommit ?: "desconocido"}",
        "Android $androidRelease (API $apiLevel) · $device · APK ${apkAbi ?: "sin dividir"} · página $pageSizeBytes B",
        "ABI del celular: ${deviceAbis.joinToString(", ").ifEmpty { "desconocida" }}",
    ).joinToString("\n")
}
