// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.kasaco223.kdownloader.engine.KdEngine
import io.github.kasaco223.kdownloader.ui.AppRoot
import io.github.kasaco223.kdownloader.ui.theme.KdTheme
import kotlinx.coroutines.launch

/**
 * La actividad del launcher (contrato A1). Pública: acepta el intent del launcher y el deep link
 * kdownloader://download?url=… (VIEW + BROWSABLE). El único otro extra es kdl.selftest (autoprueba del
 * motor para la CI), que se ignora en las compilaciones release.
 */
class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels { MainViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // El intent solo se procesa en un arranque nuevo: ni al restaurar el estado ni al volver desde Recientes.
        val fromHistory = (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (savedInstanceState == null && !fromHistory) DeepLinks.url(intent)?.let(viewModel::onLinkReceived)
        setContent {
            KdTheme { AppRoot(viewModel) }
        }
        if (savedInstanceState == null) runSelfTestIfAsked(intent)
    }

    override fun onStart() {
        super.onStart()
        val app = application as KdlApp
        app.engine.prepare() // no hace nada si el motor ya está listo o preparándose
        app.queue.startService() // retoma lo que quedó en cola
    }

    /** scripts/ci_emulator.sh abre la app con --ez kdl.selftest true y lee la línea KDL-SELFTEST de logcat. */
    private fun runSelfTestIfAsked(intent: Intent) {
        if (BuildConfig.BUILD_TYPE == "release" || !intent.getBooleanExtra(EXTRA_SELFTEST, false)) return
        val engine = (application as KdlApp).engine
        lifecycleScope.launch {
            val line = engine.selfTestLine()
            if (line.startsWith("PASS")) Log.i(KdEngine.SELFTEST_TAG, line) else Log.e(KdEngine.SELFTEST_TAG, line)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DeepLinks.url(intent)?.let(viewModel::onLinkReceived)
        runSelfTestIfAsked(intent)
    }

    private companion object {
        const val EXTRA_SELFTEST = "kdl.selftest"
    }
}
