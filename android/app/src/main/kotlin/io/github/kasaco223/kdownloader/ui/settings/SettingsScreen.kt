// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.kasaco223.kdownloader.BuildConfig
import io.github.kasaco223.kdownloader.R
import io.github.kasaco223.kdownloader.platform.NotificationAccess
import io.github.kasaco223.kdownloader.platform.SystemActions
import io.github.kasaco223.kdownloader.contracts.EngineStatus
import io.github.kasaco223.kdownloader.engine.KdEngine
import io.github.kasaco223.kdownloader.platform.UiPrefs
import io.github.kasaco223.kdownloader.ui.components.KdCard
import io.github.kasaco223.kdownloader.ui.components.ScreenContent
import io.github.kasaco223.kdownloader.ui.components.SectionTitle
import io.github.kasaco223.kdownloader.ui.engine.EngineCard
import io.github.kasaco223.kdownloader.ui.formats.formatInfo

/** Pestaña Ajustes: el formato principal, dónde se guardan los archivos, el motor y «Acerca de». */
@Composable
fun SettingsScreen(
    padding: PaddingValues,
    prefs: UiPrefs,
    engineStatus: EngineStatus,
    engineUpdate: KdEngine.UpdateState,
    onCheckEngineUpdate: () -> Unit,
    onRollbackEngine: () -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    updater: io.github.kasaco223.kdownloader.update.AppUpdater,
    onRetryEngine: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val favorite by prefs.favorite.collectAsStateWithLifecycle()
    val theme by prefs.theme.collectAsStateWithLifecycle()
    val onFavorite: (String) -> Unit = prefs::setFavorite
    val context = LocalContext.current
    ScreenContent(padding) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier
                .padding(top = 4.dp)
                .semantics { heading() },
        )
        KdCard {
            SectionTitle(stringResource(R.string.settings_favorite_title))
            Text(stringResource(R.string.settings_favorite_lead), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.selectableGroup()) {
                for (option in UiPrefs.OPTIONS) {
                    val info = formatInfo(option)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .selectable(selected = option == favorite, role = Role.RadioButton, onClick = { onFavorite(option) })
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = option == favorite, onClick = null)
                        Icon(painterResource(info.icon), contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(22.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(info.label), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(info.detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        KdCard {
            SectionTitle(stringResource(R.string.settings_theme_title))
            Column(Modifier.selectableGroup()) {
                for ((value, label) in listOf("system" to R.string.settings_theme_system, "light" to R.string.settings_theme_light, "dark" to R.string.settings_theme_dark)) {
                    RadioRow(selected = theme == value, text = stringResource(label), onClick = { prefs.setTheme(value) })
                }
            }
        }
        KdCard {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_folder), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    SectionTitle(stringResource(R.string.settings_folder_title))
                    Text(stringResource(R.string.settings_folder_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val wifiOnly by prefs.wifiOnly.collectAsStateWithLifecycle()
        KdCard {
            SectionTitle(stringResource(R.string.settings_downloads_title))
            SwitchRow(wifiOnly, stringResource(R.string.settings_wifi_only), stringResource(R.string.settings_wifi_only_detail), onWifiOnly)
            TextButton(onClick = { SystemActions.openBatterySettings(context) }) {
                Text(stringResource(R.string.settings_battery))
            }
            Text(stringResource(R.string.settings_battery_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { NotificationAccess.openSettings(context) }) {
                Text(stringResource(R.string.settings_notifications))
            }
            Text(stringResource(R.string.settings_notifications_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (engineStatus !is EngineStatus.Ready) EngineCard(status = engineStatus, onRetry = onRetryEngine)
        EngineSettingsCard(prefs, engineStatus, engineUpdate, onCheckEngineUpdate, onRollbackEngine)
        if (BuildConfig.SELF_UPDATE) AppUpdateCard(prefs, updater) // en F-Droid actualiza F-Droid
        if (BuildConfig.DONATE_URL.isNotEmpty()) {
            LinkCard(R.drawable.ic_heart, R.string.donate_title, R.string.donate_detail) { SystemActions.openWebPage(context, BuildConfig.DONATE_URL) }
        }
        LinkCard(R.drawable.ic_info, R.string.settings_about, R.string.settings_about_detail, onOpenAbout)
    }
}

/** Una tarjeta que lleva a otra pantalla o página: ícono, título, detalle y flecha. */
@Composable
fun LinkCard(icon: Int, title: Int, detail: Int, onClick: () -> Unit) {
    KdCard(Modifier.clip(MaterialTheme.shapes.medium).clickable(role = Role.Button, onClick = onClick)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Una opción de un grupo de radio, con toda la fila tocable. */
@Composable
fun RadioRow(selected: Boolean, text: String, onClick: () -> Unit, detail: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
