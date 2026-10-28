plugins {
    alias(libs.plugins.topophone.jvm.library)
}

dependencies {
    api(projects.core.model)
    api(projects.core.gnss)
    api(projects.core.geodesy)
}

dependencies {
    // Solo los tests: los ficheros de ráster de prueba se leen con el lector de core:formats (S045).
    testImplementation(projects.core.formats)
}
