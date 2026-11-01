// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.core.engine

/**
 * Qué yt-dlp queda activo al preparar el motor (diseño del motor §4, `ensureActive`).
 *
 * a) Si la copia activa falta o no coincide con su SHA-256: la anterior si verifica; si no, la del APK.
 * b) Si no, la del APK reemplaza a la activa solo si es del mismo canal, más nueva, no está en la lista de
 *    versiones descartadas y esta versión de la app todavía no lo decidió. Así una persona en el canal estable
 *    nunca recibe la nightly del APK, una vuelta atrás nunca se deshace sola y se decide una vez por versión.
 */
object BundledPolicy {
    enum class Action { KEEP, USE_PREVIOUS, INSTALL_BUNDLED }

    data class Input(
        val active: YtdlpVersion?,
        val activeChannel: String?,
        val activeVerified: Boolean,
        val previousVerified: Boolean,
        val bundled: YtdlpVersion,
        val bundledChannel: String,
        val wantedChannel: String,
        val skipVersions: Set<String>,
        val bundledOfferedVersionCode: Long?,
        val versionCode: Long,
    )

    /** La acción, y si hay que guardar que esta versión de la app ya ofreció su yt-dlp. */
    data class Decision(val action: Action, val markOffered: Boolean)

    fun decide(i: Input): Decision {
        if (i.active == null || !i.activeVerified) {
            val action = if (i.active != null && i.previousVerified) Action.USE_PREVIOUS else Action.INSTALL_BUNDLED
            return Decision(action, markOffered = action == Action.INSTALL_BUNDLED)
        }
        if (i.bundledOfferedVersionCode == i.versionCode) return Decision(Action.KEEP, markOffered = false)
        val adopt = i.bundledChannel == i.wantedChannel &&
            i.bundled > i.active &&
            i.bundled.text !in i.skipVersions
        return Decision(if (adopt) Action.INSTALL_BUNDLED else Action.KEEP, markOffered = true)
    }
}
