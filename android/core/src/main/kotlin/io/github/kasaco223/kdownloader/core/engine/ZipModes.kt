// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.core.engine

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lee el directorio central de un zip (los paquetes lib*.zip.so del motor) para saber qué es cada entrada:
 * `java.util.zip` no expone los permisos Unix (`external attributes >>> 16`), y sin ellos no se distingue un
 * enlace simbólico de un archivo. No lee el contenido; los paquetes no usan zip64 (pesan menos de 4 GB).
 */
object ZipModes {
    enum class Kind { FILE, DIR, SYMLINK }

    data class Entry(val name: String, val mode: Int, val size: Long) {
        val kind: Kind
            get() = when (mode and S_IFMT) {
                S_IFLNK -> Kind.SYMLINK
                S_IFDIR -> Kind.DIR
                else -> if (name.endsWith("/")) Kind.DIR else Kind.FILE
            }
    }

    data class Listing(val entries: Map<String, Entry>) {
        val regularFiles: Int get() = entries.values.count { it.kind == Kind.FILE }
        val regularBytes: Long get() = entries.values.filter { it.kind == Kind.FILE }.sumOf { it.size }
    }

    private const val S_IFMT = 0xF000
    private const val S_IFLNK = 0xA000
    private const val S_IFDIR = 0x4000
    private const val EOCD = 0x06054b50
    private const val CEN = 0x02014b50

    fun read(file: File): Listing = RandomAccessFile(file, "r").use { raf ->
        val length = raf.length()
        val tailSize = minOf(length, 22L + 0xFFFF).toInt()
        val tail = ByteArray(tailSize).also { raf.seek(length - tailSize); raf.readFully(it) }
        val buf = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        val eocd = (tailSize - 22 downTo 0).firstOrNull { buf.getInt(it) == EOCD }
            ?: throw IOException("${file.name}: no es un zip (falta el fin del directorio central)")
        val count = buf.getShort(eocd + 10).toInt() and 0xFFFF
        val cdSize = buf.getInt(eocd + 12).toLong() and 0xFFFFFFFFL
        val cdOffset = buf.getInt(eocd + 16).toLong() and 0xFFFFFFFFL
        if (count == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            throw IOException("${file.name}: zip64 no soportado")
        }
        if (cdOffset + cdSize > length) throw IOException("${file.name}: directorio central fuera del archivo")
        val cd = ByteArray(cdSize.toInt()).also { raf.seek(cdOffset); raf.readFully(it) }
        parse(ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN), count, file.name)
    }

    private fun parse(cd: ByteBuffer, count: Int, label: String): Listing {
        val entries = LinkedHashMap<String, Entry>(count)
        var pos = 0
        repeat(count) {
            if (pos + 46 > cd.limit() || cd.getInt(pos) != CEN) throw IOException("$label: directorio central dañado")
            val usize = cd.getInt(pos + 24).toLong() and 0xFFFFFFFFL
            val nameLen = cd.getShort(pos + 28).toInt() and 0xFFFF
            val extraLen = cd.getShort(pos + 30).toInt() and 0xFFFF
            val commentLen = cd.getShort(pos + 32).toInt() and 0xFFFF
            val attrs = cd.getInt(pos + 38)
            if (pos + 46 + nameLen > cd.limit()) throw IOException("$label: nombre fuera del directorio central")
            val name = String(cd.array(), pos + 46, nameLen, Charsets.UTF_8)
            entries[name] = Entry(name, attrs ushr 16, usize)
            pos += 46 + nameLen + extraLen + commentLen
        }
        return Listing(entries)
    }

    /**
     * Destino de un enlace del paquete, validado: relativo, sin `..` ni bytes raros. Los paquetes de 0.18.1
     * solo traen enlaces del tipo `libz.so -> libz.so.1.3.1`.
     */
    fun safeLinkTarget(raw: ByteArray): String? {
        val target = String(raw, Charsets.UTF_8)
        val ok = target.isNotEmpty() && target.length <= 255 && !target.startsWith("/") &&
            target.split('/').none { it == ".." || it.isEmpty() } && target.none { it.code < 0x20 }
        return target.takeIf { ok }
    }
}
