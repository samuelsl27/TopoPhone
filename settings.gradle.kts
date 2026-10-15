pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Descarga automática de JDK para las toolchains (CI y equipos sin JDK 21).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "TopoPhone"

include(":app")
include(":core:model")
include(":core:designsystem")
include(":core:gnss")
include(":core:ntrip")
include(":core:geodesy")
include(":core:survey")
include(":data")
include(":receiver")
