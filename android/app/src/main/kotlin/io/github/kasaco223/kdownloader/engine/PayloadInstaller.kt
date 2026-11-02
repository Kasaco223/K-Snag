// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.engine

import android.system.Os
import io.github.kasaco223.kdownloader.core.engine.ZipModes
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Descomprime los paquetes del motor (lib<nombre>.zip.so de nativeLibraryDir) en kd/payload/<nombre>/
 * (diseño del motor §2). Hace lo mismo que ZipUtils.unzip de youtubedl-android (enlaces relativos, control de
 * rutas, sin chmod) y agrega progreso, verificación y un cambio atómico: primero en <nombre>.new, después la
 * marca .kd-installed y recién entonces el nombre definitivo. Una instalación interrumpida no deja marca y se
 * rehace desde cero.
 */
class PayloadInstaller(private val dirs: EngineDirs, private val versionCode: Long) {
    private val zipCache = HashMap<String, ZipModes.Listing>()

    fun zipFile(name: String) = dirs.lib("lib$name.zip.so")

    /** Cambia con cada versión de la app o si el paquete es otro. */
    fun key(name: String) = "$versionCode:${zipFile(name).length()}"

    fun isInstalled(name: String): Boolean =
        runCatching { File(dirs.payloadDir(name), MARKER).readText() == key(name) }.getOrDefault(false)

    /** Bytes que ocupa el paquete descomprimido (del directorio central, sin leer el contenido). */
    fun installedSize(name: String): Long = listing(name).regularBytes

    private fun listing(name: String): ZipModes.Listing =
        zipCache.getOrPut(key(name)) { ZipModes.read(zipFile(name)) }

    /** [onBytes] recibe los bytes escritos desde la última llamada. */
    fun install(name: String, onBytes: (Long) -> Unit) {
        val zip = zipFile(name)
        val target = dirs.payloadDir(name)
        val staging = File(dirs.payload, "$name.new")
        // Primero se borra la copia vieja: el pico de espacio es el tamaño nuevo, no el doble.
        target.deleteRecursively()
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("No se pudo crear ${staging.path}")
        val base = staging.canonicalPath + File.separator
        val modes = listing(name)
        var files = 0
        var bytes = 0L
        val buffer = ByteArray(64 * 1024)
        ZipFile(zip).use { archive ->
            for (entry in archive.entries()) {
                val dst = File(staging, entry.name)
                val canonical = dst.canonicalPath
                if (!canonical.startsWith(base) && canonical + File.separator != base) {
                    throw IOException("Ruta fuera del paquete: ${entry.name}")
                }
                when (modes.entries[entry.name]?.kind ?: if (entry.isDirectory) ZipModes.Kind.DIR else ZipModes.Kind.FILE) {
                    ZipModes.Kind.DIR -> dst.mkdirs()
                    ZipModes.Kind.SYMLINK -> {
                        if (entry.name in SHADOW) continue
                        val raw = archive.getInputStream(entry).use { it.readBytes() }
                        val link = ZipModes.safeLinkTarget(raw) ?: throw IOException("Enlace inválido: ${entry.name}")
                        dst.parentFile?.mkdirs()
                        Os.symlink(link, dst.path)
                    }
                    ZipModes.Kind.FILE -> {
                        dst.parentFile?.mkdirs()
                        archive.getInputStream(entry).use { input ->
                            FileOutputStream(dst).use { out ->
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    out.write(buffer, 0, n)
                                    bytes += n
                                    onBytes(n.toLong())
                                }
                            }
                        }
                        files++
                    }
                }
            }
        }
        if (files != modes.regularFiles || bytes != modes.regularBytes) {
            throw IOException("$name: se escribieron $files archivos y $bytes bytes; el paquete dice ${modes.regularFiles} y ${modes.regularBytes}")
        }
        FileOutputStream(File(staging, MARKER)).use { out ->
            out.write(key(name).toByteArray())
            out.fd.sync()
        }
        if (!staging.renameTo(target)) throw IOException("No se pudo renombrar ${staging.path}")
    }

    /** Borra las marcas: el próximo ensureReady() vuelve a descomprimir («Reparar»). */
    fun invalidate() {
        for (name in NAMES) File(dirs.payloadDir(name), MARKER).delete()
    }

    companion object {
        val NAMES = listOf("python", "ffmpeg")
        const val MARKER = ".kd-installed"

        /**
         * Enlaces sin versión que no se crean (youtubedl-android #318): ninguna biblioteca del paquete los usa,
         * pero con LD_LIBRARY_PATH taparían a las del sistema (libunwindstack espera la liblzma de Android,
         * toybox la libcrypto del sistema, libmediandk la libz del sistema…).
         */
        val SHADOW = setOf("liblzma", "libz", "libcrypto", "libssl", "libexpat", "libbz2", "libxml2", "libvpx", "libpng")
            .map { "usr/lib/$it.so" }.toSet()
    }
}
