package es.topophone.buildlogic.release

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Project
import org.gradle.internal.os.OperatingSystem
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register

/**
 * Publicación (ADR-20): versión release con R8 y firmada con la clave de las variables
 * `TOPOPHONE_*` si existen (en local, sin ellas, el APK release sale sin firmar), y las tareas
 * de comprobación que usa `release.yml` (S001/RF-30 a RF-34).
 *
 * Los secretos solo se leen en la ejecución de publicación, que va sin caché de configuración.
 */
internal fun Project.configureRelease() {
    val env = ReleaseChecks.SIGNING_VARIABLES.associateWith { providers.environmentVariable(it).orNull }
    val signingAvailable = ReleaseChecks.checkSigning(env) { file(it).isFile }.isEmpty()

    extensions.configure<ApplicationExtension> {
        if (signingAvailable) {
            signingConfigs.create("release") {
                storeFile = file(env.getValue("TOPOPHONE_KEYSTORE_FILE")!!)
                storePassword = env.getValue("TOPOPHONE_KEYSTORE_PASSWORD")
                keyAlias = env.getValue("TOPOPHONE_KEY_ALIAS")
                keyPassword = env.getValue("TOPOPHONE_KEY_PASSWORD")
            }
        }
        buildTypes {
            getByName("release") {
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
                if (signingAvailable) signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    tasks.register<VerifyReleaseTagTask>("verifyReleaseTag") {
        group = "publishing"
        description = "Comprueba el formato de la etiqueta y que su versión sea mayor que la última publicada (S001/RF-31, RF-33)."
        releaseTag.set(providers.gradleProperty("releaseTag"))
        lastPublishedTag.set(providers.gradleProperty("lastPublishedTag"))
    }
    tasks.register<VerifyReleaseSigningTask>("verifyReleaseSigning") {
        group = "publishing"
        description = "Comprueba que la firma de publicación está disponible (S001/RF-32)."
    }

    val components = extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
    val android = extensions.getByType(ApplicationExtension::class.java)
    val apksignerName = if (OperatingSystem.current().isWindows) "apksigner.bat" else "apksigner"
    components.onVariants(components.selector().withBuildType("release")) { variant ->
        tasks.register<VerifyReleaseCertificateTask>("verifyReleaseCertificate") {
            group = "publishing"
            description = "Comprueba que el APK release está firmado con el certificado registrado (S001/RF-34)."
            apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
            builtArtifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
            apksigner.set(components.sdkComponents.sdkDirectory.map { it.file("build-tools/${android.buildToolsVersion}/$apksignerName") })
            expectedFingerprintFile.set(rootProject.layout.projectDirectory.file("release/certificado-sha256.txt"))
        }
    }
}
