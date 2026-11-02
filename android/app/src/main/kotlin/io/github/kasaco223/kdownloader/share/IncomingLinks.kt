// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.share

import android.content.Intent
import java.util.Locale

/** Lee el texto de un intent de «Compartir» (ACTION_SEND text/plain) o de selección de texto (PROCESS_TEXT). */
object IncomingLinks {
    fun from(intent: Intent?): SharedLink = SharedLink.fromTexts(sharedTexts(intent))

    /**
     * Los textos en el orden en que se buscan: EXTRA_TEXT, luego el ClipData, luego EXTRA_PROCESS_TEXT. Cada uno
     * se corta en [MAX_TEXT_CHARS]: la actividad corre en el hilo principal y cualquier app puede llamarla.
     */
    fun sharedTexts(intent: Intent?): List<CharSequence> {
        if (intent == null) return emptyList()
        return try {
            when (intent.action) {
                Intent.ACTION_SEND -> buildList {
                    intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.let(::add)
                    val clip = intent.clipData
                    if (clip != null) {
                        for (i in 0 until clip.itemCount) {
                            val item = clip.getItemAt(i)
                            item.text?.let(::add)
                            val uri = item.uri
                            if (uri != null && uri.scheme?.lowercase(Locale.ROOT) in WEB_SCHEMES) add(uri.toString())
                        }
                    }
                }
                Intent.ACTION_PROCESS_TEXT -> listOfNotNull(intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT))
                else -> emptyList()
            }.map { if (it.length > MAX_TEXT_CHARS) it.subSequence(0, MAX_TEXT_CHARS) else it }
        } catch (ignored: RuntimeException) {
            // La actividad es pública: un intent con extras que no se pueden leer cuenta como «sin enlace».
            emptyList()
        }
    }

    /** Lo que se lee de cada texto compartido (64 Ki caracteres: mucho más que un mensaje con un enlace). */
    const val MAX_TEXT_CHARS: Int = 64 * 1024

    private val WEB_SCHEMES = setOf("http", "https")
}
