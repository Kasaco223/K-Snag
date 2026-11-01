// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.ui.jobs

import android.content.res.Resources
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kasaco223.kdownloader.R
import io.github.kasaco223.kdownloader.core.download.StageLabeler
import io.github.kasaco223.kdownloader.download.Job
import io.github.kasaco223.kdownloader.ui.components.KdCard
import io.github.kasaco223.kdownloader.ui.components.SectionTitle

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

/** "es" o "en" según el idioma de la interfaz (observable: cambia con el idioma de la app). */
@Composable
private fun uiLang(): String = if (LocalConfiguration.current.locales[0].language == "es") "es" else "en"

/** La lista de descargas de Inicio. */
@Composable
fun JobsSection(
    jobs: List<Job>,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onOpen: (Job) -> Unit,
    onRemove: (String) -> Unit,
    onClearFinished: () -> Unit,
) {
    if (jobs.isEmpty()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        SectionTitle(stringResource(R.string.jobs_title))
        if (jobs.any { it.isTerminal }) {
            TextButton(onClick = onClearFinished) { Text(stringResource(R.string.jobs_clear_finished)) }
        }
    }
    for (job in jobs.sortedByDescending { it.createdAt }) {
        JobRow(job, onCancel, onRetry, onOpen, onRemove)
    }
}

@Composable
private fun JobRow(job: Job, onCancel: (String) -> Unit, onRetry: (String) -> Unit, onOpen: (Job) -> Unit, onRemove: (String) -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val lang = uiLang()
    val locale = LocalConfiguration.current.locales[0]
    KdCard {
        Text(
            text = job.title ?: job.displayName ?: job.url,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val stage = StageLabeler.label(job.state, job.stepKind, job.stepIndex, job.stepTotal, job.postprocessor, job.option)
        val detail = buildList {
            add(optionLabel(res, job.option))
            when (job.state) {
                "interrupted" -> add(res.getString(R.string.jobs_state_interrupted))
                "error" -> add(job.error?.title(lang) ?: res.getString(R.string.jobs_state_error))
                else -> add(stageText(res, stage))
            }
            if (job.state == "downloading") {
                job.progress?.let { add(String.format(locale, "%d %%", (it * 100).toInt())) }
                job.speed?.takeIf { it > 0 }?.let { add(Formatter.formatShortFileSize(context, it.toLong()) + "/s") }
            }
            if (job.state == "done" && job.total > 0) add(Formatter.formatShortFileSize(context, job.total))
        }
        Text(detail.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (job.state) {
            "downloading", "processing" -> {
                val p = job.progress
                if (p != null && job.state == "downloading") {
                    LinearProgressIndicator(progress = { p.toFloat() }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            "queued" -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (job.state == "error") {
            job.error?.message(lang)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (job.state) {
                "queued", "downloading", "processing" -> TextButton(onClick = { onCancel(job.id) }) { Text(stringResource(R.string.jobs_cancel)) }
                "done" -> {
                    if (job.contentUri != null) TextButton(onClick = { onOpen(job) }) { Text(stringResource(R.string.jobs_open)) }
                    TextButton(onClick = { onRemove(job.id) }) { Text(stringResource(R.string.jobs_remove)) }
                }
                else -> {
                    TextButton(onClick = { onRetry(job.id) }) { Text(stringResource(R.string.jobs_retry)) }
                    TextButton(onClick = { onRemove(job.id) }) { Text(stringResource(R.string.jobs_remove)) }
                }
            }
        }
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
