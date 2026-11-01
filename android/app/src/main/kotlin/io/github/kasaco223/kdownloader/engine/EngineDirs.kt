// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.engine

import android.content.Context
import java.io.File

/**
 * Carpetas del motor (diseño del motor §1). Todo vive en noBackupFilesDir: depende de este celular y nunca se
 * copia a una copia de seguridad ni a otro teléfono.
 *
 * ```
 * kd/payload/{python,ffmpeg}/   paquetes descomprimidos, cada uno con su marca .kd-installed
 * kd/engine/state.json          estado del motor
 * kd/engine/ytdlp/<v>/yt-dlp    copias verificadas de yt-dlp
 * kd/engine/ca/cacert.pem       certificados raíz (SSL_CERT_FILE)
 * kd/home/                      HOME vacío para Python
 * kd/runs/<id>/                 carpeta de trabajo de cada ejecución (TMPDIR)
 * cacheDir/yt-dlp/              --cache-dir (se puede perder)
 * ```
 */
class EngineDirs(context: Context) {
    val root = File(context.noBackupFilesDir, "kd")
    val payload = File(root, "payload")
    val engine = File(root, "engine")
    val stateFile = File(engine, "state.json")
    val ytdlp = File(engine, "ytdlp")
    val caFile = File(engine, "ca/cacert.pem")
    val home = File(root, "home")
    val runs = File(root, "runs")
    val cache = File(context.cacheDir, "yt-dlp")

    /** Cambia con cada actualización de la app: se lee de nuevo en cada ejecución. */
    val nativeLibDir: File get() = File(appInfoNativeDir())

    private val appContext = context.applicationContext
    private fun appInfoNativeDir(): String = appContext.applicationInfo.nativeLibraryDir

    fun payloadDir(name: String) = File(payload, name)

    fun ytdlpFile(version: String) = File(ytdlp, "$version/yt-dlp")

    /** LD_LIBRARY_PATH de Python y sus hijos: primero las bibliotecas de Python, después las de FFmpeg. */
    fun libraryPath(): String = listOf("python", "ffmpeg").joinToString(":") { File(payloadDir(it), "usr/lib").path }

    fun pythonHome() = File(payloadDir("python"), "usr")

    fun lib(name: String) = File(nativeLibDir, name)
}
