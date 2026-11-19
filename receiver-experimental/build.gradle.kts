plugins {
    alias(libs.plugins.topophone.android.library)
}

// S044 (ADR-18, ADR-21): módulo aislado del cliente de la API no oficial del receptor.
// Sin Hilt (ADR-10), sin `data` (no puede tocar puntos ni proyectos) y solo `app` lo usa, con `experimentalImplementation`.
android {
    namespace = "es.topophone.receiver.experimental"
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.receiver)
    implementation(libs.kotlinx.coroutines.core)
}
