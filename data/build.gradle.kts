plugins {
    alias(libs.plugins.topophone.android.library)
    alias(libs.plugins.topophone.hilt)
    alias(libs.plugins.room)
}

android {
    namespace = "es.topophone.data"
}

// El banco de pruebas de migraciones lee los esquemas exportados desde los assets de los tests (RNF-T6).
androidComponents {
    onVariants { variant ->
        variant.hostTests.values.forEach { it.sources.assets?.addStaticSourceDirectory("schemas") }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(projects.core.model)
    api(projects.core.formats)
    implementation(projects.core.survey)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.okhttp) // S036: GetCapabilities y consulta de informacion de los servicios WMS (ADR-13)
    ksp(libs.room.compiler)
    testImplementation(libs.room.testing)
}
