package es.topophone.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** Jetpack Compose para un módulo Android (aplicación o biblioteca) ya configurado. */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            pluginManager.withPlugin("com.android.application") {
                extensions.configure<ApplicationExtension> { buildFeatures { compose = true } }
            }
            pluginManager.withPlugin("com.android.library") {
                extensions.configure<LibraryExtension> { buildFeatures { compose = true } }
            }

            dependencies {
                val bom = platform(libs.library("compose-bom"))
                add("implementation", bom)
                add("testImplementation", bom)
                add("implementation", libs.library("compose-ui"))
                add("implementation", libs.library("compose-material3"))
                add("implementation", libs.library("compose-ui-tooling-preview"))
                add("debugImplementation", libs.library("compose-ui-tooling"))
                add("testImplementation", libs.library("compose-ui-test-junit4"))
                add("debugImplementation", libs.library("compose-ui-test-manifest"))
            }
        }
    }
}
