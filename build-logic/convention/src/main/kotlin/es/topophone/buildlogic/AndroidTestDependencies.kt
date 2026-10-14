package es.topophone.buildlogic

import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

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
