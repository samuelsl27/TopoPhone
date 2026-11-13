plugins {
    alias(libs.plugins.topophone.jvm.library)
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.geodesy) // S036: Web Mercator de las peticiones WMS (arquitectura: formats puede usar geodesy)
    implementation(libs.kotlinx.serialization.json) // S013: manifiesto y ajustes de la copia (plan, decisiones técnicas)
}
