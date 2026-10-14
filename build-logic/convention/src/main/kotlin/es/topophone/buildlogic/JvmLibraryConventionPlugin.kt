package es.topophone.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/**
 * Módulo de dominio puro (JVM, sin Android): constitución nº 6.
 * Tests con JUnit Jupiter + kotest.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")

            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = TARGET_JAVA_VERSION
                targetCompatibility = TARGET_JAVA_VERSION
            }
            tasks.withType<JavaCompile>().configureEach {
                options.release.set(TARGET_JAVA_VERSION.majorVersion.toInt())
            }
            configureKotlinCompilation()

            dependencies {
                add("testImplementation", platform(libs.library("junit-bom")))
                add("testImplementation", libs.library("junit-jupiter"))
                add("testImplementation", libs.library("kotest-assertions"))
                add("testRuntimeOnly", libs.library("junit-platform-launcher"))
            }
            tasks.withType<Test>().configureEach {
                useJUnitPlatform()
            }

            configureKtlint()
            configureDomainCoverage()
        }
    }
}
