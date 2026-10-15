// Cliente NTRIP: no depende de ningún otro módulo del proyecto (docs/arquitectura.md).
plugins {
    alias(libs.plugins.topophone.jvm.library)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
