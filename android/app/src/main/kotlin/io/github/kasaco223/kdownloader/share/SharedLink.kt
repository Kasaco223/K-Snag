// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.share

import io.github.kasaco223.kdownloader.core.Urls

/**
 * Lo que llegó al compartir: el primer enlace http(s) válido ([url], null si no había ninguno) y cuántos
 * enlaces distintos traía en total el texto.
 */
data class SharedLink(val url: String?, val total: Int) {
    companion object {
        /**
         * Busca enlaces en los textos recibidos, en orden (cada texto con las reglas de [Urls.coerce]: acepta
         * también un dominio escrito a mano, como `youtu.be/xyz`), sin contar repetidos.
         */
        fun fromTexts(texts: List<CharSequence?>): SharedLink {
            val urls = LinkedHashSet<String>()
            for (text in texts) urls += Urls.coerce(text)
            return SharedLink(url = urls.firstOrNull(), total = urls.size)
        }
    }
}
