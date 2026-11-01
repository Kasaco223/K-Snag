// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.core.engine

/**
 * Códigos de error al preparar el motor. Los mismos nombres que el catálogo de errores
 * (errors_android.json, que llega con las descargas): la pantalla los traduce con sus propios textos.
 */
object EngineErrors {
    /** Celular con páginas de 16 KB y bibliotecas alineadas a 4 KB (libwebp de FFmpeg, hasta M5). */
    const val INCOMPATIBLE = "engine_incompatible"

    /** El motor no arranca (falta una biblioteca, archivo dañado): se arregla con «Reparar». */
    const val FAILED = "engine_failed"
    const val DISK_FULL = "disk_full"
    const val TIMEOUT = "timeout"
    const val BUSY = "busy"
    const val UNKNOWN = "unknown"

    /** Clasifica la salida de un paso que falló. Primero el tamaño de página: es más específico. */
    fun classify(lines: List<String>): String {
        val text = lines.joinToString("\n").lowercase()
        return when {
            "cannot be smaller than system page size" in text || "program alignment" in text -> INCOMPATIBLE
            "no space left on device" in text || "errno 28" in text -> DISK_FULL
            "cannot link executable" in text || "dlopen failed" in text ||
                "no such file or directory" in text || "modulenotfounderror" in text ||
                "could not find platform" in text -> FAILED
            else -> UNKNOWN
        }
    }
}
