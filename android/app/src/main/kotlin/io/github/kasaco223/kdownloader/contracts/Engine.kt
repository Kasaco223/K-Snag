// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.contracts

import kotlinx.coroutines.flow.StateFlow

/**
 * El motor de descargas visto desde la interfaz (contrato B.2 del plan; lo implementa engine.KdEngine).
 * En esta versión prepara Python, FFmpeg y yt-dlp; actualizar el motor llega más adelante.
 */
interface EngineController {
    val status: StateFlow<EngineStatus>

    /** Idempotente. Si hace falta trabajo, lo corre en un servicio en primer plano («Preparando el motor»). */
    fun prepare()

    /** Botón «Reintentar» de un error: vuelve a preparar, salteando los pasos que ya terminaron. */
    fun retrySetup()

    /** «Reparar»: vuelve a descomprimir y probar todo. Falla con [EngineError.code] = busy si el motor está en uso. */
    suspend fun repair(): Result<Unit>
}

sealed interface EngineStatus {
    /** Todavía no se preparó (o se está revisando al abrir): llamar a [EngineController.prepare]. */
    data object NotReady : EngineStatus

    /** [progress] de 0 a 1 mientras descomprime; null en los pasos sin medida. */
    data class Preparing(val phase: EnginePhase, val progress: Float?) : EngineStatus

    data class Ready(val versions: EngineVersions) : EngineStatus

    /** [requiredFreeBytes]: cuánto espacio hace falta liberar cuando [error] es disk_full. */
    data class Failed(val error: EngineError, val phase: EnginePhase?, val requiredFreeBytes: Long? = null) : EngineStatus
}

enum class EnginePhase { CHECK_SPACE, UNPACK, FINISH, SELF_TEST }

/** [code]: engine_incompatible, engine_failed, disk_full, timeout, busy o unknown. [detail]: la línea técnica. */
data class EngineError(val code: String, val detail: String)

data class EngineVersions(
    val ytdlp: String,
    val ytdlpChannel: String,
    val ytdlpPrevious: String?,
    val ffmpeg: String,
    val python: String,
    val quickjs: String,
    val youtubedlAndroid: String,
    /** "session" (cancelar detiene todo el grupo de procesos) o "pid" (solo Python, más la limpieza de carpetas). */
    val sessionMode: String,
)
