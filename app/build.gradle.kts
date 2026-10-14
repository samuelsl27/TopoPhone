plugins {
    alias(libs.plugins.topophone.android.application)
    alias(libs.plugins.topophone.android.compose)
}

android {
    namespace = "es.topophone.app"

    defaultConfig {
        applicationId = "es.topophone.app" // S001/RF-1
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.designsystem)
    implementation(projects.data)
}
