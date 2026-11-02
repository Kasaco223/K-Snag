// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kasaco223.kdownloader.R
import io.github.kasaco223.kdownloader.contracts.EngineStatus
import io.github.kasaco223.kdownloader.engine.KdEngine.UpdateState
import io.github.kasaco223.kdownloader.platform.UiPrefs
import io.github.kasaco223.kdownloader.ui.components.KdCard
import io.github.kasaco223.kdownloader.ui.components.SectionTitle

/** Un interruptor con su texto; toda la fila es tocable. */
@Composable
fun SwitchRow(checked: Boolean, title: String, detail: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * Ajustes › Motor: la versión de yt-dlp, actualizarlo solo (o ahora), el canal y volver a la versión anterior si
 * una nueva falla.
 */
@Composable
fun EngineSettingsCard(
    prefs: UiPrefs,
    engineStatus: EngineStatus,
    update: UpdateState,
    onCheckNow: () -> Unit,
    onRollback: () -> Unit,
) {
    val auto by prefs.autoUpdateEngine.collectAsStateWithLifecycle()
    val channel by prefs.engineChannel.collectAsStateWithLifecycle()
    val versions = (engineStatus as? EngineStatus.Ready)?.versions
    KdCard {
        SectionTitle(stringResource(R.string.engine_settings_title))
        if (versions != null) {
            Text(stringResource(R.string.engine_settings_version, versions.ytdlp, versions.ytdlpChannel), style = MaterialTheme.typography.bodyMedium)
        }
        SwitchRow(auto, stringResource(R.string.engine_settings_auto), stringResource(R.string.engine_settings_auto_detail), prefs::setAutoUpdateEngine)
        Column(Modifier.selectableGroup()) {
            RadioRow(channel == "nightly", stringResource(R.string.engine_channel_nightly), { prefs.setEngineChannel("nightly") }, stringResource(R.string.engine_channel_nightly_detail))
            RadioRow(channel == "stable", stringResource(R.string.engine_channel_stable), { prefs.setEngineChannel("stable") }, stringResource(R.string.engine_channel_stable_detail))
        }
        val message = when (update) {
            UpdateState.Idle -> null
            UpdateState.Checking -> stringResource(R.string.engine_update_checking)
            is UpdateState.UpToDate -> stringResource(R.string.engine_update_uptodate, update.version)
            is UpdateState.Updated -> stringResource(R.string.engine_update_done, update.version)
            is UpdateState.RolledBack -> stringResource(R.string.engine_update_rolled_back, update.version)
            UpdateState.Busy -> stringResource(R.string.engine_update_busy)
            is UpdateState.Failed -> stringResource(R.string.engine_update_failed)
        }
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (update is UpdateState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        FilledTonalButton(onClick = onCheckNow, enabled = versions != null && update != UpdateState.Checking, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.engine_update_now))
        }
        if (versions?.ytdlpPrevious != null) {
            OutlinedButton(onClick = onRollback, enabled = update != UpdateState.Checking, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.engine_rollback, versions.ytdlpPrevious))
            }
        }
    }
}
