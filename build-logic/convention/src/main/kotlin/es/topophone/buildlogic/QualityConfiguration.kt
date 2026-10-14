package es.topophone.buildlogic

import com.android.build.api.dsl.Lint
import es.topophone.buildlogic.quality.CheckHardcodedUiTextTask
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register

/** Estilo de código con ktlint en cualquier módulo; las reglas viven en `.editorconfig` (S001/RF-28). */
internal fun Project.configureKtlint() {
    pluginManager.apply("org.jlleitschuh.gradle.ktlint")
}

/**
 * Android Lint como puerta de calidad (S001/RF-19, S001/RF-28): las traducciones incompletas,
 * los textos en layouts y los recursos sin usar son errores.
 */
internal fun Lint.configureTopoPhoneLint(isApplication: Boolean) {
    abortOnError = true
    checkReleaseBuilds = true
    error += setOf("MissingTranslation", "ExtraTranslation", "HardcodedText", "MissingDefaultResource")
    if (isApplication) {
        // La app analiza también sus módulos, así un recurso de biblioteca usado por la app no es "sin usar".
        checkDependencies = true
        error += "UnusedResources"
    }
}

/** `checkHardcodedUiText`: textos visibles literales en Compose (S001/RF-19), enganchada a `check`. */
internal fun Project.registerHardcodedUiTextCheck() {
    val projectPath = path // dentro del bloque de la tarea, `path` sería la ruta de la tarea
    val task = tasks.register<CheckHardcodedUiTextTask>("checkHardcodedUiText") {
        group = "verification"
        description = "Busca textos visibles escritos en el código Compose en lugar de en strings.xml (S001/RF-19)."
        modulePath.set(projectPath)
        sources.from(fileTree("src/main") { include("**/*.kt") })
        rootDirectory.set(rootProject.layout.projectDirectory)
        report.set(layout.buildDirectory.file("reports/quality/hardcoded-ui-text.txt"))
    }
    tasks.named("check") { dependsOn(task) }
}

/** Cobertura mínima de líneas en los módulos de dominio (≥ 80 %, ADR-16). */
internal fun Project.configureDomainCoverage() {
    pluginManager.apply("org.jetbrains.kotlinx.kover")
    extensions.configure<KoverProjectExtension> {
        reports {
            verify {
                rule {
                    minBound(DOMAIN_MIN_LINE_COVERAGE)
                }
            }
        }
    }
    tasks.named("check") { dependsOn("koverVerify") }
}

private const val DOMAIN_MIN_LINE_COVERAGE = 80
