// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.ui.about

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.kasaco223.kdownloader.LicenseDoc
import io.github.kasaco223.kdownloader.R
import io.github.kasaco223.kdownloader.ui.components.BackButton
import io.github.kasaco223.kdownloader.ui.components.KdCard
import io.github.kasaco223.kdownloader.ui.components.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Un componente de third_party/components.json, tal como lo copia el build a assets/licenses/third_party/. */
data class ThirdPartyItem(val name: String, val version: String?, val spdx: String, val note: String?, val licenseFiles: List<String>)

/**
 * «Avisos de terceros»: cada componente que trae el APK, su licencia y sus textos, sin conexión. Dónde está el
 * código fuente se explica arriba (el espejo de fuentes en GitHub).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThirdPartyScreen(onBack: () -> Unit, onOpenText: (LicenseDoc) -> Unit) {
    val context = LocalContext.current
    val items by produceState<List<ThirdPartyItem>?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) { readComponents(context) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.third_party_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.TopCenter) {
            val list = items
            when {
                list == null -> CircularProgressIndicator(Modifier.padding(32.dp))
                list.isEmpty() -> Text(
                    text = stringResource(R.string.third_party_load_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
                else -> LazyColumn(
                    modifier = Modifier.widthIn(max = 720.dp),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.third_party_lead),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    items(list, key = { it.name }) { item -> ThirdPartyCard(item, onOpenText) }
                }
            }
        }
    }
}

@Composable
private fun ThirdPartyCard(item: ThirdPartyItem, onOpenText: (LicenseDoc) -> Unit) {
    KdCard {
        SectionTitle(listOfNotNull(item.name, item.version).joinToString(" "))
        Text(item.spdx, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        item.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        for (file in item.licenseFiles) {
            val name = file.substringAfterLast('/')
            TextButton(onClick = { onOpenText(LicenseDoc("${item.name} · $name", "$TEXTS/$file")) }) {
                Text(stringResource(R.string.third_party_view, name))
            }
        }
    }
}

private const val TEXTS = "licenses/third_party/texts"

private fun readComponents(context: Context): List<ThirdPartyItem> = runCatching {
    val json = JSONObject(context.assets.open("licenses/third_party/components.json").bufferedReader().use { it.readText() })
    val arr = json.getJSONArray("components")
    (0 until arr.length()).map { arr.getJSONObject(it) }.map { c ->
        val files = c.optJSONArray("license_files")
        ThirdPartyItem(
            name = c.getString("name"),
            version = c.optString("version").takeIf { it.isNotEmpty() },
            spdx = c.getString("spdx"),
            note = c.optString("note").takeIf { it.isNotEmpty() },
            licenseFiles = if (files == null) emptyList() else (0 until files.length()).map { files.getString(it) },
        )
    }.sortedBy { it.name.lowercase() }
}.getOrDefault(emptyList())
