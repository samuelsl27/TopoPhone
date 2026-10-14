// Los plugins se declaran aquí (sin aplicar) para que compartan un único classpath;
// cada módulo los aplica a través de las convenciones de build-logic.
plugins {
    alias(libs.plugins.topophone.quality)
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// `./gradlew check` también ejecuta los tests de las convenciones y tareas de build-logic.
tasks.named("check") {
    dependsOn(gradle.includedBuild("build-logic").task(":convention:check"))
}
