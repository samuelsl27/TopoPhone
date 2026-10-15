plugins {
    alias(libs.plugins.topophone.android.library)
    alias(libs.plugins.topophone.hilt)
}

android {
    namespace = "es.topophone.receiver"
}

dependencies {
    api(projects.core.gnss)
    api(projects.core.ntrip)
    api(projects.core.survey)
    implementation(libs.kotlinx.coroutines.core)
}
