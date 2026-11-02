// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader

import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** La app abre en Inicio, las pestañas navegan, el formato principal se elige en Ajustes y Acerca de muestra los avisos legales. */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun text(id: Int): String = compose.activity.getString(id)

    @Test
    fun homeShowsTheFormatsOnceThereIsALink() {
        compose.onNodeWithText(text(R.string.home_link_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.home_paste_link)).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("https://youtu.be/aqz-KE-bpKQ")
        compose.onNodeWithText(text(R.string.format_action_recommended)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.option_mp3)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.home_clear_link)).performClick()
        compose.onNodeWithText(text(R.string.home_paste_link)).assertIsDisplayed()
    }

    @Test
    fun theMainFormatChosenInSettingsIsTheBigButton() {
        openSettings()
        compose.onNodeWithText(text(R.string.option_m4a)).performScrollTo().performClick()
        try {
            compose.onNodeWithText(text(R.string.tab_home)).performClick()
            compose.onNode(hasSetTextAction()).performTextInput("https://youtu.be/aqz-KE-bpKQ")
            compose.onNodeWithText(text(R.string.format_action_m4a)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.option_recommended)).assertIsDisplayed() // ahora es un chip
        } finally {
            (compose.activity.application as KdlApp).uiPrefs.setFavorite("recommended")
        }
    }

    @Test
    fun downloadsTabOpensFromTheBottomBar() {
        compose.onNodeWithText(text(R.string.tab_downloads)).performClick()
        compose.onAllNodesWithText(text(R.string.tab_downloads)).assertCountEquals(2) // título y pestaña
    }

    /** Ajustes desde la barra de abajo (en Inicio todavía no hay otro texto igual). */
    private fun openSettings() {
        compose.onNodeWithText(text(R.string.tab_settings)).performClick()
    }

    private fun openAbout() {
        openSettings()
        compose.onNodeWithText(text(R.string.settings_about)).performScrollTo().performClick()
    }

    @Test
    fun aboutShowsTheLegalNoticeAndTheDiagnostics() {
        openAbout()
        compose.onNodeWithText(text(R.string.about_copyright)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.about_gpl_notice)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.diag_title)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("(API ${Build.VERSION.SDK_INT})", substring = true).performScrollTo().assertIsDisplayed()
        val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)
        compose.onNodeWithText("${pageSize / 1024} KB").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theLicenseOpensFromAbout() {
        openAbout()
        compose.onNodeWithText(text(R.string.about_view_license)).performScrollTo().performClick()
        // El texto se lee del APK en segundo plano; la primera página trae el título oficial.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("GNU GENERAL PUBLIC LICENSE", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text(R.string.license_official_note)).assertIsDisplayed()
    }
}
