package es.topophone.core.survey

import io.kotest.matchers.collections.shouldBeEmpty
import java.io.File
import org.junit.jupiter.api.Test

/**
 * Ningún código de la app calcula a mano la UTM o la rejilla de ED50 del SC del proyecto: todo pasa por el [CoordinateEngine], que es lo que
 * distingue un SC de España de uno de la biblioteca (S034/RF-26, RF-28). Las excepciones son las lecturas de formatos de intercambio, que
 * declaran su propio SC de origen y pasan a la biblioteca en S057.
 */
class UtmUsageRuleTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() && File(it, "core").isDirectory }

    private val allowedFiles = setOf(
        "core/survey/src/main/kotlin/es/topophone/core/survey/CoordinateEngine.kt", // el motor de España
        "core/survey/src/main/kotlin/es/topophone/core/survey/geometry/GroundPosition.kt", // el de España por datum y huso sueltos
        // Formatos de intercambio con su SC de origen (→ S057):
        "core/survey/src/main/kotlin/es/topophone/core/survey/CadPlacement.kt",
        "core/survey/src/main/kotlin/es/topophone/core/survey/ImportAreaFilter.kt",
        "core/survey/src/main/kotlin/es/topophone/core/survey/RasterElevation.kt",
        "core/survey/src/main/kotlin/es/topophone/core/survey/GroundPoint.kt",
    )

    @Test
    fun `S034 RF-26 solo el motor y los formatos llaman a Utm y a Ed50`() {
        val pattern = Regex("""\b(Utm\.(forward|inverse|factors)|Ed50\.(toEtrs89|fromEtrs89))\b""")
        val offenders = listOf("core/survey", "core/formats", "data", "app", "receiver").flatMap { module ->
            File(root, "$module/src/main").walkTopDown().filter { it.extension == "kt" }.toList()
        }.filter { file ->
            val relative = file.relativeTo(root).invariantSeparatorsPath
            relative !in allowedFiles && pattern.containsMatchIn(file.readText())
        }.map { it.relativeTo(root).invariantSeparatorsPath }

        offenders.shouldBeEmpty()
    }
}
