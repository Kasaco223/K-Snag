// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.core.download

/**
 * Línea de comando de yt-dlp en Android (diseño del motor §3 y §6). El orden sigue al de Windows, con QuickJS
 * en lugar de Deno. La URL va siempre al final, después de `--`, y nunca dentro de un preset.
 */
object Args {
    data class Paths(
        val cacheDir: String,
        val qjs: String,
        val ffmpeg: String,
        val home: String,
        val temp: String,
    )

    /** Argumentos que van en toda ejecución (descarga, análisis). [playerClient]: solo para YouTube. */
    fun base(paths: Paths, playerClient: String? = null): List<String> = buildList {
        addAll(listOf("--ignore-config", "--no-update", "--no-plugin-dirs", "--color", "never"))
        addAll(listOf("--cache-dir", paths.cacheDir))
        addAll(listOf("--no-js-runtimes", "--js-runtimes", "quickjs:${paths.qjs}"))
        addAll(listOf("--ffmpeg-location", paths.ffmpeg))
        if (playerClient != null) addAll(listOf("--extractor-args", "youtube:player_client=$playerClient"))
    }

    fun download(
        paths: Paths,
        url: String,
        choice: Presets.Choice,
        isYoutube: Boolean,
        filenameStyle: String = "title_id",
        maxCodec: String = "vp9",
        forceBest: Boolean = false,
        quiet: Boolean = false,
        playerClient: String? = null,
        extra: List<String> = emptyList(),
    ): List<String> = buildList {
        addAll(base(paths, playerClient.takeIf { isYoutube }))
        addAll(listOf("--no-quiet", "--newline", "--progress", "--progress-delta", "0.5"))
        addAll(listOf("--progress-template", Kdl.TEMPLATE_DL, "--progress-template", Kdl.TEMPLATE_PP))
        addAll(listOf("--print", Kdl.PRINT_FILE, "--print", Kdl.PRINT_META))
        addAll(listOf("-P", "home:${paths.home}", "-P", "temp:${paths.temp}"))
        addAll(Presets.outputTemplate(filenameStyle))
        addAll(listOf("-N", "2", "--retries", "10", "--fragment-retries", "10"))
        addAll(listOf("--retry-sleep", "exp=1:20", "--retry-sleep", "fragment:exp=1:20"))
        addAll(listOf("--embed-metadata", "--embed-thumbnail", "--embed-chapters", "--no-playlist"))
        addAll(listOf("--match-filters", "!is_live"))
        addAll(if (forceBest) Presets.fallbackArgs(choice) else Presets.buildArgs(choice, isYoutube, maxCodec))
        if (quiet) addAll(listOf("--sleep-requests", "0.75", "--sleep-interval", "10", "--max-sleep-interval", "20"))
        addAll(extra)
        add("--")
        add(url)
    }
}
