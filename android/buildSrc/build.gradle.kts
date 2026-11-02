// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Tareas propias del build (src/main/kotlin/KdTasks.kt). buildSrc es un build aparte: usa el Kotlin que
// trae Gradle y solo necesita la API de Gradle.
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}
