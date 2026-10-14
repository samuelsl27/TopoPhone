package es.topophone.buildlogic.release

import org.gradle.api.Project
import org.gradle.api.provider.Provider

/**
 * Lee de git, con proveedores compatibles con la caché de configuración, la etiqueta exacta,
 * las etiquetas `v*` y el commit. Fuera de un repositorio git (o sin etiquetas) se obtiene `0.0.0-dev` / `unknown`.
 */
internal class GitVersioning(private val project: Project) {

    val versionInfo: Provider<VersionInfo> =
        gitOutput("describe", "--tags", "--exact-match", "--match", "v*").zip(gitOutput("tag", "--list", "v*")) { exact, tags ->
            VersionInfo.from(
                exactTag = exact.trim().ifEmpty { null },
                allTags = tags.lines().map(String::trim).filter(String::isNotEmpty),
            )
        }

    val commit: Provider<String> = gitOutput("rev-parse", "HEAD").map(GitCommit::normalize)

    private fun gitOutput(vararg args: String): Provider<String> = project.providers.exec {
        commandLine(listOf("git") + args)
        workingDir = project.rootDir
        isIgnoreExitValue = true
    }.standardOutput.asText.orElse("")
}
