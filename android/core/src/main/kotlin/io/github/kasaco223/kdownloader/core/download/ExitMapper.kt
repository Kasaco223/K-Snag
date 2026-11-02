// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.core.download

/**
 * Resultado de una ejecución de yt-dlp (diseño del motor §9, "Exit mapping"; gana la primera fila que aplica).
 * Las paradas del motor por tiempo, red o sistema llegan con las descargas en segundo plano.
 */
object ExitMapper {
    enum class Stop { DUPLICATE, DISK_FULL, STALLED }

    sealed interface Outcome {
        data object Canceled : Outcome
        data class Ok(val path: String, val alreadyDownloaded: Boolean = false) : Outcome
        data class AlreadyDownloaded(val note: String = "already_downloaded") : Outcome
        data object Killed : Outcome

        /** [code] null = clasificar las líneas con el catálogo de errores. */
        data class Failed(val code: String?) : Outcome
    }

    fun map(
        userCanceled: Boolean,
        stop: Stop?,
        exitCode: Int,
        acceptedFile: String?,
        alreadyDownloadedFile: String?,
        lines: List<String>,
    ): Outcome {
        if (userCanceled) return Outcome.Canceled
        when (stop) {
            Stop.DUPLICATE -> return Outcome.AlreadyDownloaded()
            Stop.DISK_FULL -> return Outcome.Failed("disk_full")
            Stop.STALLED -> return Outcome.Failed("stalled")
            null -> Unit
        }
        if (exitCode == 0 && acceptedFile != null) return Outcome.Ok(acceptedFile)
        if (alreadyDownloadedFile != null && acceptedFile == null) return Outcome.Ok(alreadyDownloadedFile, alreadyDownloaded = true)
        if (exitCode == 0) {
            val text = lines.joinToString("\n").lowercase()
            return Outcome.Failed(
                when {
                    "does not pass filter (!is_live)" in text -> "live_unsupported"
                    "ffmpeg is not installed" in text -> "ffmpeg_missing"
                    else -> "unknown"
                },
            )
        }
        if (exitCode == 137 || exitCode == 143) return Outcome.Killed
        return Outcome.Failed(null)
    }

    /** "[download] <ruta> has already been downloaded" (regla de Windows): la ruta, o null. */
    fun alreadyDownloadedPath(lines: List<String>): String? {
        val marker = " has already been downloaded"
        for (line in lines.asReversed()) {
            if (line.startsWith("[download] ") && marker in line) {
                return line.substring("[download] ".length, line.indexOf(marker)).trim()
            }
        }
        return null
    }
}

/**
 * Qué decir de un trabajo en la fila y en la notificación (diseño del motor §8). Devuelve la clave del texto;
 * la app la traduce (strings.xml) en un solo lugar.
 */
object StageLabeler {
    data class Stage(val key: String, val stepIndex: Int? = null, val stepTotal: Int? = null)

    fun label(state: String, stepKind: String?, stepIndex: Int, stepTotal: Int, postprocessor: String?, option: String): Stage =
        when (state) {
            "queued" -> Stage("jobs_state_queued")
            "downloading" -> {
                val key = when (stepKind) {
                    "video" -> "jobs_step_video"
                    "audio" -> "jobs_step_audio"
                    else -> "jobs_step_av"
                }
                if (stepTotal == 2) Stage(key, stepIndex, stepTotal) else Stage(key)
            }
            "processing" -> Stage(
                when (postprocessor) {
                    "Merger" -> "jobs_pp_merger"
                    "ExtractAudio" -> if (option == "m4a") "jobs_pp_extract_audio_m4a" else "jobs_pp_extract_audio"
                    "EmbedThumbnail" -> "jobs_pp_embed_thumbnail"
                    "FFmpegMetadata", "Metadata" -> "jobs_pp_metadata"
                    "Publish" -> "jobs_pp_publish"
                    else -> "jobs_pp_other"
                },
            )
            "done" -> Stage("jobs_state_done")
            "error" -> Stage("jobs_state_error")
            "canceled" -> Stage("jobs_state_canceled")
            else -> Stage("jobs_state_queued")
        }
}
