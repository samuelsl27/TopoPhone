package es.topophone.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import es.topophone.buildlogic.release.GitVersioning
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

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
            }

            configureKotlinCompilation()
            addAndroidUnitTestDependencies()
        }
    }
}
