// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.ui.about

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import io.github.kasaco223.kdownloader.R
import io.github.kasaco223.kdownloader.platform.Diagnostics
import io.github.kasaco223.kdownloader.ui.components.KdCard
import io.github.kasaco223.kdownloader.ui.components.SectionTitle

/**
 * Tarjeta «Diagnóstico»: lo que hace falta para entender un problema en un celular concreto (versión de
 * Android, ABI, tamaño de página de memoria, versión y compilación de la app). «Copiar detalles» copia
 * [Diagnostics.report], que nunca incluye enlaces.
 */
@Composable
fun DiagnosticsCard(diagnostics: Diagnostics, onCopy: () -> Unit) {
    KdCard {
        SectionTitle(stringResource(R.string.diag_title))
        Text(
            text = stringResource(R.string.diag_lead),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DiagnosticRow(
            R.string.diag_android,
            stringResource(R.string.diag_android_value, diagnostics.androidRelease, diagnostics.apiLevel),
        )
        DiagnosticRow(R.string.diag_device, diagnostics.device)
        DiagnosticRow(R.string.diag_device_abis, diagnostics.deviceAbis.joinToString(", "))
        DiagnosticRow(R.string.diag_apk_abi, diagnostics.apkAbi ?: stringResource(R.string.diag_apk_abi_unsplit))
        DiagnosticRow(R.string.diag_page_size, pageSizeText(diagnostics.pageSizeBytes))
        DiagnosticRow(R.string.diag_version, diagnostics.versionName)
        DiagnosticRow(R.string.diag_version_code, diagnostics.versionCode.toString())
        DiagnosticRow(R.string.diag_build, "${diagnostics.flavor} · ${diagnostics.buildType}")
        DiagnosticRow(
            R.string.diag_commit,
            diagnostics.knownCommit?.take(12) ?: stringResource(R.string.diag_commit_unknown),
        )
        OutlinedButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.diag_copy))
        }
    }
}

/** Etiqueta y valor en un solo nodo de accesibilidad («Versión de Android, 16 (API 36)»). */
@Composable
private fun DiagnosticRow(@StringRes label: Int, value: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun pageSizeText(bytes: Long): String =
    if (bytes > 0 && bytes % 1024 == 0L) {
        stringResource(R.string.diag_page_size_kb, bytes / 1024)
    } else {
        stringResource(R.string.diag_page_size_bytes, bytes)
    }
