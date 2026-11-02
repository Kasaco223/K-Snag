// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader

import android.content.Intent
import io.github.kasaco223.kdownloader.contracts.MainIntents
import io.github.kasaco223.kdownloader.core.Urls

/** kdownloader://download?url=<enlace>: el enlace que abre la web («¿Ya tienes la app?») o la app de Windows. */
object DeepLinks {
    /**
     * El enlace http(s) que trae el intent, ya validado con [Urls.coerce]; null si no es ese deep link. Cualquier
     * página puede abrirlo, así que solo se muestra: nunca empieza una descarga por sí solo.
     */
    fun url(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val data = intent.data ?: return null
        if (data.scheme != MainIntents.DEEP_LINK_SCHEME) return null
        if (!data.host.equals(MainIntents.DEEP_LINK_HOST, ignoreCase = true)) return null
        val raw = try {
            data.getQueryParameter(MainIntents.DEEP_LINK_URL_PARAM)
        } catch (ignored: RuntimeException) {
            null // URI opaca o mal formada
        }
        return Urls.coerce(raw).firstOrNull()
    }
}
