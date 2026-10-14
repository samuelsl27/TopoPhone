plugins {
    `kotlin-dsl`
}

group = "es.topophone.buildlogic"

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.compose.compiler.gradle.plugin)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "topophone.android.application"
            implementationClass = "es.topophone.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "topophone.android.library"
            implementationClass = "es.topophone.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "topophone.android.compose"
            implementationClass = "es.topophone.buildlogic.AndroidComposeConventionPlugin"
        }
        register("jvmLibrary") {
            id = "topophone.jvm.library"
            implementationClass = "es.topophone.buildlogic.JvmLibraryConventionPlugin"
        }
        register("hilt") {
            id = "topophone.hilt"
            implementationClass = "es.topophone.buildlogic.HiltConventionPlugin"
        }
    }
}
