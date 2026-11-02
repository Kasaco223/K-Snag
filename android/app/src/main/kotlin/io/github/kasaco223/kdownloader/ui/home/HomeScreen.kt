// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.ui.home

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.kasaco223.kdownloader.BuildConfig
import io.github.kasaco223.kdownloader.R
import io.github.kasaco223.kdownloader.contracts.EngineStatus
import io.github.kasaco223.kdownloader.core.Urls
import io.github.kasaco223.kdownloader.download.DownloadQueue.EnqueueResult
import io.github.kasaco223.kdownloader.download.HistoryItem
import io.github.kasaco223.kdownloader.download.Job
import io.github.kasaco223.kdownloader.platform.NotificationAccess
import io.github.kasaco223.kdownloader.platform.SystemActions
import io.github.kasaco223.kdownloader.ui.analyze.AnalysisState
import io.github.kasaco223.kdownloader.ui.analyze.MediaPreview
import io.github.kasaco223.kdownloader.ui.analyze.isLive
import io.github.kasaco223.kdownloader.ui.analyze.playlistUrls
import io.github.kasaco223.kdownloader.ui.analyze.prefill
import io.github.kasaco223.kdownloader.ui.components.BrandHeader
import io.github.kasaco223.kdownloader.ui.components.GradientButton
import io.github.kasaco223.kdownloader.ui.components.ScreenContent
import io.github.kasaco223.kdownloader.ui.engine.EngineCard
import io.github.kasaco223.kdownloader.ui.formats.FormatChooser
import io.github.kasaco223.kdownloader.ui.jobs.ActiveJobCard
import io.github.kasaco223.kdownloader.ui.jobs.DownloadRequest
import io.github.kasaco223.kdownloader.ui.jobs.HistoryRow
import io.github.kasaco223.kdownloader.ui.jobs.JobRow
import io.github.kasaco223.kdownloader.ui.jobs.ListHeader
import io.github.kasaco223.kdownloader.ui.jobs.rememberDownloadStarter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Inicio: «¿Qué bajamos hoy?», pegar el enlace y elegir el formato con un toque, la descarga en curso en grande y
 * lo último que bajaste. [receivedLink] es el enlace que llegó por kdownloader://download (rellena el cuadro).
 */
@Composable
fun HomeScreen(
    padding: PaddingValues,
    snackbar: SnackbarHostState,
    receivedLink: String?,
    onDismissLink: () -> Unit,
    engineStatus: EngineStatus,
    onRetryEngine: () -> Unit,
    jobs: List<Job>,
    history: List<HistoryItem>,
    favorite: String,
    analysis: AnalysisState,
    onAnalyze: (url: String, playlist: Boolean?) -> Unit,
    onRetryAnalysis: () -> Unit,
    onClearAnalysis: () -> Unit,
    enqueue: suspend (DownloadRequest) -> EnqueueResult,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onRemove: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onOpenItem: (HistoryItem) -> Unit,
    onOpenDownloads: () -> Unit,
    waitingForWifi: Boolean = false,
    updateVersion: String? = null,
    onOpenUpdate: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(receivedLink) {
        if (receivedLink != null) {
            text = receivedLink
            onDismissLink()
        }
    }
    val msgQueued = stringResource(R.string.download_queued)
    val msgQueuedHere = stringResource(R.string.download_queued_here)
    // Aviso de notificaciones apagadas: se revisa al volver a la app (por ejemplo, desde los ajustes).
    var notifWarn by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        notifWarn = NotificationAccess.shouldWarn(context)
        onPauseOrDispose { }
    }
    val msgInvalid = stringResource(R.string.download_invalid)
    val msgStorage = stringResource(R.string.download_storage_denied)
    val msgAlready = stringResource(R.string.download_already)
    val msgAgain = stringResource(R.string.download_again)
    val msgNoLink = stringResource(R.string.home_paste_empty)
    val res = LocalResources.current
    lateinit var start: (DownloadRequest) -> Unit
    start = rememberDownloadStarter(
        enqueue = enqueue,
        onNotificationPrompt = { open -> if (!open) notifWarn = NotificationAccess.shouldWarn(context) },
    ) { request, result ->
        // Si la pregunta del permiso se acaba de abrir, el avance se verá en cuanto la persona acepte.
        val visible = NotificationAccess.progressVisible(context) || NotificationAccess.needsPermission(context)
        scope.launch {
            when (result) {
                is EnqueueResult.Queued -> {
                    text = ""
                    snackbar.showSnackbar(if (visible) msgQueued else msgQueuedHere)
                }
                is EnqueueResult.AlreadyInDownloads -> {
                    val r = snackbar.showSnackbar("$msgAlready: ${result.item.displayName}", actionLabel = msgAgain, duration = SnackbarDuration.Long)
                    if (r == SnackbarResult.ActionPerformed) start(request.copy(allowDuplicate = true))
                }
                is EnqueueResult.QueuedMany -> {
                    if (result.queued > 0) text = ""
                    snackbar.showSnackbar(queuedManyText(res, result))
                }
                is EnqueueResult.Rejected -> snackbar.showSnackbar(if (result.code == "storage_permission") msgStorage else msgInvalid)
            }
        }
    }
    val link = text.trim()
    val urlOk = Urls.validate(link) != null
    // Analiza el enlace apenas se deja de escribir (pegar o compartir lo deja listo de una vez).
    LaunchedEffect(link, urlOk) {
        if (urlOk) {
            delay(400)
            onAnalyze(link, null)
        } else {
            onClearAnalysis()
        }
    }
    val running = jobs.filter { it.state == "downloading" || it.state == "processing" }.sortedBy { it.createdAt }
    val queued = jobs.filter { it.state == "queued" }.sortedBy { it.createdAt }
    val problems = jobs.filter { it.state == "error" || it.state == "interrupted" }.sortedByDescending { it.createdAt }

    ScreenContent(padding) {
        BrandHeader(Modifier.padding(top = 4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.home_link_title),
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, lineHeight = 36.sp),
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.home_lead), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (urlOk) {
            LinkReady(
                link = link,
                favorite = favorite,
                analysis = analysis,
                onClear = { text = "" },
                onRetry = onRetryAnalysis,
                onPlaylistMode = { onAnalyze(link, it) },
                onPick = { option -> start(requestFor(link, option, analysis)) },
            )
        } else {
            GradientButton(
                onClick = {
                    val found = SystemActions.readClipboard(context)?.let { Urls.extract(it).firstOrNull() }
                    if (found != null) text = found else scope.launch { snackbar.showSnackbar(msgNoLink) }
                },
                modifier = Modifier.fillMaxWidth(),
                minHeight = 60.dp,
            ) {
                Icon(painterResource(R.drawable.ic_paste), contentDescription = null)
                Text(stringResource(R.string.home_paste_link), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 17.sp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.home_link_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                )
                if (BuildConfig.DONATE_URL.isNotEmpty()) {
                    OutlinedIconButton(
                        onClick = { SystemActions.openWebPage(context, BuildConfig.DONATE_URL) },
                        modifier = Modifier.size(56.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_heart),
                            contentDescription = stringResource(R.string.donate_title),
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }

        if (updateVersion != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp))
                    .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.app_update_banner, updateVersion),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenUpdate) { Text(stringResource(R.string.app_update_see)) }
            }
        }
        if (notifWarn) {
            NotificationsOffBanner(
                onEnable = { NotificationAccess.openSettings(context) },
                onHide = {
                    NotificationAccess.hideWarning(context)
                    notifWarn = false
                },
            )
        }
        if (engineStatus !is EngineStatus.Ready) EngineCard(status = engineStatus, onRetry = onRetryEngine)

        if (running.isNotEmpty() || queued.isNotEmpty()) {
            ListHeader(stringResource(R.string.home_now))
            if (waitingForWifi && running.isEmpty()) {
                Text(
                    text = stringResource(R.string.home_waiting_wifi),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
            running.forEach { ActiveJobCard(it, onCancel) }
            queued.forEach { JobRow(it, onCancel, onRetry, onRemove) }
        }
        if (problems.isNotEmpty()) {
            ListHeader(stringResource(R.string.home_problems))
            problems.forEach { JobRow(it, onCancel, onRetry, onRemove) }
        }
        if (history.isNotEmpty()) {
            ListHeader(stringResource(R.string.home_recent), stringResource(R.string.home_see_all), onOpenDownloads)
            Column {
                history.take(3).forEach { HistoryRow(it, onOpenItem, onRemoveHistory, compact = true) }
            }
        } else if (jobs.isEmpty()) {
            EmptyDownloads()
        }
    }
}

