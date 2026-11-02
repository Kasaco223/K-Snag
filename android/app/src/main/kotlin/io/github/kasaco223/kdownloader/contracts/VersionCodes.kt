// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.contracts

/**
 * Contrato del versionCode con el build (app/build.gradle.kts): base = MAYOR·1 000 000 + MENOR·10 000 +
 * PARCHE·100 + (rc ? N : 99), y versionCode = base·10 + dígito de ABI (1 armeabi-v7a, 3 arm64-v8a,
 * 4 x86_64; 0 = APK sin dividir, solo compilaciones locales).
 *
 * Se lee siempre el versionCode INSTALADO (PackageInfoCompat.getLongVersionCode), nunca
 * BuildConfig.VERSION_CODE, que es uno por variante y no por APK.
 */
object VersionCodes {
    val ABI_BY_DIGIT: Map<Int, String> = mapOf(1 to "armeabi-v7a", 3 to "arm64-v8a", 4 to "x86_64")

    /** La ABI del APK instalado según su último dígito, o null si es un APK sin dividir. */
    fun splitAbi(installedVersionCode: Long): String? = ABI_BY_DIGIT[installedVersionCode.mod(10L).toInt()]

    /** La ABI que corresponde a este celular: la del APK instalado o, si no está dividido, la primera conocida. */
    fun abiOf(installedVersionCode: Long, supportedAbis: Array<String>): String? =
        splitAbi(installedVersionCode) ?: supportedAbis.firstOrNull { it in ABI_BY_DIGIT.values }
}
