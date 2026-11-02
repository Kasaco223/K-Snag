// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.core.download

/**
 * Progreso de un trabajo a partir de los eventos KDL (port exacto de `downloads._on_event` de Windows):
 * - pasos: 1 para mp3/m4a o un formato con audio y video juntos; si no, 2 (video y audio);
 * - fracción: 1 al terminar; si no, bytes ÷ total; si no, fragmentos; si no, desconocida;
 * - pesos [0.9, 0.1] con dos pasos, 1/n con otros; nunca pasa de 0.99 hasta que el archivo está publicado.
 */
class ProgressModel(private val option: String, private val isYoutube: Boolean) {
    var state = "queued"
        private set
    var stepIndex = 0
        private set
    var stepTotal = 0
        private set
    var stepKind: String? = null
        private set
    var postprocessor: String? = null
        private set
    var progress: Double? = null
        private set
    var downloaded = 0L
        private set
    var total = 0L
        private set
    var speed: Double? = null
        private set
    var eta: Long? = null
        private set
    var filePath: String? = null
        private set
    val formatsSeen = mutableListOf<String>()
    val notes = mutableListOf<String>()
    private var slowSince: Long? = null

    /** Datos de `KDL|meta` (Android): título, autor, tamaño esperado. */
    var meta: Map<String, Any?>? = null
        private set

    /** [now] en milisegundos (inyectado: las pruebas reproducen transcripciones sin reloj real). */
    fun onEvent(ev: KdlEvent, now: Long) {
        when (ev) {
            is KdlEvent.Dl -> onDownload(ev, now)
            is KdlEvent.Pp -> if (ev.data.str("status") == "started") {
                state = "processing"
                postprocessor = ev.data.str("postprocessor").orEmpty()
                speed = null
                eta = null
            }
            is KdlEvent.File -> filePath = ev.path
            is KdlEvent.Meta -> meta = ev.data
        }
    }

    private fun onDownload(ev: KdlEvent.Dl, now: Long) {
        val d = ev.data
        val fid = ev.info.str("format_id").orEmpty()
        if (fid.isNotEmpty() && fid !in formatsSeen) formatsSeen += fid
        val kind = Kdl.streamKind(ev.info)
        if (stepTotal == 0) stepTotal = if (option == "mp3" || option == "m4a" || kind == "av") 1 else 2
        stepIndex = formatsSeen.size.coerceIn(1, 9)
        stepTotal = maxOf(stepTotal, stepIndex)
        stepKind = kind
        val totalBytes = d.long("total_bytes")?.takeIf { it > 0 } ?: d.long("total_bytes_estimate") ?: 0L
        val done = d.long("downloaded_bytes") ?: 0L
        val fragCount = d.long("fragment_count")?.takeIf { it > 0 }
        val frac: Double? = when {
            d.str("status") == "finished" -> 1.0
            totalBytes > 0 -> minOf(done.toDouble() / totalBytes, 1.0)
            fragCount != null -> minOf((d.long("fragment_index") ?: 0L).toDouble() / fragCount, 1.0)
            else -> null
        }
        val weights = if (stepTotal == 2) listOf(0.9, 0.1) else List(stepTotal) { 1.0 / stepTotal }
        if (frac != null) {
            val idx = minOf(stepIndex, weights.size)
            progress = minOf(weights.take(idx - 1).sum() + weights[idx - 1] * frac, 0.99)
        }
        downloaded = done
        total = totalBytes
        speed = d.num("speed")
        eta = d.long("eta")
        state = "downloading"
        postprocessor = null
        val s = speed ?: 0.0
        if (isYoutube && d.str("status") == "downloading" && s > 0 && s < SLOW_BYTES_PER_SEC) {
            val since = slowSince ?: now.also { slowSince = it }
            if (now - since > 20_000 && "slow_site" !in notes) notes += "slow_site"
        } else if (s >= SLOW_BYTES_PER_SEC) {
            slowSince = null
        }
    }

    companion object {
        const val SLOW_BYTES_PER_SEC = 150 * 1024
    }
}
