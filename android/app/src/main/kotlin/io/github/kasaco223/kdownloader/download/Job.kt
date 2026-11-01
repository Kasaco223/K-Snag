// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.download

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Error de un trabajo, con los textos en los dos idiomas (la pantalla elige). */
data class JobError(
    val code: String,
    val titleEs: String,
    val messageEs: String,
    val titleEn: String,
    val messageEn: String,
    val details: String,
) {
    fun title(lang: String) = if (lang == "es") titleEs else titleEn

    fun message(lang: String) = if (lang == "es") messageEs else messageEn
}

/**
 * Un trabajo de descarga (diseño del motor §11, versión de esta etapa). Estados: queued, downloading,
 * processing, done, error, canceled e interrupted (el proceso se cerró a mitad: «Reintentar» lo retoma).
 */
data class Job(
    val id: String,
    val url: String,
    val option: String,
    val container: String,
    val isYoutube: Boolean,
    val site: String,
    val videoKey: String? = null,
    val title: String? = null,
    val uploader: String? = null,
    val thumbnail: String? = null,
    val duration: Long? = null,
    val state: String = "queued",
    val stepIndex: Int = 0,
    val stepTotal: Int = 0,
    val stepKind: String? = null,
    val postprocessor: String? = null,
    val progress: Double? = null,
    val downloaded: Long = 0,
    val total: Long = 0,
    val speed: Double? = null,
    val eta: Long? = null,
    val expectedSize: Long? = null,
    val displayName: String? = null,
    val contentUri: String? = null,
    val mime: String? = null,
    val pendingUri: String? = null,
    val error: JobError? = null,
    val notes: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val attempts: Int = 0,
    val forceBest: Boolean = false,
) {
    val isActive: Boolean get() = state == "downloading" || state == "processing"
    val isTerminal: Boolean get() = state == "done" || state == "error" || state == "canceled"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("url", url); put("option", option); put("container", container)
        put("is_youtube", isYoutube); put("site", site); putOpt("video_key", videoKey)
        putOpt("title", title); putOpt("uploader", uploader); putOpt("thumbnail", thumbnail)
        putOpt("duration", duration)
        put("state", state); put("step_index", stepIndex); put("step_total", stepTotal); putOpt("step_kind", stepKind)
        putOpt("postprocessor", postprocessor); putOpt("progress", progress)
        put("downloaded", downloaded); put("total", total); putOpt("expected_size", expectedSize)
        putOpt("display_name", displayName); putOpt("content_uri", contentUri); putOpt("mime", mime)
        putOpt("pending_uri", pendingUri)
        error?.let {
            put("error", JSONObject().put("code", it.code).put("title_es", it.titleEs).put("message_es", it.messageEs)
                .put("title_en", it.titleEn).put("message_en", it.messageEn).put("details", it.details))
        }
        put("notes", JSONArray(notes)); put("created_at", createdAt); putOpt("finished_at", finishedAt)
        put("attempts", attempts); put("force_best", forceBest)
    }

    companion object {
        private fun JSONObject.optStr(k: String): String? = if (isNull(k)) null else optString(k).takeIf { has(k) }

        fun fromJson(o: JSONObject): Job = Job(
            id = o.getString("id"), url = o.getString("url"), option = o.getString("option"),
            container = o.optString("container", "mp4"), isYoutube = o.optBoolean("is_youtube"),
            site = o.optString("site"), videoKey = o.optStr("video_key"),
            title = o.optStr("title"), uploader = o.optStr("uploader"), thumbnail = o.optStr("thumbnail"),
            duration = if (o.has("duration") && !o.isNull("duration")) o.optLong("duration") else null,
            state = o.optString("state", "queued"), stepIndex = o.optInt("step_index"), stepTotal = o.optInt("step_total"),
            stepKind = o.optStr("step_kind"), postprocessor = o.optStr("postprocessor"),
            progress = if (o.isNull("progress") || !o.has("progress")) null else o.optDouble("progress"),
            downloaded = o.optLong("downloaded"), total = o.optLong("total"),
            expectedSize = if (o.has("expected_size") && !o.isNull("expected_size")) o.optLong("expected_size") else null,
            displayName = o.optStr("display_name"), contentUri = o.optStr("content_uri"), mime = o.optStr("mime"),
            pendingUri = o.optStr("pending_uri"),
            error = o.optJSONObject("error")?.let {
                JobError(it.optString("code"), it.optString("title_es"), it.optString("message_es"),
                    it.optString("title_en"), it.optString("message_en"), it.optString("details"))
            },
            notes = o.optJSONArray("notes")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            createdAt = o.optLong("created_at"), finishedAt = if (o.has("finished_at") && !o.isNull("finished_at")) o.optLong("finished_at") else null,
            attempts = o.optInt("attempts"), forceBest = o.optBoolean("force_best"),
        )
    }
}

/** Una descarga terminada, para el historial (máximo 500, la más nueva primero). */
data class HistoryItem(
    val id: String,
    val url: String,
    val title: String,
    val site: String,
    val videoKey: String?,
    val option: String,
    val displayName: String,
    val contentUri: String,
    val mime: String,
    val size: Long,
    val finishedAt: Long,
    val uploader: String? = null,
    val duration: Long? = null,
) {
    val isAudio: Boolean get() = mime.startsWith("audio/")

    fun toJson(): JSONObject = JSONObject().put("id", id).put("url", url).put("title", title).put("site", site)
        .putOpt("video_key", videoKey).put("option", option).put("display_name", displayName)
        .put("content_uri", contentUri).put("mime", mime).put("size", size).put("finished_at", finishedAt)
        .putOpt("uploader", uploader).putOpt("duration", duration)

    companion object {
        fun fromJson(o: JSONObject) = HistoryItem(
            o.getString("id"), o.getString("url"), o.optString("title"), o.optString("site"),
            if (o.isNull("video_key")) null else o.optString("video_key").takeIf { o.has("video_key") },
            o.optString("option"), o.optString("display_name"), o.getString("content_uri"), o.optString("mime"),
            o.optLong("size"), o.optLong("finished_at"),
            uploader = if (o.isNull("uploader")) null else o.optString("uploader").takeIf { o.has("uploader") },
            duration = if (o.has("duration") && !o.isNull("duration")) o.optLong("duration") else null,
        )
    }
}

/** Lectura y escritura atómicas de jobs.json / history.json (`{"schema":2,"items":[…]}`). */
internal object JsonFiles {
    fun <T> load(file: File, key: String, parse: (JSONObject) -> T): List<T> = runCatching {
        val root = JSONObject(String(AtomicFile(file).readFully(), Charsets.UTF_8))
        val arr = root.getJSONArray(key)
        (0 until arr.length()).mapNotNull { runCatching { parse(arr.getJSONObject(it)) }.getOrNull() }
    }.getOrDefault(emptyList())

    fun <T> save(file: File, key: String, items: List<T>, toJson: (T) -> JSONObject) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            val root = JSONObject().put("schema", 2).put(key, JSONArray(items.map(toJson)))
            out.write(root.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }
}
