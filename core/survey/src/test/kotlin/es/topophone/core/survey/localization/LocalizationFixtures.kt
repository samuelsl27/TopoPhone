package es.topophone.core.survey.localization

import es.topophone.core.model.localization.LocalizationPair
import java.io.File

/** Los vectores de `fixtures/localizacion/` (S039/T1), calculados con otro algoritmo (`fractions`) y con las posiciones de pyproj. */
object LocalizationFixtures {
    /** Un caso: sus pares, si la escala va fija y lo esperado (parámetros, residuos y RMS). */
    data class Case(
        val name: String,
        val fixScale: Boolean,
        val pairs: List<LocalizationPair>,
        val expected: Map<String, String>,
        val residuals: List<Triple<Double, Double, Double>>,
    )

    private fun lines(name: String): List<List<String>> {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        return File(root, "fixtures/localizacion/$name").readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split(',') }
    }

    val cases: List<Case> by lazy {
        val parameterRows = lines("parametros.csv")
        val parameterHeader = parameterRows.first()
        val pairRows = lines("pares.csv")
        parameterRows.drop(1).map { row ->
            val expected = parameterHeader.zip(row).toMap()
            val name = expected.getValue("caso")
            val rows = pairRows.drop(1).filter { it[0] == name }
            Case(
                name = name,
                fixScale = expected.getValue("escala_fija") == "1",
                pairs = rows.map {
                    LocalizationPair(
                        id = "$name-${it[2]}",
                        pointId = it[2],
                        pointName = "P${it[2]}",
                        scE = it[3].toDouble(),
                        scN = it[4].toDouble(),
                        scH = it[5].toDouble(),
                        knownE = it[6].toDouble(),
                        knownN = it[7].toDouble(),
                        knownH = it[8].toDouble(),
                        useHorizontal = it[9] == "1",
                        useVertical = it[10] == "1",
                    )
                },
                expected = expected,
                residuals = rows.map { Triple(it[11].toDouble(), it[12].toDouble(), it[13].toDouble()) },
            )
        }
    }
}
