// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.platform

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Preferencias de la interfaz (kd_ui). Por ahora, el formato principal: el del botón grande de Inicio y de la
 * hoja de compartir. Los ajustes completos llegan con la pestaña Ajustes del plan.
 */
class UiPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("kd_ui", Context.MODE_PRIVATE)

    private val _favorite = MutableStateFlow(prefs.getString(KEY_FAVORITE, null)?.takeIf { it in OPTIONS } ?: OPTIONS.first())
    val favorite: StateFlow<String> = _favorite.asStateFlow()

    fun setFavorite(option: String) {
        if (option !in OPTIONS) return
        _favorite.value = option
        prefs.edit().putString(KEY_FAVORITE, option).apply()
    }

    companion object {
        /** Los cuatro formatos, en el orden en que se muestran. */
        val OPTIONS = listOf("recommended", "max", "mp3", "m4a")
        private const val KEY_FAVORITE = "favorite_option"
    }
}
