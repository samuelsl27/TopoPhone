package es.topophone.buildlogic

import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/** Infraestructura de tests unitarios de los módulos Android: JUnit 4 + Robolectric + kotest. */
internal fun Project.addAndroidUnitTestDependencies() {
    dependencies {
        add("testImplementation", libs.library("junit4"))
        add("testImplementation", libs.library("kotest-assertions"))
        add("testImplementation", libs.library("robolectric"))
        add("testImplementation", libs.library("androidx-test-ext-junit"))
        add("testImplementation", libs.library("kotlinx-coroutines-test"))
        add("testImplementation", libs.library("turbine"))
    }
}

/**
 * Los tests `*PerformanceTest` comprueban requisitos no funcionales de tiempo (RNF) con límites pensados para el equipo de desarrollo. En
 * el runner compartido del CI (variable `CI`) los tiempos varían demasiado y dan falsos fallos, así que ahí no se ejecutan; en local sí.
 */
internal fun Test.excludePerformanceTestsInCi() {
    if (System.getenv("CI") != null) filter.excludeTestsMatching("*PerformanceTest")
}

/** Robolectric accede a internals del JDK que el JDK 17+ no exporta por defecto. */
internal fun Project.configureRobolectricJvm() {
    tasks.withType<Test>().configureEach {
        jvmArgs(
            "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
            "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
            "--add-opens=java.base/java.io=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
        )
        // 3 GB: el módulo app ya pasa de 3.800 tests con Robolectric y con 2 GB la JVM de tests se quedaba sin memoria (S037/T15), con cientos de OutOfMemoryError.
        maxHeapSize = "3g"
        excludePerformanceTestsInCi()
        // Hilt (KSP) genera fuentes de test aunque el módulo aún no tenga tests.
        failOnNoDiscoveredTests.set(false)
    }
}
