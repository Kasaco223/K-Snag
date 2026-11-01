// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kasaco223.kdownloader.MainViewModel
import io.github.kasaco223.kdownloader.Screen
import io.github.kasaco223.kdownloader.ui.about.AboutScreen
import io.github.kasaco223.kdownloader.ui.about.LicenseScreen
import io.github.kasaco223.kdownloader.ui.about.ThirdPartyScreen
import io.github.kasaco223.kdownloader.ui.history.HistoryScreen
import io.github.kasaco223.kdownloader.ui.home.HomeScreen

/** Raíz de MainActivity: muestra la pantalla actual. «Atrás» solo se intercepta fuera del inicio. */
@Composable
fun AppRoot(viewModel: MainViewModel) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val receivedLink by viewModel.receivedLink.collectAsStateWithLifecycle()
    val engineStatus by viewModel.engineStatus.collectAsStateWithLifecycle()
    val repair by viewModel.repair.collectAsStateWithLifecycle()
    val licenseDoc by viewModel.licenseDoc.collectAsStateWithLifecycle()
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()

    BackHandler(enabled = screen != Screen.HOME) { viewModel.back() }

    when (screen) {
        Screen.HOME -> HomeScreen(
            receivedLink = receivedLink,
            onDismissLink = viewModel::dismissLink,
            onOpenAbout = { viewModel.open(Screen.ABOUT) },
            onOpenHistory = { viewModel.open(Screen.HISTORY) },
            engineStatus = engineStatus,
            onRetryEngine = viewModel::retryEngine,
            jobs = jobs,
            enqueue = viewModel::enqueue,
            onCancel = viewModel::cancel,
            onRetry = viewModel::retry,
            onRemove = viewModel::remove,
            onClearFinished = viewModel::clearFinished,
        )
        Screen.HISTORY -> HistoryScreen(items = history, onBack = { viewModel.back() }, onRemove = viewModel::removeHistory)
        Screen.ABOUT -> AboutScreen(
            onBack = { viewModel.back() },
            onOpenLicense = { viewModel.open(Screen.LICENSE) },
            onOpenThirdParty = { viewModel.open(Screen.THIRD_PARTY) },
            engineStatus = engineStatus,
            onRetryEngine = viewModel::retryEngine,
            repair = repair,
            onRepair = viewModel::repairEngine,
            onRepairShown = viewModel::repairShown,
        )
        Screen.LICENSE -> LicenseScreen(onBack = { viewModel.back() })
        Screen.THIRD_PARTY -> ThirdPartyScreen(onBack = { viewModel.back() }, onOpenText = viewModel::openLicenseDoc)
        Screen.THIRD_PARTY_TEXT -> {
            val doc = licenseDoc
            if (doc == null) {
                viewModel.back()
            } else {
                LicenseScreen(onBack = { viewModel.back() }, title = doc.title, assetPath = doc.assetPath)
            }
        }
    }
}
