// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.download

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection

/**
 * Miniaturas de las descargas. La imagen que yt-dlp informa en `KDL|meta` se baja una sola vez (solo https, sin
 * cookies, como mucho 4 MB), se achica a [WIDTH] px de ancho y queda en kd/thumbs/<id>.jpg. Es decoración: si
 * falla, la tarjeta muestra el ícono del formato y la descarga sigue igual.
 */
class Thumbnails(private val dir: File, private val scope: CoroutineScope) {
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Sube cada vez que aparece o se borra una miniatura: la interfaz vuelve a leer el archivo. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun file(id: String) = File(dir, "$id.jpg")

    /** Baja la miniatura de [id] en segundo plano, si todavía no la tiene. Se puede llamar muchas veces. */
    fun fetch(id: String, url: String) {
        if (!url.startsWith("https://") || file(id).exists() || !inFlight.add(id)) return
        scope.launch {
            try {
                save(id, decode(read(url)))
                _version.update { it + 1 }
            } catch (e: IOException) {
                Log.i(TAG, "Sin miniatura para $id: $e")
            } catch (e: RuntimeException) {
                Log.i(TAG, "Sin miniatura para $id: $e")
            } finally {
                inFlight.remove(id)
            }
        }
    }

    fun delete(ids: Collection<String>) {
        if (ids.isEmpty()) return
        ids.forEach { file(it).delete() }
        _version.update { it + 1 }
    }

    private fun read(url: String): ByteArray {
        val conn = URL(url).openConnection() as? HttpsURLConnection ?: throw IOException("no es https")
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.useCaches = false
            conn.setRequestProperty("Accept", "image/*")
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) throw IOException("más de 4 MB")
                }
                return out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun decode(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0 || w.toLong() * h > MAX_PIXELS) throw IOException("imagen inválida (${w}x$h)")
        var sample = 1
        while (w / (sample * 2) >= WIDTH) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("no se pudo leer la imagen")
        if (bitmap.width <= WIDTH) return bitmap
        val scaled = Bitmap.createScaledBitmap(bitmap, WIDTH, (bitmap.height.toLong() * WIDTH / bitmap.width).toInt().coerceAtLeast(1), true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun save(id: String, bitmap: Bitmap) {
        dir.mkdirs()
        val tmp = File(dir, "$id.jpg.tmp")
        try {
            tmp.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) throw IOException("no se pudo guardar") }
            if (!tmp.renameTo(file(id))) throw IOException("no se pudo guardar")
        } finally {
            tmp.delete()
            bitmap.recycle()
        }
    }

    companion object {
        private const val TAG = "Thumbnails"
        const val WIDTH = 480
        private const val MAX_BYTES = 4 * 1024 * 1024
        private const val MAX_PIXELS = 40_000_000L
    }
}
