// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.kasaco223.kdownloader.R
import io.github.kasaco223.kdownloader.contracts.EngineStatus
import io.github.kasaco223.kdownloader.core.Urls
import io.github.kasaco223.kdownloader.download.DownloadQueue.EnqueueResult
import io.github.kasaco223.kdownloader.download.Job
import io.github.kasaco223.kdownloader.platform.SystemActions
import io.github.kasaco223.kdownloader.ui.components.KdCard
import io.github.kasaco223.kdownloader.ui.components.ScreenContent
import io.github.kasaco223.kdownloader.ui.components.SectionTitle
import io.github.kasaco223.kdownloader.ui.engine.EngineCard
import io.github.kasaco223.kdownloader.ui.jobs.DownloadRequest
import io.github.kasaco223.kdownloader.ui.jobs.JobsSection
import io.github.kasaco223.kdownloader.ui.jobs.rememberDownloadStarter
import kotlinx.coroutines.launch

/**
 * Inicio: pegar un enlace y elegir el formato, el estado del motor y las descargas. [receivedLink] es el enlace
 * que llegó por kdownloader://download (rellena el cuadro).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    receivedLink: String?,
    onDismissLink: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenHistory: () -> Unit,
    engineStatus: EngineStatus,
    onRetryEngine: () -> Unit,
    jobs: List<Job>,
    enqueue: suspend (DownloadRequest) -> EnqueueResult,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearFinished: () -> Unit,
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(receivedLink) {
        if (receivedLink != null) {
            text = receivedLink
            onDismissLink()
        }
    }
    val msgQueued = stringResource(R.string.download_queued)
    val msgInvalid = stringResource(R.string.download_invalid)
    val msgStorage = stringResource(R.string.download_storage_denied)
    val msgAlready = stringResource(R.string.download_already)
    val msgAgain = stringResource(R.string.download_again)
    val msgOpenFailed = stringResource(R.string.common_no_app_for_file)
    lateinit var start: (DownloadRequest) -> Unit
    start = rememberDownloadStarter(enqueue) { request, result ->
        scope.launch {
            when (result) {
                is EnqueueResult.Queued -> {
                    text = ""
                    snackbar.showSnackbar(msgQueued)
                }
                is EnqueueResult.AlreadyInDownloads -> {
                    val r = snackbar.showSnackbar("$msgAlready: ${result.item.displayName}", actionLabel = msgAgain, duration = SnackbarDuration.Long)
                    if (r == SnackbarResult.ActionPerformed) start(request.copy(allowDuplicate = true))
                }
                is EnqueueResult.Rejected -> snackbar.showSnackbar(if (result.code == "storage_permission") msgStorage else msgInvalid)
            }
        }
    }
    val urlOk = Urls.validate(text.trim()) != null

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
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        ScreenContent(innerPadding) {
            KdCard {
                SectionTitle(stringResource(R.string.home_link_title))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.home_link_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    trailingIcon = {
                        TextButton(onClick = {
                            SystemActions.readClipboard(context)?.let { clip -> Urls.extract(clip).firstOrNull()?.let { text = it } ?: run { text = clip.trim() } }
                        }) { Text(stringResource(R.string.home_paste)) }
                    },
                )
                Text(
                    text = stringResource(R.string.home_pick_format),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FormatButtons(enabled = urlOk) { option -> start(DownloadRequest(text.trim(), option)) }
            }
            EngineCard(status = engineStatus, onRetry = onRetryEngine)
            JobsSection(
                jobs = jobs,
                onCancel = onCancel,
                onRetry = onRetry,
                onOpen = { job ->
                    val uri = job.contentUri
                    if (uri == null || !SystemActions.openFile(context, uri, job.mime)) scope.launch { snackbar.showSnackbar(msgOpenFailed) }
                },
                onRemove = onRemove,
                onClearFinished = onClearFinished,
            )
            OutlinedButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.history_title))
            }
        }
    }
}

/** Los cuatro formatos: dos de video y dos de audio. */
@Composable
fun FormatButtons(enabled: Boolean, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onPick("recommended") }, enabled = enabled, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.option_recommended))
        }
        OutlinedButton(onClick = { onPick("max") }, enabled = enabled, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.option_max))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onPick("mp3") }, enabled = enabled, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.option_mp3))
        }
        OutlinedButton(onClick = { onPick("m4a") }, enabled = enabled, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.option_m4a))
        }
    }
}
