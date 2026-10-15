plugins {
    alias(libs.plugins.topophone.android.library)
    alias(libs.plugins.topophone.hilt)
}

android {
    namespace = "es.topophone.data"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.survey)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
}
