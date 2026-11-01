// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.kasaco223.kdownloader.BuildConfig
import io.github.kasaco223.kdownloader.R
import io.github.kasaco223.kdownloader.RepairOutcome
import io.github.kasaco223.kdownloader.contracts.EngineStatus
import io.github.kasaco223.kdownloader.platform.DeviceDiagnostics
import io.github.kasaco223.kdownloader.platform.SourceLinks
import io.github.kasaco223.kdownloader.platform.SystemActions
import io.github.kasaco223.kdownloader.ui.components.BackButton
import io.github.kasaco223.kdownloader.ui.components.KdCard
import io.github.kasaco223.kdownloader.ui.components.ScreenContent
import io.github.kasaco223.kdownloader.ui.components.SectionTitle
import io.github.kasaco223.kdownloader.ui.engine.EngineCard
import kotlinx.coroutines.launch

/**
 * «Acerca de»: los avisos legales que pide la GPL-3.0 (§0 y §5 d: copyright, sin garantía, que se puede
 * redistribuir bajo la GPL y cómo leer la licencia), los avisos de terceros, el enlace al código fuente de esta
 * compilación, el motor de descargas («Reparar») y la tarjeta «Diagnóstico».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenLicense: () -> Unit,
    onOpenThirdParty: () -> Unit,
    engineStatus: EngineStatus,
    onRetryEngine: () -> Unit,
    repair: RepairOutcome?,
    onRepair: () -> Unit,
    onRepairShown: () -> Unit,
) {
    val context = LocalContext.current
    val diagnostics = remember(context) { DeviceDiagnostics.collect(context) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val noBrowser = stringResource(R.string.common_no_browser)
    val copied = stringResource(R.string.diag_copied)
    val clipLabel = stringResource(R.string.diag_title)
    val repairMessage = when (repair) {
        RepairOutcome.DONE -> stringResource(R.string.engine_repair_done)
        RepairOutcome.BUSY -> stringResource(R.string.engine_repair_busy)
        RepairOutcome.FAILED -> stringResource(R.string.engine_repair_failed)
        else -> null
    }
    LaunchedEffect(repairMessage) {
        if (repairMessage != null) {
            snackbarHostState.showSnackbar(repairMessage)
            onRepairShown()
        }
    }
    val sourceUrl = SourceLinks.sourceTree(
        repoUrl = BuildConfig.REPO_URL,
        buildType = BuildConfig.BUILD_TYPE,
        versionName = BuildConfig.VERSION_NAME,
        sourceCommit = BuildConfig.SOURCE_COMMIT,
    )
    val openPage: (String) -> Unit = { url ->
        if (!SystemActions.openWebPage(context, url)) scope.launch { snackbarHostState.showSnackbar(noBrowser) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        ScreenContent(innerPadding) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.about_app_name_full),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = stringResource(
                        R.string.about_version_detail,
                        diagnostics.versionName,
                        diagnostics.flavor,
                        diagnostics.apkAbi ?: stringResource(R.string.diag_apk_abi_unsplit),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            KdCard {
                SectionTitle(stringResource(R.string.about_license_title))
                Text(text = stringResource(R.string.about_copyright), style = MaterialTheme.typography.bodyMedium)
                Text(text = stringResource(R.string.about_gpl_notice), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onOpenLicense, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.about_view_license))
                }
                OutlinedButton(onClick = onOpenThirdParty, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.about_third_party))
                }
                OutlinedButton(onClick = { openPage(sourceUrl) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.about_source))
                }
                TextButton(onClick = { openPage(BuildConfig.WEB_BASE) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.about_website))
                }
            }
            EngineCard(
                status = engineStatus,
                onRetry = onRetryEngine,
                detailed = true,
                repairing = repair == RepairOutcome.RUNNING,
                onRepair = onRepair,
            )
            DiagnosticsCard(
                diagnostics = diagnostics,
                onCopy = {
                    if (SystemActions.copyPlainText(context, clipLabel, diagnostics.report())) {
                        scope.launch { snackbarHostState.showSnackbar(copied) }
                    }
                },
            )
            Text(
                text = stringResource(R.string.about_not_affiliated),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
