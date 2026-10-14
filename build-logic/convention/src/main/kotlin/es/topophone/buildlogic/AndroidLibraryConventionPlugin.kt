package es.topophone.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")

            extensions.configure<LibraryExtension> {
                compileSdk = libs.intVersionOf("compileSdk")
                defaultConfig {
                    minSdk = libs.intVersionOf("minSdk")
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }
                compileOptions {
                    sourceCompatibility = TARGET_JAVA_VERSION
                    targetCompatibility = TARGET_JAVA_VERSION
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
