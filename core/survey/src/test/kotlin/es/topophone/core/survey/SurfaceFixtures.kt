package es.topophone.core.survey

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Una superficie de `fixtures/superficies/` leída con el XML de la JVM, aparte del lector de la app: los vértices en Este, Norte y
 * cota (el fichero los trae como Norte, Este, cota) y los triángulos visibles como tres posiciones de vértice (S042).
 */
class FixtureSurface(val easting: DoubleArray, val northing: DoubleArray, val height: DoubleArray, val triangles: IntArray)

object SurfaceFixtures {
    private val folder: File by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        File(root, "fixtures/superficies")
    }

    fun surface(file: String, name: String): FixtureSurface {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(folder, file))
        val surfaces = document.getElementsByTagName("Surface")
        val element = (0 until surfaces.length).map { surfaces.item(it) as Element }.first { it.getAttribute("name") == name }
        val points = element.getElementsByTagName("P")
        val position = HashMap<String, Int>()
        val e = DoubleArray(points.length)
        val n = DoubleArray(points.length)
        val z = DoubleArray(points.length)
        for (i in 0 until points.length) {
            val p = points.item(i) as Element
            val values = p.textContent.trim().split(Regex("\\s+")).map { it.toDouble() }
            position[p.getAttribute("id")] = i
            n[i] = values[0]
            e[i] = values[1]
            z[i] = values[2]
        }
        val faces = element.getElementsByTagName("F")
        val triangles = ArrayList<Int>()
        for (i in 0 until faces.length) {
            val f = faces.item(i) as Element
            if (f.getAttribute("i") == "1") continue
            f.textContent.trim().split(Regex("\\s+")).forEach { triangles += position.getValue(it) }
        }
        return FixtureSurface(e, n, z, triangles.toIntArray())
    }

    fun terrain() = surface("dos-superficies.xml", "terreno")

    /** Las consultas de `consultas.csv`: tipo, Este, Norte y la cota esperada (null si no hay). */
    fun queries(): List<Query> = SurveyFixtures.rows("consultas.csv", "superficies").map {
        Query(it[0], it[1].toDouble(), it[2].toDouble(), it.getOrNull(3)?.takeIf { z -> z.isNotEmpty() }?.toDouble())
    }

    class Query(val kind: String, val easting: Double, val northing: Double, val height: Double?)
}
