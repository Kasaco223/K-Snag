// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kasaco223.kdownloader.MainViewModel
import io.github.kasaco223.kdownloader.R
import io.github.kasaco223.kdownloader.Screen
import io.github.kasaco223.kdownloader.contracts.EngineStatus
import io.github.kasaco223.kdownloader.download.HistoryItem
import io.github.kasaco223.kdownloader.platform.SystemActions
import io.github.kasaco223.kdownloader.ui.about.AboutScreen
import io.github.kasaco223.kdownloader.ui.about.LicenseScreen
import io.github.kasaco223.kdownloader.ui.about.ThirdPartyScreen
import io.github.kasaco223.kdownloader.ui.components.LocalThumbnails
import io.github.kasaco223.kdownloader.ui.downloads.DownloadsScreen
import io.github.kasaco223.kdownloader.ui.home.HomeScreen
import io.github.kasaco223.kdownloader.ui.onboarding.WelcomeScreen
import io.github.kasaco223.kdownloader.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

private data class Tab(val screen: Screen, val label: Int, val icon: Int)

private val tabs = listOf(
    Tab(Screen.HOME, R.string.tab_home, R.drawable.ic_home),
    Tab(Screen.DOWNLOADS, R.string.tab_downloads, R.drawable.ic_downloads),
    Tab(Screen.SETTINGS, R.string.tab_settings, R.drawable.ic_tune),
)

/**
 * Raíz de MainActivity. Inicio, Descargas y Ajustes comparten la barra de abajo y los avisos; Acerca de y las
 * licencias se abren encima, a pantalla completa. «Atrás» solo se intercepta fuera de Inicio.
 */
@Composable
fun AppRoot(viewModel: MainViewModel) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val receivedLink by viewModel.receivedLink.collectAsStateWithLifecycle()
    val engineStatus by viewModel.engineStatus.collectAsStateWithLifecycle()
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val favorite by viewModel.favorite.collectAsStateWithLifecycle()
    val analysis by viewModel.analysis.state.collectAsStateWithLifecycle()
    val onboarded by viewModel.prefs.onboarded.collectAsStateWithLifecycle()
    val engineUpdate by viewModel.engineUpdate.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val openFailed = stringResource(R.string.common_no_app_for_file)
    val openItem: (HistoryItem) -> Unit = { item ->
        if (!SystemActions.openFile(context, item.contentUri, item.mime)) scope.launch { snackbar.showSnackbar(openFailed) }
    }

    BackHandler(enabled = screen != Screen.HOME) { viewModel.back() }

    if (!onboarded) {
        WelcomeScreen(engineStatus = engineStatus, onRetryEngine = viewModel::retryEngine, onDone = { viewModel.prefs.setOnboarded(true) })
        return
    }

    CompositionLocalProvider(LocalThumbnails provides viewModel.thumbnails) {
        if (screen.isTab) {
            Scaffold(
                bottomBar = { TabBar(screen, onSelect = viewModel::open) },
                snackbarHost = { SnackbarHost(snackbar) },
            ) { padding ->
                when (screen) {
                    Screen.DOWNLOADS -> DownloadsScreen(
                        padding = padding,
                        jobs = jobs,
                        history = history,
                        onCancel = viewModel::cancel,
                        onRetry = viewModel::retry,
                        onRemove = viewModel::remove,
                        onClearFinished = viewModel::clearFinished,
                        onOpenItem = openItem,
                        onRemoveHistory = viewModel::removeHistory,
                    )
                    Screen.SETTINGS -> SettingsScreen(
                        padding = padding,
                        prefs = viewModel.prefs,
                        engineStatus = engineStatus,
                        engineUpdate = engineUpdate,
                        onCheckEngineUpdate = viewModel::checkEngineUpdate,
                        onRollbackEngine = viewModel::rollbackEngine,
                        onRetryEngine = viewModel::retryEngine,
                        onOpenAbout = { viewModel.open(Screen.ABOUT) },
                    )
                    else -> HomeScreen(
                        padding = padding,
                        snackbar = snackbar,
                        receivedLink = receivedLink,
                        onDismissLink = viewModel::dismissLink,
                        engineStatus = engineStatus,
                        onRetryEngine = viewModel::retryEngine,
                        jobs = jobs,
                        history = history,
                        favorite = favorite,
                        analysis = analysis,
                        onAnalyze = { url, playlist -> viewModel.analysis.analyze(url, playlist) },
                        onRetryAnalysis = viewModel.analysis::retry,
                        onClearAnalysis = viewModel.analysis::clear,
                        enqueue = viewModel::enqueue,
                        onCancel = viewModel::cancel,
                        onRetry = viewModel::retry,
                        onRemove = viewModel::remove,
                        onRemoveHistory = viewModel::removeHistory,
                        onOpenItem = openItem,
                        onOpenDownloads = { viewModel.open(Screen.DOWNLOADS) },
                    )
                }
            }
        } else {
            FullScreen(viewModel, screen, engineStatus)
        }
    }
}

/** Las pantallas que se abren encima de las pestañas. */
@Composable
private fun FullScreen(viewModel: MainViewModel, screen: Screen, engineStatus: EngineStatus) {
    val repair by viewModel.repair.collectAsStateWithLifecycle()
    val licenseDoc by viewModel.licenseDoc.collectAsStateWithLifecycle()
    when (screen) {
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
        else -> Unit // las pestañas van arriba
    }
}

@Composable
private fun TabBar(current: Screen, onSelect: (Screen) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        for (tab in tabs) {
            NavigationBarItem(
                selected = current == tab.screen,
                onClick = { if (current != tab.screen) onSelect(tab.screen) },
                icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                label = { Text(stringResource(tab.label)) },
                colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.secondaryContainer),
            )
        }
    }
}
