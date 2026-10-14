package es.topophone.buildlogic.release

import com.android.build.api.variant.BuiltArtifactsLoader
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

private fun fail(errors: List<String>) {
    if (errors.isNotEmpty()) throw GradleException(errors.joinToString("\n"))
}

/** `verifyReleaseTag -PreleaseTag=vX.Y.Z -PlastPublishedTag=vA.B.C` (S001/RF-31, S001/RF-33). */
abstract class VerifyReleaseTagTask : DefaultTask() {

    @get:Input
    @get:Optional
    abstract val releaseTag: Property<String>

    @get:Input
    @get:Optional
    abstract val lastPublishedTag: Property<String>

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun verify() {
        val tag = releaseTag.orNull?.takeIf { it.isNotBlank() }
            ?: throw GradleException("Indica la etiqueta a publicar con -PreleaseTag=vX.Y.Z (S001/RF-31).")
        fail(ReleaseChecks.checkTag(tag, lastPublishedTag.orNull))
        logger.lifecycle("Etiqueta $tag válida y mayor que la última publicada (${lastPublishedTag.orNull ?: "ninguna"}).")
    }
}

/**
 * `verifyReleaseSigning`: la firma de publicación está disponible (S001/RF-32). Lee el entorno
 * al ejecutarse para que ningún secreto quede guardado como entrada de la tarea.
 */
abstract class VerifyReleaseSigningTask : DefaultTask() {

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun verify() {
        val env = ReleaseChecks.SIGNING_VARIABLES.associateWith { System.getenv(it) }
        fail(ReleaseChecks.checkSigning(env) { File(it).isFile })
        logger.lifecycle("Firma de publicación disponible.")
    }
}

/** `verifyReleaseCertificate`: el APK release está firmado con el certificado registrado (S001/RF-34). */
abstract class VerifyReleaseCertificateTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty

    @get:Internal
    abstract val builtArtifactsLoader: Property<BuiltArtifactsLoader>

    @get:Internal
    abstract val apksigner: RegularFileProperty

    @get:Internal
    abstract val expectedFingerprintFile: RegularFileProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun verify() {
        val apk = builtArtifactsLoader.get().load(apkDirectory.get())?.elements?.singleOrNull()?.outputFile
            ?: throw GradleException("No se encuentra el APK release (S001/RF-34).")
        val output = ByteArrayOutputStream()
        exec.exec {
            commandLine(apksigner.get().asFile.absolutePath, "verify", "--print-certs", apk)
            standardOutput = output
            errorOutput = output
            isIgnoreExitValue = true
        }
        val expected = expectedFingerprintFile.get().asFile.takeIf { it.isFile }?.readText()
        fail(ReleaseChecks.checkCertificate(expected, output.toString(Charsets.UTF_8)))
        logger.lifecycle("El APK está firmado con el certificado de publicación registrado.")
    }
}
