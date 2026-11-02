// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Todos los plugins se declaran aquí (sin aplicar) para que se carguen una sola vez en el classpath común.
// AGP 9 trae Kotlin integrado: el plugin de Compose y el de Kotlin/JVM suben KGP a la versión del catálogo.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}
