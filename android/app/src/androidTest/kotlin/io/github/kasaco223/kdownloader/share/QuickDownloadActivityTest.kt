// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.share

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kasaco223.kdownloader.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La hoja de «Compartir»: el intent llega por el filtro del manifiesto (no por nombre de clase) y la hoja
 * muestra el primer enlace. La actividad se abre con startActivitySync porque vive en su propia tarea
 * (taskAffinity vacío, singleTask), que ActivityScenario no maneja bien.
 */
@RunWith(AndroidJUnit4::class)
class QuickDownloadActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private var activity: Activity? = null

    @After
    fun closeSheet() {
        val sheet = activity ?: return
        instrumentation.runOnMainSync { sheet.finish() }
        // La siguiente prueba abre otra instancia singleTask: esta tiene que haber terminado de cerrarse.
        val deadline = System.currentTimeMillis() + 10_000
        while (!sheet.isDestroyed && System.currentTimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            Thread.sleep(50)
        }
    }

    @Test
    fun shareTargetIsTheQuickDownloadSheet() {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(context.packageName)
        assertEquals(QuickDownloadActivity::class.java.name, resolve(send)?.activityInfo?.name)
        val processText = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").setPackage(context.packageName)
        assertEquals(QuickDownloadActivity::class.java.name, resolve(processText)?.activityInfo?.name)
    }

    @Test
    fun sharedTextShowsTheFirstLink() {
        open(
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "Mira esto: https://youtu.be/aqz-KE-bpKQ?si=prueba y https://vimeo.com/76979871"),
        )
        compose.onNodeWithText("https://youtu.be/aqz-KE-bpKQ?si=prueba").assertIsDisplayed()
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.quick_more_links, 2, 2)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.format_other)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.option_mp3)).assertIsDisplayed()
    }

    @Test
    fun selectedTextWithADomainIsAccepted() {
        open(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").putExtra(Intent.EXTRA_PROCESS_TEXT, "youtu.be/aqz-KE-bpKQ"))
        compose.onNodeWithText("https://youtu.be/aqz-KE-bpKQ").assertIsDisplayed()
    }

    @Test
    fun textWithoutALinkExplainsWhatToDo() {
        open(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "un video muy bueno"))
        compose.onNodeWithText(context.getString(R.string.quick_no_link_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.quick_open_app)).assertIsDisplayed()
    }

    /** Abre la hoja como lo haría el menú «Compartir» de otra app: intent implícito limitado a este paquete. */
    private fun open(intent: Intent) {
        activity = instrumentation.startActivitySync(
            intent.setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        compose.waitForIdle()
    }

    private fun resolve(intent: Intent): ResolveInfo? = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.resolveActivity(intent, 0)
    }
}
