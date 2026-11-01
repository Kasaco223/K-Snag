// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.core.download

/**
 * Opciones de descarga → argumentos de yt-dlp, iguales byte a byte a `engine/presets.py` de Windows:
 * - recomendado → H.264 hasta 1080p + AAC en MP4, que se reproduce en todo;
 * - máxima → VP9 (o AV1) + Opus en MKV;
 * - mp3 / m4a → solo audio.
 * Nunca `[height<=N]`: rompe los Shorts verticales (`res` usa la dimensión menor).
 */
object Presets {
    val ALL = listOf("recommended", "max", "mp3", "m4a")

    data class Choice(val preset: String, val height: Int? = null) {
        val isAudio: Boolean get() = preset == "mp3" || preset == "m4a"
    }

    /** "recommended", "max", "mp3", "m4a" o "h<N>"; cualquier otra cosa → null (invalid). */
    fun optionToChoice(option: String): Choice? = when {
        option in ALL -> Choice(option)
        option.length > 1 && option[0] == 'h' && option.drop(1).all(Char::isDigit) ->
            option.drop(1).toIntOrNull()?.takeIf { it in 1..9999 }?.let { Choice("height", it) }
        else -> null
    }

    fun containerFor(option: String, height: Int? = null): String = when {
        option == "mp3" || option == "m4a" -> option
        option == "max" || (option.startsWith("h") && (height ?: 0) > 1080) -> "mkv"
        else -> "mp4"
    }

    private fun videoFormat(isYoutube: Boolean) = listOf("-f", if (isYoutube) "bv+ba" else "bv*+ba/b")

    fun buildArgs(choice: Choice, isYoutube: Boolean, maxCodec: String = "vp9"): List<String> = when (choice.preset) {
        "recommended" -> videoFormat(isYoutube) + listOf(
            "-S", "lang,vcodec:h264,res:1080,acodec:aac,quality,fps,hdr:12",
            "--merge-output-format", "mp4", "--remux-video", "mp4",
        )
        "max" -> {
            val codec = if (maxCodec == "vp9") ",vcodec:vp9" else ""
            videoFormat(isYoutube) + listOf("-S", "lang,res,fps,hdr:sdr$codec,acodec:opus", "--merge-output-format", "mkv")
        }
        "height" -> {
            val n = choice.height ?: 1080
            if (n <= 1080) {
                videoFormat(isYoutube) + listOf(
                    "-S", "lang,res:$n,vcodec:h264,acodec:aac,fps,hdr:sdr",
                    "--merge-output-format", "mp4", "--remux-video", "mp4",
                )
            } else {
                val codec = if (maxCodec == "vp9") ",vcodec:vp9" else ""
                videoFormat(isYoutube) + listOf("-S", "lang,res:$n,fps,hdr:sdr$codec,acodec:opus", "--merge-output-format", "mkv")
            }
        }
        "mp3" -> listOf("-f", "ba/b", "-x", "--audio-format", "mp3", "--audio-quality", "0")
        "m4a" -> listOf("-f", "ba[ext=m4a]/ba[acodec^=mp4a]/ba/b", "-x", "--audio-format", "m4a")
        else -> throw IllegalArgumentException("Opción desconocida: ${choice.preset}")
    }

    /** Para "Requested format is not available": la mejor calidad que haya, avisando a la persona. */
    fun fallbackArgs(choice: Choice): List<String> =
        if (choice.isAudio) buildArgs(choice, isYoutube = false) else listOf("-f", "bv*+ba/b", "--merge-output-format", "mp4/mkv")

    /** Nombre de archivo: título recortado a 120 bytes + [id]. "simple" agrega --restrict-filenames. */
    fun outputTemplate(style: String): List<String> {
        val args = mutableListOf("-o", "%(title).120B [%(id)s].%(ext)s")
        if (style == "simple") args += "--restrict-filenames"
        return args
    }
}
