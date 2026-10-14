plugins {
    alias(libs.plugins.topophone.android.library)
}

android {
    namespace = "es.topophone.data"
}

dependencies {
    implementation(projects.core.model)
}
