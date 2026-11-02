// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** La bienvenida del primer uso: no deja empezar sin aceptar el uso responsable y después no vuelve. */
@RunWith(AndroidJUnit4::class)
class WelcomeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KdlApp

    private fun text(id: Int) = app.getString(id)

    @After
    fun restore() = app.uiPrefs.setOnboarded(true)

    @Test
    fun startNeedsTheResponsibleUseCheckbox() {
        app.uiPrefs.setOnboarded(false)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText(text(R.string.welcome_title)).performScrollTo()
            compose.onNodeWithText(text(R.string.welcome_start)).performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText(text(R.string.welcome_accept)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.welcome_start)).performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithText(text(R.string.home_link_title)).performScrollTo()
        }
    }
}
