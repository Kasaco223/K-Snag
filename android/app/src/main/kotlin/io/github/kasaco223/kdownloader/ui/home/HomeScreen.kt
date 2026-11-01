// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.kasaco223.kdownloader.R
import io.github.kasaco223.kdownloader.contracts.EngineStatus
import io.github.kasaco223.kdownloader.ui.components.KdCard
import io.github.kasaco223.kdownloader.ui.components.ScreenContent
import io.github.kasaco223.kdownloader.ui.components.SectionTitle
import io.github.kasaco223.kdownloader.ui.engine.EngineCard

/**
 * Inicio de la versión de prueba: el estado del motor de descargas, y con honestidad que todavía no descarga
 * y cómo probar lo que sí hace (recibir un enlace al compartir). [receivedLink] es el enlace que llegó por
 * kdownloader://download.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    receivedLink: String?,
    onDismissLink: () -> Unit,
    onOpenAbout: () -> Unit,
    engineStatus: EngineStatus,
    onRetryEngine: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenAbout) {
                        Icon(painterResource(R.drawable.ic_info), contentDescription = stringResource(R.string.home_about))
                    }
                },
            )
        },
    ) { innerPadding ->
        ScreenContent(innerPadding) {
            if (receivedLink != null) ReceivedLinkCard(url = receivedLink, onDismiss = onDismissLink)
            EngineCard(status = engineStatus, onRetry = onRetryEngine)
            InfoCard(title = R.string.home_test_build_title, body = R.string.home_test_build_body)
            InfoCard(title = R.string.home_try_share_title, body = R.string.home_try_share_body)
            FilledTonalButton(onClick = onOpenAbout, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_about))
            }
        }
    }
}

@Composable
private fun ReceivedLinkCard(url: String, onDismiss: () -> Unit) {
    KdCard {
        SectionTitle(stringResource(R.string.link_received_title))
        SelectionContainer {
            Text(text = url, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            text = stringResource(R.string.link_not_yet),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.common_dismiss))
        }
    }
}

@Composable
private fun InfoCard(@StringRes title: Int, @StringRes body: Int) {
    KdCard {
        SectionTitle(stringResource(title))
        Text(text = stringResource(body), style = MaterialTheme.typography.bodyMedium)
    }
}
