plugins {
    alias(libs.plugins.topophone.android.application)
    alias(libs.plugins.topophone.android.compose)
    alias(libs.plugins.topophone.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "es.topophone.app"

    defaultConfig {
        applicationId = "es.topophone.app" // S001/RF-1
        buildConfigField("String", "REPO_URL", "\"https://github.com/samuelsl27/TopoPhone\"") // S001/RF-20
    }

    androidResources {
        // Idiomas de la app: español (por defecto) e inglés (S001/RF-16, S001/RF-17).
        generateLocaleConfig = true
        localeFilters += listOf("es", "en")
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.designsystem)
    implementation(projects.data)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
}
