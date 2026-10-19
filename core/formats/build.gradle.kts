plugins {
    alias(libs.plugins.topophone.jvm.library)
}

dependencies {
    api(projects.core.model)
    implementation(libs.kotlinx.serialization.json) // S013: manifiesto y ajustes de la copia (plan, decisiones técnicas)
}
