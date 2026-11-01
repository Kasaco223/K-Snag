// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.ui.jobs

import android.content.res.Resources
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kasaco223.kdownloader.R
import io.github.kasaco223.kdownloader.core.download.StageLabeler
import io.github.kasaco223.kdownloader.download.HistoryItem
import io.github.kasaco223.kdownloader.download.Job
import io.github.kasaco223.kdownloader.ui.components.BrandProgressBar
import io.github.kasaco223.kdownloader.ui.components.DurationBadge
import io.github.kasaco223.kdownloader.ui.components.OverlayBadge
import io.github.kasaco223.kdownloader.ui.components.Thumb
import io.github.kasaco223.kdownloader.ui.formats.fallbackIcon

/** El texto de una etapa (las claves de StageLabeler), igual en la fila y en la notificación. */
fun stageText(res: Resources, stage: StageLabeler.Stage): String {
    val id = when (stage.key) {
        "jobs_state_queued" -> R.string.jobs_state_queued
        "jobs_step_video" -> R.string.jobs_step_video
        "jobs_step_audio" -> R.string.jobs_step_audio
        "jobs_step_av" -> R.string.jobs_step_av
        "jobs_pp_merger" -> R.string.jobs_pp_merger
        "jobs_pp_extract_audio" -> R.string.jobs_pp_extract_audio
        "jobs_pp_extract_audio_m4a" -> R.string.jobs_pp_extract_audio_m4a
        "jobs_pp_embed_thumbnail" -> R.string.jobs_pp_embed_thumbnail
        "jobs_pp_metadata" -> R.string.jobs_pp_metadata
        "jobs_pp_publish" -> R.string.jobs_pp_publish
        "jobs_pp_other" -> R.string.jobs_pp_other
        "jobs_state_done" -> R.string.jobs_state_done
        "jobs_state_error" -> R.string.jobs_state_error
        "jobs_state_canceled" -> R.string.jobs_state_canceled
        else -> R.string.jobs_state_queued
    }
    val text = res.getString(id)
    return if (stage.stepIndex != null && stage.stepTotal != null) {
        res.getString(R.string.jobs_step_n_of, stage.stepIndex, stage.stepTotal, text)
    } else {
        text
    }
}

fun optionLabel(res: Resources, option: String): String = res.getString(
    when (option) {
        "recommended" -> R.string.option_recommended
        "max" -> R.string.option_max
        "mp3" -> R.string.option_mp3
        "m4a" -> R.string.option_m4a
        else -> R.string.option_recommended
    },
)

/** "es" o "en" según el idioma de la interfaz (observable: cambia con el idioma de la app). */
@Composable
private fun uiLang(): String = if (LocalConfiguration.current.locales[0].language == "es") "es" else "en"

private val Job.isRunning: Boolean get() = state == "queued" || state == "downloading" || state == "processing"

/** Etapa, velocidad y tiempo que falta de un trabajo, en una línea. */
@Composable
private fun jobStatusLine(job: Job): String {
    val context = LocalContext.current
    val res = LocalResources.current
    val lang = uiLang()
    val stage = StageLabeler.label(job.state, job.stepKind, job.stepIndex, job.stepTotal, job.postprocessor, job.option)
    return buildList {
        when (job.state) {
            "interrupted" -> add(res.getString(R.string.jobs_state_interrupted))
            "error" -> add(job.error?.title(lang) ?: res.getString(R.string.jobs_state_error))
            else -> add(stageText(res, stage))
        }
        if (job.state == "downloading") {
            job.speed?.takeIf { it > 0 }?.let { add(Formatter.formatShortFileSize(context, it.toLong()) + "/s") }
            job.eta?.takeIf { it > 0 }?.let { add(res.getString(R.string.jobs_eta, DateUtils.formatElapsedTime(it))) }
        }
    }.joinToString(" · ")
}

/** Porcentaje de lo que se está bajando; null mientras no se sabe (en cola, uniendo, convirtiendo). */
private fun Job.visibleProgress(): Float? = if (state == "downloading") progress?.toFloat() else null

/** La descarga en curso, grande, con su miniatura: lo primero que se ve en Inicio. */
@Composable
fun ActiveJobCard(job: Job, onCancel: (String) -> Unit, modifier: Modifier = Modifier) {
    val res = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val p = job.visibleProgress()
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Thumb(
            id = job.id,
            fallback = fallbackIcon(job.option),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            corner = 0.dp,
            iconSize = 40.dp,
        ) {
            OverlayBadge(optionLabel(res, job.option), Modifier.align(Alignment.TopStart).padding(10.dp), accent = true)
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color(0xD9101218)),
                contentAlignment = Alignment.Center,
            ) {
                if (p != null) {
                    CircularProgressIndicator(progress = { p }, modifier = Modifier.size(48.dp), strokeWidth = 4.dp, trackColor = Color.White.copy(alpha = 0.18f))
                    Text(String.format(locale, "%d%%", (p * 100).toInt()), color = Color.White, style = MaterialTheme.typography.labelMedium)
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(40.dp), strokeWidth = 4.dp, trackColor = Color.White.copy(alpha = 0.18f))
                }
            }
        }
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = job.title ?: job.url,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = jobStatusLine(job),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onCancel(job.id) }) {
                    Text(stringResource(R.string.jobs_cancel), color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}

/**
 * Un trabajo en una fila: en curso (con barra), con error (mensaje y «Reintentar») o interrumpido. Lo
 * terminado se muestra con [HistoryRow].
 */
@Composable
fun JobRow(job: Job, onCancel: (String) -> Unit, onRetry: (String) -> Unit, onRemove: (String) -> Unit) {
    val lang = uiLang()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Thumb(job.id, fallbackIcon(job.option), Modifier.width(96.dp).aspectRatio(16f / 9f), corner = 10.dp, iconSize = 22.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(job.title ?: job.url, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = jobStatusLine(job),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (job.state == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (job.isRunning) BrandProgressBar(job.visibleProgress())
            if (job.state == "error") {
                job.error?.message(lang)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (job.isRunning) {
                    TextButton(onClick = { onCancel(job.id) }) { Text(stringResource(R.string.jobs_cancel)) }
                } else {
                    TextButton(onClick = { onRemove(job.id) }) { Text(stringResource(R.string.jobs_remove)) }
                    if (job.state != "done") {
                        TextButton(onClick = { onRetry(job.id) }) {
                            Icon(painterResource(R.drawable.ic_retry), contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.jobs_retry), modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Una descarga terminada. Tocarla la abre; la equis la quita del historial (el archivo queda en Descargas). */
@Composable
fun HistoryRow(item: HistoryItem, onOpen: (HistoryItem) -> Unit, onRemove: (String) -> Unit, compact: Boolean = false) {
    val context = LocalContext.current
    val res = LocalResources.current
    val detail = listOf(
        optionLabel(res, item.option),
        Formatter.formatShortFileSize(context, item.size),
        DateUtils.getRelativeTimeSpanString(item.finishedAt).toString(),
    ).joinToString(" · ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.jobs_open)) { onOpen(item) }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(
            id = item.id,
            fallback = fallbackIcon(item.option),
            modifier = Modifier.width(if (compact) 80.dp else 112.dp).aspectRatio(16f / 9f),
            corner = 10.dp,
            iconSize = 22.dp,
        ) {
            if (!compact) DurationBadge(item.duration)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = { onRemove(item.id) }) {
            Icon(
                painterResource(R.drawable.ic_close),
                contentDescription = stringResource(R.string.history_remove_named, item.title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Título de una sección de la lista, con una acción opcional a la derecha. */
@Composable
fun ListHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

