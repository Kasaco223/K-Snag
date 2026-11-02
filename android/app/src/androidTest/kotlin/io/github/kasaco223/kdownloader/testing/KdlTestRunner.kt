// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.testing

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/**
 * Runner de las pruebas instrumentadas (testInstrumentationRunner en app/build.gradle.kts y ci_emulator.sh).
 * Es el único lugar donde las pruebas pueden cambiar la Application por una con dependencias falsas
 * (newApplication); hoy usa la real, porque esta versión todavía no tiene motor ni red.
 *
 * En CI corre con `-e notAnnotation io.github.kasaco223.kdownloader.testing.NetworkTest`.
 */
class KdlTestRunner : AndroidJUnitRunner() {
    /** Las pruebas arrancan en Inicio: la bienvenida ya se vio (WelcomeTest la vuelve a mostrar). */
    override fun callApplicationOnCreate(app: Application) {
        app.getSharedPreferences("kd_ui", Context.MODE_PRIVATE).edit().putBoolean("onboarded", true).commit()
        super.callApplicationOnCreate(app)
    }
}