/** Las notificaciones están apagadas: sin ellas el avance no se ve con la app cerrada. */
@Composable
private fun NotificationsOffBanner(onEnable: () -> Unit, onHide: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(14.dp))
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.notif_off_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = stringResource(R.string.notif_off_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            IconButton(onClick = onHide) {
                Icon(
                    painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.common_close),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        TextButton(onClick = onEnable) { Text(stringResource(R.string.notif_off_enable)) }
    }
}

/** El pedido para un formato: con lo que se sabe del análisis, o la lista entera si es una lista. */
fun requestFor(link: String, option: String, analysis: AnalysisState): DownloadRequest {
    val a = (analysis as? AnalysisState.Ready)?.takeIf { it.url == link }?.analysis ?: return DownloadRequest(link, option)
    return if (a.summary.isPlaylist) DownloadRequest(link, option, urls = a.playlistUrls) else DownloadRequest(a.url, option, prefill = a.prefill())
}

/** "Se agregaron 12 videos a la cola (3 ya estaban en Descargas)". */
fun queuedManyText(res: Resources, r: EnqueueResult.QueuedMany): String {
    val queued = res.getQuantityString(R.plurals.download_queued_many, r.queued, r.queued)
    return if (r.skipped > 0) queued + " " + res.getQuantityString(R.plurals.download_skipped, r.skipped, r.skipped) else queued
}

/** El enlace listo para bajar: lo que se sabe de él, el botón grande con el formato principal y los otros como chips. */
@Composable
private fun LinkReady(
    link: String,
    favorite: String,
    analysis: AnalysisState,
    onClear: () -> Unit,
    onRetry: () -> Unit,
    onPlaylistMode: (Boolean) -> Unit,
    onPick: (String) -> Unit,
) {
    val ready = (analysis as? AnalysisState.Ready)?.takeIf { it.url == link }?.analysis
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.home_link_ready), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(link, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onClear) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.home_clear_link), modifier = Modifier.size(20.dp))
                }
            }
            MediaPreview(analysis.takeIf { it !is AnalysisState.Idle } ?: AnalysisState.Loading(link, false), onRetry, onPlaylistMode)
            FormatChooser(
                favorite = favorite,
                onPick = onPick,
                enabled = !analysis.isLive,
                tiles = ready?.tiles,
                more = ready?.more.orEmpty(),
                playlistCount = ready?.takeIf { it.summary.isPlaylist }?.playlistUrls?.size,
            )
        }
    }
}

/** Sin descargas todavía: cómo se baja el primer video. */
@Composable
fun EmptyDownloads(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_downloads), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.home_empty_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
