// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.ui.engine

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.kasaco223.kdownloader.R
import io.github.kasaco223.kdownloader.contracts.EnginePhase
import io.github.kasaco223.kdownloader.contracts.EngineStatus
import io.github.kasaco223.kdownloader.ui.components.KdCard
import io.github.kasaco223.kdownloader.ui.components.SectionTitle

/**
 * Estado del motor de descargas. En Inicio ([detailed] = false) solo dice si está listo; en «Acerca de» muestra
 * las versiones y «Reparar» ([onRepair]).
 */
@Composable
fun EngineCard(
    status: EngineStatus,
    onRetry: () -> Unit,
    detailed: Boolean = false,
    repairing: Boolean = false,
    onRepair: (() -> Unit)? = null,
) {
    KdCard {
        SectionTitle(stringResource(R.string.engine_title))
        when (status) {
            EngineStatus.NotReady -> Preparing(stringResource(R.string.engine_phase_check), null)
            is EngineStatus.Preparing -> Preparing(stringResource(phaseText(status.phase)), status.progress)
            is EngineStatus.Ready -> {
                Text(stringResource(R.string.engine_ready), style = MaterialTheme.typography.bodyMedium)
                if (detailed) {
                    val v = status.versions
                    Text(
                        text = stringResource(R.string.engine_versions, v.ytdlp, v.ytdlpChannel, v.ffmpeg, v.python, v.quickjs, v.youtubedlAndroid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            is EngineStatus.Failed -> {
                Text(
                    text = stringResource(errorTitle(status.error.code)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                val body = if (status.error.code == "disk_full" && status.requiredFreeBytes != null) {
                    stringResource(R.string.engine_error_disk_full_body, Formatter.formatShortFileSize(LocalContext.current, status.requiredFreeBytes))
                } else {
                    stringResource(errorBody(status.error.code))
                }
                Text(body, style = MaterialTheme.typography.bodyMedium)
                if (status.error.code != "engine_incompatible") {
                    FilledTonalButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.engine_retry))
                    }
                }
            }
        }
        if (onRepair != null && status !is EngineStatus.Preparing && status != EngineStatus.NotReady) {
            OutlinedButton(onClick = onRepair, enabled = !repairing, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (repairing) R.string.engine_repairing else R.string.engine_repair))
            }
            Text(
                text = stringResource(R.string.engine_repair_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Preparing(text: String, progress: Float?) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    if (progress != null) {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    Text(
        text = stringResource(R.string.engine_preparing_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@StringRes
private fun phaseText(phase: EnginePhase): Int = when (phase) {
    EnginePhase.CHECK_SPACE -> R.string.engine_phase_check
    EnginePhase.UNPACK -> R.string.engine_phase_unpack
    EnginePhase.FINISH -> R.string.engine_phase_finish
    EnginePhase.SELF_TEST -> R.string.engine_phase_selftest
}

@StringRes
private fun errorTitle(code: String): Int = when (code) {
    "engine_incompatible" -> R.string.engine_error_incompatible_title
    "disk_full" -> R.string.engine_error_disk_full_title
    else -> R.string.engine_error_failed_title
}

@StringRes
private fun errorBody(code: String): Int = when (code) {
    "engine_incompatible" -> R.string.engine_error_incompatible_body
    "disk_full" -> R.string.engine_error_disk_full_generic
    "timeout" -> R.string.engine_error_timeout_body
    else -> R.string.engine_error_failed_body
}
