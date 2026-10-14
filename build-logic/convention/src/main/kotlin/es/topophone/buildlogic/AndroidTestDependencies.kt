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

/** Robolectric accede a internals del JDK que el JDK 17+ no exporta por defecto. */
internal fun Project.configureRobolectricJvm() {
    tasks.withType<Test>().configureEach {
        jvmArgs(
            "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
            "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
            "--add-opens=java.base/java.io=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
        )
        maxHeapSize = "2g"
    }
}
