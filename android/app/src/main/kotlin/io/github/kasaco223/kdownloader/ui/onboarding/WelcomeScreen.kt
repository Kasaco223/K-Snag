// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kasaco223.kdownloader.R
import io.github.kasaco223.kdownloader.contracts.EngineStatus
import io.github.kasaco223.kdownloader.ui.components.GradientButton
import io.github.kasaco223.kdownloader.ui.components.KdCard
import io.github.kasaco223.kdownloader.ui.components.ScreenContent
import io.github.kasaco223.kdownloader.ui.components.SectionTitle
import io.github.kasaco223.kdownloader.ui.engine.EngineCard

/**
 * La bienvenida del primer uso: cómo se usa en tres pasos, el uso responsable (hay que aceptarlo) y el motor,
 * que se prepara mientras tanto. Se muestra una sola vez.
 */
@Composable
fun WelcomeScreen(engineStatus: EngineStatus, onRetryEngine: () -> Unit, onDone: () -> Unit) {
    var accepted by rememberSaveable { mutableStateOf(false) }
    Scaffold { padding: PaddingValues ->
        ScreenContent(padding) {
            Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.padding(top = 16.dp).size(64.dp))
            Text(
                text = stringResource(R.string.welcome_title),
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, lineHeight = 36.sp),
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.welcome_lead), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            KdCard {
                Step(R.drawable.ic_paste, R.string.welcome_step1)
                Step(R.drawable.ic_video, R.string.welcome_step2)
                Step(R.drawable.ic_folder, R.string.welcome_step3)
            }
            KdCard {
                SectionTitle(stringResource(R.string.welcome_responsible_title))
                Text(stringResource(R.string.welcome_responsible_body), style = MaterialTheme.typography.bodyMedium)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = accepted, role = Role.Checkbox, onValueChange = { accepted = it })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = accepted, onCheckedChange = null)
                    Text(stringResource(R.string.welcome_accept), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                }
            }
            EngineCard(status = engineStatus, onRetry = onRetryEngine)
            GradientButton(onClick = onDone, enabled = accepted, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.welcome_start), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            }
        }
    }
}

@Composable
private fun Step(icon: Int, text: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Column { Text(stringResource(text), style = MaterialTheme.typography.bodyLarge) }
    }
}
