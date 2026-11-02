// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.engine

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Una copia de yt-dlp instalada en kd/engine/ytdlp/<version>/yt-dlp. */
data class YtdlpCopy(
    val version: String,
    val channel: String,
    val sha256: String,
    /** "bundled" (la del APK) o "github" (una actualización, más adelante). */
    val source: String,
    val installedAt: Long,
)

data class SelfTestRecord(val key: String, val ok: Boolean, val caCount: Int, val at: Long)

/**
 * kd/engine/state.json (diseño del motor §4). Se escribe entero con AtomicFile: un corte de energía deja el
 * archivo viejo o el nuevo, nunca uno a medias. Un archivo ilegible cuenta como vacío (el motor se rehace).
 */
data class EngineState(
    val active: YtdlpCopy? = null,
    val previous: YtdlpCopy? = null,
    val skipVersions: Set<String> = emptySet(),
    val bundledOfferedVersionCode: Long? = null,
    val selfTest: SelfTestRecord? = null,
    val sessionMode: String? = null,
    /** Última vez que se buscó una versión nueva de yt-dlp, y el error de esa búsqueda (null si salió bien). */
    val checkedAt: Long = 0,
    val lastError: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schema", SCHEMA)
        put("active", active?.toJson() ?: JSONObject.NULL)
        put("previous", previous?.toJson() ?: JSONObject.NULL)
        put("skip_versions", JSONArray(skipVersions.sorted()))
        put("bundled_offered_vc", bundledOfferedVersionCode ?: JSONObject.NULL)
        put(
            "selftest",
            selfTest?.let {
                JSONObject().put("key", it.key).put("ok", it.ok).put("ca_count", it.caCount).put("at", it.at)
            } ?: JSONObject.NULL,
        )
        put("session_mode", sessionMode ?: JSONObject.NULL)
        put("checked_at", checkedAt)
        put("last_error", lastError ?: JSONObject.NULL)
    }

    companion object {
        const val SCHEMA = 2

        private fun YtdlpCopy.toJson() = JSONObject()
            .put("version", version).put("channel", channel).put("sha256", sha256)
            .put("source", source).put("installed_at", installedAt)

        private fun JSONObject.copyOrNull(key: String): YtdlpCopy? = optJSONObject(key)?.let {
            YtdlpCopy(it.getString("version"), it.getString("channel"), it.getString("sha256"), it.optString("source", "bundled"), it.optLong("installed_at"))
        }

        fun fromJson(json: JSONObject): EngineState {
            if (json.optInt("schema") != SCHEMA) return EngineState()
            val skip = json.optJSONArray("skip_versions")?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() }.orEmpty()
            val self = json.optJSONObject("selftest")?.let {
                SelfTestRecord(it.getString("key"), it.getBoolean("ok"), it.optInt("ca_count"), it.optLong("at"))
            }
            return EngineState(
                active = json.copyOrNull("active"),
                previous = json.copyOrNull("previous"),
                skipVersions = skip,
                bundledOfferedVersionCode = if (json.isNull("bundled_offered_vc")) null else json.optLong("bundled_offered_vc"),
                selfTest = self,
                sessionMode = if (json.isNull("session_mode")) null else json.optString("session_mode"),
                checkedAt = json.optLong("checked_at"),
                lastError = if (json.isNull("last_error")) null else json.optString("last_error").takeIf { json.has("last_error") },
            )
        }

        fun load(file: File): EngineState = runCatching {
            fromJson(JSONObject(String(AtomicFile(file).readFully(), Charsets.UTF_8)))
        }.getOrDefault(EngineState())

        fun save(file: File, state: EngineState) {
            file.parentFile?.mkdirs()
            val atomic = AtomicFile(file)
            val out = atomic.startWrite()
            try {
                out.write(state.toJson().toString(2).toByteArray(Charsets.UTF_8))
                atomic.finishWrite(out)
            } catch (e: Exception) {
                atomic.failWrite(out)
                throw e
            }
        }
    }
}
