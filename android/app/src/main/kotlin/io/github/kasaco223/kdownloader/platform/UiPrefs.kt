// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.platform

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Los ajustes de la app (kd_ui), cada uno como StateFlow para que la interfaz y la cola reaccionen enseguida:
 * formato principal, tema, bienvenida vista, solo con Wi-Fi y las opciones del motor.
 */
class UiPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("kd_ui", Context.MODE_PRIVATE)

    private fun string(key: String, allowed: List<String>): MutableStateFlow<String> =
        MutableStateFlow(prefs.getString(key, null)?.takeIf { it in allowed } ?: allowed.first())

    private fun bool(key: String, default: Boolean) = MutableStateFlow(prefs.getBoolean(key, default))

    private val _favorite = string(KEY_FAVORITE, OPTIONS)
    val favorite: StateFlow<String> = _favorite.asStateFlow()

    private val _theme = string(KEY_THEME, THEMES)
    val theme: StateFlow<String> = _theme.asStateFlow()

    private val _onboarded = bool(KEY_ONBOARDED, false)
    val onboarded: StateFlow<Boolean> = _onboarded.asStateFlow()

    private val _wifiOnly = bool(KEY_WIFI_ONLY, false)
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    private val _autoUpdateEngine = bool(KEY_AUTO_UPDATE_ENGINE, true)
    val autoUpdateEngine: StateFlow<Boolean> = _autoUpdateEngine.asStateFlow()

    private val _engineChannel = string(KEY_ENGINE_CHANNEL, CHANNELS)
    val engineChannel: StateFlow<String> = _engineChannel.asStateFlow()

    private val _autoUpdateApp = bool(KEY_AUTO_UPDATE_APP, true)
    val autoUpdateApp: StateFlow<Boolean> = _autoUpdateApp.asStateFlow()

    fun setFavorite(option: String) = put(KEY_FAVORITE, option, OPTIONS, _favorite)

    fun setTheme(theme: String) = put(KEY_THEME, theme, THEMES, _theme)

    fun setEngineChannel(channel: String) = put(KEY_ENGINE_CHANNEL, channel, CHANNELS, _engineChannel)

    fun setOnboarded(value: Boolean) = put(KEY_ONBOARDED, value, _onboarded)

    fun setWifiOnly(value: Boolean) = put(KEY_WIFI_ONLY, value, _wifiOnly)

    fun setAutoUpdateEngine(value: Boolean) = put(KEY_AUTO_UPDATE_ENGINE, value, _autoUpdateEngine)

    fun setAutoUpdateApp(value: Boolean) = put(KEY_AUTO_UPDATE_APP, value, _autoUpdateApp)

    private fun put(key: String, value: String, allowed: List<String>, flow: MutableStateFlow<String>) {
        if (value !in allowed) return
        flow.value = value
        prefs.edit().putString(key, value).apply()
    }

    private fun put(key: String, value: Boolean, flow: MutableStateFlow<Boolean>) {
        flow.value = value
        prefs.edit().putBoolean(key, value).apply()
    }

    companion object {
        /** Los cuatro formatos, en el orden en que se muestran. */
        val OPTIONS = listOf("recommended", "max", "mp3", "m4a")

        /** Tema: el del sistema, claro u oscuro. */
        val THEMES = listOf("system", "light", "dark")

        /** Canal de yt-dlp: nightly (por defecto, como en Windows) o estable. */
        val CHANNELS = listOf("nightly", "stable")

        private const val KEY_FAVORITE = "favorite_option"
        private const val KEY_THEME = "theme"
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_AUTO_UPDATE_ENGINE = "auto_update_engine"
        private const val KEY_ENGINE_CHANNEL = "engine_channel"
        private const val KEY_AUTO_UPDATE_APP = "auto_update_app"
    }
}
