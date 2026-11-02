// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.contracts

/** Intents que abren MainActivity desde fuera de la app. */
object MainIntents {
    /** kdownloader://download?url=<enlace codificado>, el mismo de la app de Windows y de la web. */
    const val DEEP_LINK_SCHEME = "kdownloader"
    const val DEEP_LINK_HOST = "download"
    const val DEEP_LINK_URL_PARAM = "url"
}
