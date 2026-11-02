// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
//
// :core = lógica pura en Kotlin, sin android.* (paquete io.github.kasaco223.kdownloader.core). Así el
// compilador impide que se cuele una dependencia de Android y las pruebas corren en la JVM sin emulador.
// Corre en Android 8+ (API 26): compila contra la API de Java 17 aunque Gradle use el JDK 21, y lint la
// revisa con el minSdk de la app desde :app (checkDependencies = true).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.android.lint)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Sin esto, MutableList.removeFirst()/removeLast() compilan contra los métodos de Java 21 (solo
        // Android 15+) y fallan con NoSuchMethodError en API 26-34. List.of (API 30) lo detecta lint.
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    // Datos de la app de Windows que las pruebas leen en su lugar (transcripciones de yt-dlp, catálogo de errores).
    val repo = rootProject.layout.projectDirectory.dir("..")
    jvmArgumentProviders += objects.newInstance(RepoRootArgs::class.java).apply {
        repoRoot.set(repo)
        fixtures.set(repo.dir("tests/fixtures"))
        errorCatalog.set(repo.file("src/kdownloader/engine/errors_catalog.json"))
    }
}
