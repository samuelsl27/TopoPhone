plugins {
    alias(libs.plugins.topophone.jvm.library)
}

dependencies {
    api(projects.core.model)
    api(projects.core.gnss)
    api(projects.core.geodesy)
}
