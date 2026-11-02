// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.ui.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LicenseTextTest {
    @Test
    fun joinsTheLinesOfEachParagraph() {
        val raw = "  Title line\n   second line\n\n  0. Definitions.\n\n\"This License\" refers\nto version 3.\r\n\r\nEnd.\n"
        assertEquals(
            listOf("Title line second line", "0. Definitions.", "\"This License\" refers to version 3.", "End."),
            LicenseText.paragraphs(raw),
        )
    }

    @Test
    fun readsTheRealGplText() {
        // Las pruebas de Gradle corren en android/app/: el APK lleva este mismo archivo (tarea kdLicenseAssets).
        val license = File(System.getProperty("user.dir"), "../LICENSE")
        assertTrue("No se encontró android/LICENSE desde ${license.absolutePath}", license.isFile)
        val paragraphs = LicenseText.paragraphs(license.readText(Charsets.UTF_8))
        assertEquals("GNU GENERAL PUBLIC LICENSE Version 3, 29 June 2007", paragraphs.first())
        assertTrue(paragraphs.any { it == "TERMS AND CONDITIONS" })
        assertTrue(paragraphs.last().endsWith("<https://www.gnu.org/licenses/why-not-lgpl.html>."))
        assertTrue(paragraphs.size > 100)
        assertTrue(paragraphs.none { '\n' in it })
    }
}
