// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** kdownloader://download?url=… (contrato A8): llega a MainActivity y solo muestra un enlace http(s) válido. */
@RunWith(AndroidJUnit4::class)
class DeepLinkTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun deepLinkResolvesToMainActivity() {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse("kdownloader://download?url=x"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(context.packageName)
        assertEquals(MainActivity::class.java.name, resolve(view)?.activityInfo?.name)
    }

    @Test
    fun deepLinkFillsTheLinkBox() {
        val url = "https://youtu.be/aqz-KE-bpKQ?si=web"
        ActivityScenario.launch<MainActivity>(deepLink(Uri.encode(url))).use {
            compose.onNodeWithText(url).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.home_link_title)).assertIsDisplayed()
        }
    }

    @Test
    fun deepLinkWithoutAWebLinkShowsTheUsualHome() {
        ActivityScenario.launch<MainActivity>(deepLink(Uri.encode("javascript:alert(1)"))).use {
            compose.onNodeWithText(context.getString(R.string.home_link_title)).assertIsDisplayed()
            compose.onAllNodesWithText("javascript:", substring = true).assertCountEquals(0)
        }
    }

    /** Intent explícito con la misma acción y datos que manda el navegador (ActivityScenario compara intents). */
    private fun deepLink(encodedUrl: String): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("kdownloader://download?url=$encodedUrl"))

    private fun resolve(intent: Intent): ResolveInfo? = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.resolveActivity(intent, 0)
    }
}
