package es.topophone.core.survey

import java.io.File

/** Los CSV de `fixtures/replanteo/lineas/` calculados con pyproj y aparte (constitución nº 2), con sus líneas como [StakeLine]. */
object LineFixtures {
    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
    }

    fun rows(name: String): List<Map<String, String>> {
        val lines = File(root, "fixtures/replanteo/lineas/$name").readLines(Charsets.UTF_8).filter {
            it.isNotBlank() && !it.startsWith("#")
        }
        val header = lines.first().split(",")
        return lines.drop(1).map { line -> header.zip(line.split(",")).toMap() }
    }

    /** Las líneas por nombre, como [StakeLine]. */
    val lines: Map<String, StakeLine> by lazy {
        rows("lineas.csv").groupBy { it.getValue("linea") }.mapValues { (_, vertices) ->
            val z = vertices.first().getValue("cota").isNotEmpty()
            StakeLine.create(
                DoubleArray(vertices.size) { vertices[it].getValue("este").toDouble() },
                DoubleArray(vertices.size) { vertices[it].getValue("norte").toDouble() },
                if (z) DoubleArray(vertices.size) { vertices[it].getValue("cota").toDouble() } else null,
                closed = vertices.first().getValue("cerrada") == "1",
            )!!
        }
    }

    fun line(name: String): StakeLine = lines.getValue(name)

    fun Map<String, String>.number(key: String) = getValue(key).toDouble()

    fun Map<String, String>.optional(key: String) = getValue(key).takeIf { it.isNotEmpty() }?.toDouble()
}
