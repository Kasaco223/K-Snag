// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.platform

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.core.content.pm.PackageInfoCompat
import io.github.kasaco223.kdownloader.BuildConfig

/** Reúne los datos de [Diagnostics] desde Android. Todo es local y rápido: no hay red ni disco. */
object DeviceDiagnostics {
    fun collect(context: Context): Diagnostics {
        val info = installedPackage(context)
        return Diagnostics(
            versionName = info?.versionName ?: BuildConfig.VERSION_NAME,
            versionCode = info?.let(PackageInfoCompat::getLongVersionCode) ?: BuildConfig.VERSION_CODE.toLong(),
            flavor = BuildConfig.FLAVOR,
            buildType = BuildConfig.BUILD_TYPE,
            sourceCommit = BuildConfig.SOURCE_COMMIT,
            androidRelease = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            deviceAbis = Build.SUPPORTED_ABIS.toList(),
            // Decide si el motor (que trae bibliotecas nativas) puede correr: Android 15+ admite páginas de 16 KB.
            pageSizeBytes = Os.sysconf(OsConstants._SC_PAGESIZE),
        )
    }

    private fun installedPackage(context: Context): PackageInfo? = try {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
    } catch (ignored: PackageManager.NameNotFoundException) {
        null
    }
}
