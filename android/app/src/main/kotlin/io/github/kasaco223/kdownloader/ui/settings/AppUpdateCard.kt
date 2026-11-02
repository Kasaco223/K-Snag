// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kasaco223.kdownloader.BuildConfig
import io.github.kasaco223.kdownloader.R
import io.github.kasaco223.kdownloader.platform.SystemActions
import io.github.kasaco223.kdownloader.platform.UiPrefs
import io.github.kasaco223.kdownloader.ui.components.BrandProgressBar
import io.github.kasaco223.kdownloader.ui.components.KdCard
import io.github.kasaco223.kdownloader.ui.components.SectionTitle
import io.github.kasaco223.kdownloader.update.AppUpdater
import io.github.kasaco223.kdownloader.update.AppUpdater.State

/** Ajustes › Actualizaciones de la app: buscar, bajar e instalar la versión nueva (Android pide confirmación). */
@Composable
fun AppUpdateCard(prefs: UiPrefs, updater: AppUpdater) {
    val context = LocalContext.current
    val state by updater.state.collectAsStateWithLifecycle()
    val auto by prefs.autoUpdateApp.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    KdCard {
        SectionTitle(stringResource(R.string.app_update_title))
        Text(stringResource(R.string.app_update_current, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
        if (!updater.supported) {
            Text(stringResource(R.string.app_update_test_build), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@KdCard
        }
        SwitchRow(auto, stringResource(R.string.app_update_auto), null, prefs::setAutoUpdateApp)
        val message = when (val s = state) {
            State.Idle -> null
            State.Checking -> stringResource(R.string.app_update_checking)
            State.UpToDate -> stringResource(R.string.app_update_uptodate)
            is State.Available -> stringResource(if (s.update.required) R.string.app_update_required else R.string.app_update_available, s.update.version)
            is State.Downloading -> stringResource(R.string.app_update_downloading, s.update.version, String.format(locale, "%d", (s.progress * 100).toInt()))
            is State.NeedsPermission -> stringResource(R.string.app_update_permission)
            is State.Installing -> stringResource(R.string.app_update_installing)
            is State.Failed -> stringResource(R.string.app_update_failed)
        }
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (state is State.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        when (val s = state) {
            is State.Available -> {
                FilledTonalButton(onClick = { updater.downloadAndInstall(s.update) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.app_update_install))
                }
                s.update.notesUrl?.let { url ->
                    TextButton(onClick = { SystemActions.openWebPage(context, url) }) { Text(stringResource(R.string.app_update_notes)) }
                }
            }
            is State.Downloading -> BrandProgressBar(s.progress)
            is State.NeedsPermission -> {
                FilledTonalButton(onClick = { context.startActivity(updater.permissionIntent()) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.app_update_grant))
                }
                OutlinedButton(onClick = updater::resumeInstall, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.app_update_install))
                }
            }
            State.Checking, is State.Installing -> Unit
            else -> OutlinedButton(onClick = updater::check, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.app_update_check))
            }
        }
    }
}
