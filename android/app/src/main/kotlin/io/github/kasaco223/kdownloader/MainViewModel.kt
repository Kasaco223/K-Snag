// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.kasaco223.kdownloader.contracts.EngineController
import io.github.kasaco223.kdownloader.contracts.EngineStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Pantallas de esta versión. La navegación completa (pestañas, Navigation 3) llega con el resto de la app. */
enum class Screen(val parent: Screen?) {
    HOME(null),
    ABOUT(HOME),
    LICENSE(ABOUT),
    THIRD_PARTY(ABOUT),
    THIRD_PARTY_TEXT(THIRD_PARTY),
}

/** Un texto de licencia de terceros abierto desde «Avisos de terceros». */
data class LicenseDoc(val title: String, val assetPath: String)

/** Resultado de «Reparar», para el aviso en pantalla. */
enum class RepairOutcome { RUNNING, DONE, BUSY, FAILED }

/**
 * Pantalla actual, enlace recibido por kdownloader://download y estado del motor. La pantalla y el enlace viven
 * en SavedStateHandle: sobreviven a la rotación y a que Android cierre el proceso en segundo plano.
 */
class MainViewModel(private val savedState: SavedStateHandle, private val engine: EngineController) : ViewModel() {
    private val _screen = MutableStateFlow(
        savedState.get<String>(KEY_SCREEN)?.let { name -> Screen.entries.firstOrNull { it.name == name } } ?: Screen.HOME,
    )
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _receivedLink = MutableStateFlow(savedState.get<String>(KEY_LINK))
    val receivedLink: StateFlow<String?> = _receivedLink.asStateFlow()

    private val _licenseDoc = MutableStateFlow(
        savedState.get<String>(KEY_DOC_PATH)?.let { LicenseDoc(savedState.get<String>(KEY_DOC_TITLE).orEmpty(), it) },
    )
    val licenseDoc: StateFlow<LicenseDoc?> = _licenseDoc.asStateFlow()

    val engineStatus: StateFlow<EngineStatus> = engine.status

    private val _repair = MutableStateFlow<RepairOutcome?>(null)
    val repair: StateFlow<RepairOutcome?> = _repair.asStateFlow()

    fun open(screen: Screen) {
        _screen.value = screen
        savedState[KEY_SCREEN] = screen.name
    }

    /** Vuelve a la pantalla anterior. False si ya está en el inicio (ahí «Atrás» lo maneja el sistema). */
    fun back(): Boolean {
        val parent = _screen.value.parent ?: return false
        open(parent)
        return true
    }

    fun onLinkReceived(url: String) {
        _receivedLink.value = url
        savedState[KEY_LINK] = url
        open(Screen.HOME)
    }

    fun dismissLink() {
        _receivedLink.value = null
        savedState[KEY_LINK] = null
    }

    fun openLicenseDoc(doc: LicenseDoc) {
        _licenseDoc.value = doc
        savedState[KEY_DOC_PATH] = doc.assetPath
        savedState[KEY_DOC_TITLE] = doc.title
        open(Screen.THIRD_PARTY_TEXT)
    }

    fun retryEngine() = engine.retrySetup()

    fun repairEngine() {
        if (_repair.value == RepairOutcome.RUNNING) return
        _repair.value = RepairOutcome.RUNNING
        viewModelScope.launch {
            val result = engine.repair()
            val busy = (result.exceptionOrNull() as? io.github.kasaco223.kdownloader.engine.KdEngine.EngineException)
                ?.error?.code == "busy"
            _repair.value = when {
                result.isSuccess -> RepairOutcome.DONE
                busy -> RepairOutcome.BUSY
                else -> RepairOutcome.FAILED
            }
        }
    }

    fun repairShown() {
        if (_repair.value != RepairOutcome.RUNNING) _repair.value = null
    }

    companion object {
        private const val KEY_SCREEN = "screen"
        private const val KEY_LINK = "receivedLink"
        private const val KEY_DOC_PATH = "licenseDocPath"
        private const val KEY_DOC_TITLE = "licenseDocTitle"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { MainViewModel(createSavedStateHandle(), (this[APPLICATION_KEY] as KdlApp).engine) }
        }
    }
}
