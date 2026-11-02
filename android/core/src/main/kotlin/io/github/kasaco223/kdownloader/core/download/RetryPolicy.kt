// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.core.download

/**
 * Qué hacer solo después de un error (diseño del motor §10), según el campo `auto` del catálogo de Windows:
 *
 * - `update_retry` (403, motor viejo): actualizar yt-dlp y reintentar, una vez.
 * - `fallback_best` (formato no disponible): reintentar con «la mejor disponible», una vez.
 * - `retry_network` (red): reintentar hasta 3 veces, esperando 5, 20 y 60 s.
 * - `quiet_mode` (límite de YouTube, bot-check): reintentar una vez en modo tranquilo, al minuto.
 * - `merge_failed`: reintentar una vez con «la mejor disponible» (otra combinación de códecs).
 *
 * Nunca más de [MAX_ATTEMPTS] intentos en total. [done] son las acciones automáticas que ya se usaron.
 */
object RetryPolicy {
    const val MAX_ATTEMPTS = 8

    sealed interface Decision {
        data object GiveUp : Decision

        /** Reintentar después de [delayMs]; [action] queda anotada en el trabajo. */
        data class Retry(
            val action: String,
            val delayMs: Long = 0,
            val updateEngine: Boolean = false,
            val forceBest: Boolean = false,
            val quiet: Boolean = false,
        ) : Decision
    }

    private val NETWORK_DELAYS = listOf(5_000L, 20_000L, 60_000L)

    fun decide(code: String, auto: String?, attempts: Int, done: List<String>): Decision {
        if (attempts >= MAX_ATTEMPTS) return Decision.GiveUp
        fun once(action: String, d: Decision.Retry) = if (action in done) Decision.GiveUp else d
        return when {
            auto == "update_retry" -> once("update_retry", Decision.Retry("update_retry", updateEngine = true))
            auto == "fallback_best" || code == "merge_failed" -> once("fallback_best", Decision.Retry("fallback_best", forceBest = true))
            auto == "quiet_mode" -> once("quiet_mode", Decision.Retry("quiet_mode", delayMs = 60_000, quiet = true))
            auto == "retry_network" -> {
                val n = done.count { it == "retry_network" }
                if (n >= NETWORK_DELAYS.size) Decision.GiveUp else Decision.Retry("retry_network", delayMs = NETWORK_DELAYS[n])
            }
            else -> Decision.GiveUp
        }
    }
}
