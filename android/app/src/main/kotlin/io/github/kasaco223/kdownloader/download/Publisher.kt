// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Pasa el archivo terminado de la carpeta privada del trabajo a Descargas/K-Snag (diseño del motor §11):
 * - API 29+: MediaStore, con IS_PENDING mientras copia, para que ninguna galería vea un archivo a medias;
 * - API 26-28: copia directa (necesita WRITE_EXTERNAL_STORAGE), escaneo y un URI de FileProvider.
 * La carpeta privada se borra solo después de publicar.
 */
class Publisher(private val context: Context) {
    data class Published(val uri: String, val displayName: String, val mime: String, val size: Long)

    class Canceled : Exception("cancelado")

    /** [onPending] guarda el URI pendiente enseguida (si el proceso muere, el reintento lo borra primero). */
    fun publish(
        file: File,
        jobId: String,
        previousPending: String?,
        isCanceled: () -> Boolean,
        onPending: (String) -> Unit,
    ): Published {
        val mime = mimeFor(file.name)
        return if (Build.VERSION.SDK_INT >= 29) {
            publishMediaStore(file, jobId, mime, previousPending, isCanceled, onPending)
        } else {
            publishLegacy(file, jobId, mime, isCanceled)
        }
    }

    @RequiresApi(29)
    private fun publishMediaStore(
        file: File,
        jobId: String,
        mime: String,
        previousPending: String?,
        isCanceled: () -> Boolean,
        onPending: (String) -> Unit,
    ): Published {
        val resolver = context.contentResolver
        previousPending?.let { runCatching { resolver.delete(Uri.parse(it), null, null) } }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        fun insert(name: String): Uri? = resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        )
        // Con más de 31 archivos del mismo nombre, MediaProvider ya no renombra y el insert falla.
        val uri = try {
            insert(file.name)
        } catch (e: IllegalStateException) {
            insert(withJobId(file.name, jobId))
        } ?: throw IOException("MediaStore no creó la fila")
        onPending(uri.toString())
        try {
            // "wt": en Android 10, "w" no trunca un archivo existente.
            resolver.openOutputStream(uri, "wt")?.use { out -> copy(file.inputStream(), out, isCanceled) }
                ?: throw IOException("No se pudo abrir la fila de MediaStore")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        val name = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: file.name
        return Published(uri.toString(), name, mime, file.length())
    }

    @Suppress("DEPRECATION") // API 26-28: no hay otra forma de escribir en Descargas
    private fun publishLegacy(file: File, jobId: String, mime: String, isCanceled: () -> Boolean): Published {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("No se pudo crear ${dir.path}")
        val target = uniqueName(dir, file.name, jobId)
        try {
            target.outputStream().use { out -> copy(file.inputStream(), out, isCanceled) }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        val latch = CountDownLatch(1)
        MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf(mime)) { _, _ -> latch.countDown() }
        latch.await(10, TimeUnit.SECONDS)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", target)
        return Published(uri.toString(), target.name, mime, target.length())
    }

    private fun copy(input: InputStream, out: OutputStream, isCanceled: () -> Boolean) {
        input.use {
            val buf = ByteArray(1 shl 20)
            var sinceCheck = 0L
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                sinceCheck += n
                if (sinceCheck >= CHUNK) {
                    sinceCheck = 0
                    if (isCanceled()) throw Canceled()
                }
            }
            out.flush()
            if (out is java.io.FileOutputStream) out.fd.sync()
        }
    }

    companion object {
        const val FOLDER = "K-Snag"
        private const val CHUNK = 8L shl 20

        /** El MIME del archivo real, con los de respaldo del diseño para lo que MimeTypeMap no conoce. */
        fun mimeFor(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
                "mp4" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "opus" -> "audio/ogg"
                else -> "application/octet-stream"
            }
        }

        fun withJobId(name: String, jobId: String): String {
            val dot = name.lastIndexOf('.')
            return if (dot > 0) "${name.substring(0, dot)} [$jobId]${name.substring(dot)}" else "$name [$jobId]"
        }

        /** Igual que MediaProvider: `nombre (1).ext`… hasta 31; después, con el id del trabajo. */
        fun uniqueName(dir: File, name: String, jobId: String): File {
            var candidate = File(dir, name)
            if (!candidate.exists()) return candidate
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            for (n in 1..31) {
                candidate = File(dir, "$stem ($n)$ext")
                if (!candidate.exists()) return candidate
            }
            return File(dir, withJobId(name, jobId))
        }
    }
}
