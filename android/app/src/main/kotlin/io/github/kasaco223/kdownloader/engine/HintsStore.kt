// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.engine

import android.util.AtomicFile
import android.util.Log
import io.github.kasaco223.kdownloader.core.download.JsonStrict
import io.github.kasaco223.kdownloader.core.engine.UpdateParsing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Las pistas remotas del motor (engine.json del repo, las mismas que lee Windows; diseño del motor §5): el mínimo
 * de yt-dlp por canal, el cliente de YouTube para un arreglo de emergencia y un aviso. Se bajan una vez al día
 * (15 s, 64 000 bytes como mucho) y quedan en kd/hints.json; si falla, siguen las anteriores.
 */
class HintsStore(private val file: File, private val engine: KdEngine) {
    private val _hints = MutableStateFlow(load())
    val hints: StateFlow<UpdateParsing.Hints> = _hints.asStateFlow()

    init {
        engine.hints = _hints.value
    }

    private fun load(): UpdateParsing.Hints = runCatching {
        val o = JSONObject(String(AtomicFile(file).readFully(), Charsets.UTF_8))
        UpdateParsing.hints(JsonStrict.parse(o.getJSONObject("data").toString()))
    }.getOrDefault(UpdateParsing.Hints())

    private fun fetchedAt(): Long = runCatching { JSONObject(String(AtomicFile(file).readFully(), Charsets.UTF_8)).optLong("fetched_at") }.getOrDefault(0L)

    /** Si pasaron 24 h, las baja otra vez. Se llama en segundo plano al abrir la app. */
    fun refreshIfDue() {
        if (System.currentTimeMillis() - fetchedAt() < 24 * 3_600_000L) return
        val text = try {
            fetch()
        } catch (e: IOException) {
            Log.i(TAG, "Sin pistas nuevas: $e")
            return
        }
        val raw = runCatching { JsonStrict.parse(text) }.getOrNull() ?: return
        val parsed = UpdateParsing.hints(raw)
        _hints.value = parsed
        engine.hints = parsed
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(JSONObject().put("fetched_at", System.currentTimeMillis()).put("data", JSONObject(text)).toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
        }
    }

    private fun fetch(): String {
        val conn = URL(URL_HINTS).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", EngineUpdater.USER_AGENT)
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val bytes = conn.inputStream.use { it.readBytes().also { b -> if (b.size > MAX_BYTES) throw IOException("engine.json muy grande") } }
            return String(bytes, Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "HintsStore"
        private const val MAX_BYTES = 64_000
        const val URL_HINTS = "https://raw.githubusercontent.com/Kasaco223/K-Snag/master/engine.json"
    }
}
