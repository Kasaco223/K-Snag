// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.platform

/**
 * Dónde está el código fuente de ESTA compilación (GPL-3.0 §6: el código correspondiente).
 * Una versión publicada enlaza a su tag; cualquier otra compilación, al commit del que salió.
 */
object SourceLinks {
    private val COMMIT = Regex("[0-9a-f]{7,40}")

    /** True si [value] es el hash (abreviado o completo) de un commit de git. */
    fun isCommit(value: String): Boolean = COMMIT.matches(value)

    fun sourceTree(repoUrl: String, buildType: String, versionName: String, sourceCommit: String): String = when {
        buildType == "release" -> "$repoUrl/tree/android-v$versionName/android"
        isCommit(sourceCommit) -> "$repoUrl/tree/$sourceCommit"
        else -> "$repoUrl/tree/master/android" // compilación local sin commit conocido
    }
}
