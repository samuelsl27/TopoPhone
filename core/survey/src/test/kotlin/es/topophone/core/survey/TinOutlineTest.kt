package es.topophone.core.survey

import es.topophone.core.model.surface.SurfaceRing
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.time.measureTime
import org.junit.jupiter.api.Test

/**
 * El contorno de una malla frente a `fixtures/superficies/contornos/`, calculado aparte por `tools/superficies/generar_contornos.py`
 * con otro método (qué lado de cada arista cae dentro) (S061/RF-6).
 */
class TinOutlineTest {

    private class Case(
        val easting: DoubleArray,
        val northing: DoubleArray,
        val triangles: IntArray,
        val rings: List<Pair<String, List<Pair<Double, Double>>>>,
    )

    private fun case(name: String): Case {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        val e = ArrayList<Double>()
        val n = ArrayList<Double>()
        val t = ArrayList<Int>()
        val rings = ArrayList<Pair<String, List<Pair<Double, Double>>>>()
        File(root, "fixtures/superficies/contornos/$name.txt").readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .forEach { line ->
                val parts = line.split(',')
                when (parts[0]) {
                    "V" -> {
                        e += parts[1].toDouble()
                        n += parts[2].toDouble()
                    }
                    "T" -> t += parts.drop(1).map { it.toInt() }
                    "R" -> rings += parts[1] to parts[2].split(';').map { p -> p.split(' ').let { it[0].toDouble() to it[1].toDouble() } }
                }
            }
        return Case(e.toDoubleArray(), n.toDoubleArray(), t.toIntArray(), rings)
    }

    /** El anillo empezando por su vértice de menor Este y, a igualdad, menor Norte, como en la referencia. */
    private fun canonical(ring: SurfaceRing): Pair<String, List<Pair<Double, Double>>> {
        val points = ring.easting.indices.map { ring.easting[it] to ring.northing[it] }
        val start = points.indices.minWith(compareBy({ points[it].first }, { points[it].second }))
        return (if (ring.exterior) "EXT" else "HOLE") to (points.drop(start) + points.take(start))
    }

    private fun check(name: String, triangles: (IntArray) -> IntArray = { it }) {
        val c = case(name)

        val outline = TinOutline.rings(c.easting, c.northing, triangles(c.triangles))

        outline.rings.map { canonical(it) }.sortedWith(compareBy({ it.first }, { it.second[0].first }, { it.second[0].second })) shouldBe
            c.rings
    }

    @Test
    fun `S061 RF-6 un cuadrado de dos triangulos da un exterior de cuatro vertices`() = check("cuadrado")

    @Test
    fun `S061 RF-6 una malla con un hueco da el exterior antihorario y el hueco horario`() = check("hueco")

    @Test
    fun `S061 RF-6 dos islas dan dos exteriores`() = check("islas")

    @Test
    fun `S061 RF-6 dos anillos que se tocan en un vertice no se mezclan`() = check("vertice")

    @Test
    fun `S061 RF-6 el sentido en que vienen los triangulos no cambia el contorno`() = check("hueco") { t ->
        // Cada triángulo a, b, c pasa a a, c, b: todos en sentido horario.
        IntArray(t.size) { i -> t[i - i % 3 + (3 - i % 3) % 3] }
    }

    @Test
    fun `S061 RF-6 sin triangulos no hay contorno`() {
        TinOutline.rings(doubleArrayOf(0.0), doubleArrayOf(0.0), IntArray(0)).rings shouldBe emptyList()
    }

    @Test
    fun `S061 RF-6 el contorno de un millon de triangulos tarda menos de 2 s`() {
        val side = 708 // 1.002.528 triángulos
        val n = side + 1
        val e = DoubleArray(n * n) { 440_000.0 + (it % n) * 2.0 }
        val no = DoubleArray(n * n) { 4_474_000.0 + (it / n) * 2.0 }
        val t = IntArray(side * side * 6)
        var k = 0
        for (j in 0 until side) {
            for (i in 0 until side) {
                val a = j * n + i
                for (v in intArrayOf(a, a + 1, a + n + 1, a, a + n + 1, a + n)) t[k++] = v
            }
        }
        TinOutline.rings(e, no, t) // calentar la JIT

        var rings = 0
        val elapsed = measureTime { rings = TinOutline.rings(e, no, t).rings.size }

        println("Contorno de ${t.size / 3} triángulos: ${elapsed.inWholeMilliseconds} ms")
        rings shouldBe 1
        elapsed.inWholeMilliseconds shouldBeLessThan 2_000L
    }
}
