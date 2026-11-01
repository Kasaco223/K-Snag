// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.core.engine

/**
 * Versión de yt-dlp: `AAAA.MM.DD` (estable) o `AAAA.MM.DD.HHMMSS` (nightly), como `versions.py` de Windows.
 * Se comparan por números: la estable de un día va antes que cualquier nightly del mismo día.
 */
data class YtdlpVersion(val parts: List<Int>, val text: String) : Comparable<YtdlpVersion> {
    override fun compareTo(other: YtdlpVersion): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val c = (parts.getOrNull(i) ?: -1).compareTo(other.parts.getOrNull(i) ?: -1)
            if (c != 0) return c
        }
        return 0
    }

    override fun toString(): String = text

    companion object {
        private val PATTERN = Regex("""(\d{4})\.(\d{2})\.(\d{2})(?:\.(\d{6}))?""")

        fun parse(text: String?): YtdlpVersion? {
            val trimmed = text?.trim() ?: return null
            val m = PATTERN.matchEntire(trimmed) ?: return null
            val parts = m.groupValues.drop(1).filter { it.isNotEmpty() }.map { it.toInt() }
            if (parts[1] !in 1..12 || parts[2] !in 1..31) return null
            return YtdlpVersion(parts, trimmed)
        }
    }
}
