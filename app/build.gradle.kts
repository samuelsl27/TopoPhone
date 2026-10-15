plugins {
    alias(libs.plugins.topophone.android.application)
    alias(libs.plugins.topophone.android.compose)
    alias(libs.plugins.topophone.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.aboutlibraries)
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
    implementation(projects.receiver)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.aboutlibraries.compose.m3)

    // Base de datos en memoria para los tests de las pantallas de proyectos (S006).
    testImplementation(libs.room.runtime)
}

// Licencias de terceros para "Licencias y atribuciones" (S001/RF-23), generadas al compilar sin red
// (constitución nº 4). Modo estricto: solo licencias compatibles con GPL-3.0 (RNF-T14).
aboutLibraries {
    offlineMode = true
    collect {
        fetchRemoteLicense = false
        fetchRemoteFunding = false
    }
    export {
        excludeFields.addAll("funding")
    }
    license {
        strictMode = com.mikepenz.aboutlibraries.plugin.StrictMode.FAIL
        allowedLicenses.addAll(
            "Apache-2.0",
            "MIT",
            "BSD-2-Clause",
            "BSD-3-Clause",
            "ISC",
            "LGPL-2.1-or-later",
            "LGPL-3.0-or-later",
            "GPL-3.0-or-later",
            "GPL-3.0-only",
        )
    }
}
