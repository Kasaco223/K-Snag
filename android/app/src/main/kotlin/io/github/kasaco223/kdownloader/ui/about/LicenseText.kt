// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.ui.about

/** Texto de la licencia que va dentro del APK (lo genera la tarea kdLicenseAssets desde android/LICENSE). */
object LicenseText {
    const val GPL_ASSET = "licenses/gpl-3.0.txt"

    private val BLANK_LINES = Regex("""\n[ \t]*\n+""")

    /**
     * Párrafos del texto oficial, para leerlo en un celular: el original viene cortado a 70 columnas, así que
     * las líneas de cada párrafo se unen y la pantalla las vuelve a acomodar a su ancho.
     */
    fun paragraphs(raw: String): List<String> =
        raw.replace("\r\n", "\n").split(BLANK_LINES)
            .map { block -> block.lines().joinToString(" ") { it.trim() }.trim() }
            .filter { it.isNotEmpty() }

    /** Bloques separados por líneas en blanco, sin reacomodar (los textos de terceros traen listas y sangrías). */
    fun blocks(raw: String): List<String> =
        raw.replace("\r\n", "\n").split(BLANK_LINES).map { it.trimEnd() }.filter { it.isNotBlank() }
}
