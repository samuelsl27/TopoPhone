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
    // OkHttp ya está en el APK (lo trae MapLibre); aquí da el HTTP y el WebSocket (S044 D5, D7).
    implementation(libs.okhttp)
    // Solo `Json.parseToJsonElement`, sin el plugin (S044 D6).
    implementation(libs.kotlinx.serialization.json)

    // S044 D14: servidor HTTP y WebSocket falso solo para los tests; no entra en el APK.
    testImplementation(libs.okhttp.mockwebserver)
}
