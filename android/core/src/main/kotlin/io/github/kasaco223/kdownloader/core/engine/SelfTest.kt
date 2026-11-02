// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.core.engine

/** Línea `/proc/<pid>/stat`: los campos después del último `)` (el nombre del programa puede traer espacios). */
data class ProcStat(val pid: Int, val pgrp: Int, val session: Int) {
    companion object {
        fun parse(line: String): ProcStat? {
            val open = line.indexOf('(')
            val close = line.lastIndexOf(')')
            if (open <= 0 || close < open) return null
            val pid = line.substring(0, open).trim().toIntOrNull() ?: return null
            val rest = line.substring(close + 1).trim().split(' ')
            // rest[0] = estado (campo 3), rest[1] = ppid (4), rest[2] = pgrp (5), rest[3] = sesión (6)
            val pgrp = rest.getOrNull(2)?.toIntOrNull() ?: return null
            val session = rest.getOrNull(3)?.toIntOrNull() ?: return null
            return ProcStat(pid, pgrp, session)
        }
    }
}

/**
 * Prueba de sesión del motor (diseño del motor §3, paso a): Python corre bajo `setsid` y escribe
 * `KDSELF <pid> <pgrp> <sid> <certificados>`; el lanzador escribe antes `KDPID:<pid>` en stderr.
 * La sesión propia vale solo si los cuatro números coinciden y son distintos del grupo y la sesión de la app:
 * así cancelar puede mandar la señal a todo el grupo (Python, FFmpeg y QuickJS) sin tocar nada más.
 */
object SessionProbe {
    data class Probe(val pid: Int, val pgrp: Int, val sid: Int, val caCount: Int)

    fun parseKdself(stdout: List<String>): Probe? = stdout.asReversed().firstNotNullOfOrNull { line ->
        val p = line.trim().split(' ')
        if (p.size == 5 && p[0] == "KDSELF") {
            val n = p.drop(1).map { it.toIntOrNull() ?: return@firstNotNullOfOrNull null }
            Probe(n[0], n[1], n[2], n[3])
        } else {
            null
        }
    }

    fun parseKdpid(stderr: List<String>): Int? =
        stderr.firstNotNullOfOrNull { Regex("""^KDPID:(\d+)$""").find(it.trim())?.groupValues?.get(1)?.toIntOrNull() }

    fun isOwnSession(probe: Probe, kdpid: Int?, app: ProcStat): Boolean =
        kdpid != null && kdpid == probe.pid && probe.pid == probe.pgrp && probe.pid == probe.sid &&
            probe.pgrp != app.pgrp && probe.sid != app.session
}

/**
 * Línea de la autoprueba que lee la CI (scripts/ci_emulator.sh), con tag `KDL-SELFTEST`:
 * `PASS {"ytdlp":…,"ffmpeg_ok":true,"session":…,"page_size":…,"abi":…,"ca_count":…}` o `FAIL <código>: <línea>`.
 */
object SelfTestLine {
    fun pass(ytdlp: String, ffmpegOk: Boolean, session: String, pageSize: Long, abi: String, caCount: Int): String =
        "PASS {\"ytdlp\":${quote(ytdlp)},\"ffmpeg_ok\":$ffmpegOk,\"session\":${quote(session)}," +
            "\"page_size\":$pageSize,\"abi\":${quote(abi)},\"ca_count\":$caCount}"

    fun fail(code: String, line: String): String = "FAIL $code: ${line.replace('\n', ' ').take(300)}"

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c.code < 0x20 -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}
