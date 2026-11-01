// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader

import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** La app abre, dice que es una versión de prueba y muestra los avisos legales y el diagnóstico. */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun text(id: Int): String = compose.activity.getString(id)

    @Test
    fun homeShowsTheLinkBoxAndTheFormats() {
        compose.onNodeWithText(text(R.string.home_link_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.option_recommended)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.option_mp3)).assertIsDisplayed()
    }

    @Test
    fun aboutShowsTheLegalNoticeAndTheDiagnostics() {
        compose.onNodeWithContentDescription(text(R.string.home_about)).performClick() // el ícono (i) de la barra
        compose.onNodeWithText(text(R.string.about_copyright)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.about_gpl_notice)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.diag_title)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("(API ${Build.VERSION.SDK_INT})", substring = true).performScrollTo().assertIsDisplayed()
        val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)
        compose.onNodeWithText("${pageSize / 1024} KB").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theLicenseOpensFromAbout() {
        compose.onNodeWithContentDescription(text(R.string.home_about)).performClick() // el ícono (i) de la barra
        compose.onNodeWithText(text(R.string.about_view_license)).performScrollTo().performClick()
        // El texto se lee del APK en segundo plano; la primera página trae el título oficial.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("GNU GENERAL PUBLIC LICENSE", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text(R.string.license_official_note)).assertIsDisplayed()
    }
}
