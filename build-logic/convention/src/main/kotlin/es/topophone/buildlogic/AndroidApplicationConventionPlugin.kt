package es.topophone.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import es.topophone.buildlogic.quality.CheckNoTelemetryTask
import es.topophone.buildlogic.release.GitVersioning
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")

            val git = GitVersioning(this)
            val version = git.versionInfo.get()

            extensions.configure<ApplicationExtension> {
                compileSdk = libs.intVersionOf("compileSdk")
                defaultConfig {
                    minSdk = libs.intVersionOf("minSdk")
                    targetSdk = libs.intVersionOf("targetSdk")
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                    // Versión desde la etiqueta git vX.Y.Z; sin etiqueta, "-dev" (S001/RF-21, S001/RF-30).
                    versionCode = version.versionCode
                    versionName = version.versionName
                    buildConfigField("String", "GIT_COMMIT", "\"${git.commit.get()}\"") // S001/RF-20
                }
                compileOptions {
                    sourceCompatibility = TARGET_JAVA_VERSION
                    targetCompatibility = TARGET_JAVA_VERSION
                }
                buildFeatures {
                    buildConfig = true
                }
                testOptions {
                    unitTests.isIncludeAndroidResources = true
                }
                lint {
                    configureTopoPhoneLint(isApplication = true)
                }
            }

            configureKotlinCompilation()
            addAndroidUnitTestDependencies()
            configureKtlint()
            registerHardcodedUiTextCheck()
            registerTelemetryCheck()
        }
    }

    /** `checkNoTelemetry`: ninguna variante release puede llevar SDK de telemetría (S001/RF-24). */
    private fun Project.registerTelemetryCheck() {
        val aggregate = tasks.register("checkNoTelemetry") {
            group = "verification"
            description = "Comprueba que ninguna variante release incluye SDK de analítica o de informes de errores (S001/RF-24)."
        }
        extensions.configure<ApplicationAndroidComponentsExtension> {
            onVariants(selector().withBuildType("release")) { variant ->
                val variantTask = tasks.register<CheckNoTelemetryTask>(
                    "checkNoTelemetry${variant.name.replaceFirstChar(Char::uppercase)}",
                ) {
                    group = "verification"
                    coordinates.set(
                        variant.runtimeConfiguration.incoming.resolutionResult.rootComponent.map(CheckNoTelemetryTask::collectCoordinates),
                    )
                    report.set(layout.buildDirectory.file("reports/quality/telemetry-${variant.name}.txt"))
                }
                aggregate.configure { dependsOn(variantTask) }
            }
        }
        tasks.named("check") { dependsOn(aggregate) }
    }
}
