package es.topophone.core.geodesy.catalog

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.GeodesyFixtures
import es.topophone.core.geodesy.GeodeticPoint
import es.topophone.core.geodesy.datum.ClasspathGridSource
import es.topophone.core.geodesy.datum.GridSource
import es.topophone.core.geodesy.datum.TransformResult
import es.topophone.core.model.project.CorrectionsDatum
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.abs
import org.junit.jupiter.api.Test

/** Las transformaciones del catálogo frente a PROJ, la elección de la mejor y las rejillas que faltan (S034/RF-9, RF-10, RF-11, RF-12). */
class CrsTransformationsTest {
    private val catalog = CrsCatalog.bundled
    private val appGrids = ClasspathGridSource("/shift/")

    @Test
    fun `S034 RF-9 cada opcion del catalogo da el punto de la tuberia de PROJ a menos de 1 mm`() {
        val rows = GeodesyFixtures.rows("catalogo_transformaciones.csv")
        rows.size shouldBeGreaterThan 1000

        val failures = rows.mapNotNull { row ->
            val frame = if (row[1] == "E") CorrectionFrame.ETRS89 else CorrectionFrame.WGS84
            val candidates = catalog.optionsFor(row[0].toInt(), frame).filter { it.name.replace(',', ';') == row[2] }
            if (candidates.isEmpty()) return@mapNotNull "${row[0]} ${row[2]}: no está en el catálogo"
            val input = GeodeticPoint(row[3].toDouble(), row[4].toDouble(), 0.0)
            val best = candidates.minOf { option ->
                when (val result = CatalogTransformations.build(option, appGrids).forward(input)) {
                    is TransformResult.Ok -> maxOf(
                        abs(result.point.latitudeDeg - row[5].toDouble()),
                        abs(
                            result.point.longitudeDeg - row[6].toDouble(),
                        ),
                    )
                    else -> Double.MAX_VALUE
                }
            }
            if (best > 1e-8 + inverseGap(candidates.first())) "${row[0]} ${row[2]} (${row[3]}, ${row[4]}): $best°" else null
        }

        java.io.File("build/catalog-transformation-failures.txt").writeText(failures.joinToString(System.lineSeparator()))
        failures.shouldBeEmpty()
    }

    /**
     * Lo que puede diferir el inverso exacto de S033 del de PROJ, que invierte un Helmert cambiando el signo de los parámetros: R·(s² + Σθ²),
     * es decir (s² + Σθ²) radianes de arco (S033, plan «Cambios al implementar»), en grados.
     */
    private fun inverseGap(option: TransformationOption): Double =
        option.steps.filterIsInstance<StepSpec.Helmert>().filter { it.inverse }.sumOf { h ->
            val s = h.scalePpm * 1e-6
            val rotations = listOf(h.rotationArcSec.first, h.rotationArcSec.second, h.rotationArcSec.third)
            Math.toDegrees(s * s + rotations.sumOf { (it * ARC_SECOND_RAD) * (it * ARC_SECOND_RAD) })
        }

    @Test
    fun `S034 RF-9 RF-11 la ida y la vuelta de cada tipo de paso cierran a 0,1 mm`() {
        val options = listOf(
            catalog.optionsFor(4230, CorrectionFrame.ETRS89).first { it.name == "ED50 to ETRS89 (7)" },
            catalog.optionsFor(4230, CorrectionFrame.ETRS89).first { it.gridNames == listOf("es_ign_SPED2ETV2.tif") },
            catalog.optionsFor(4258, CorrectionFrame.ETRS89).single(),
        )

        options.forEach { option ->
            val transformation = CatalogTransformations.build(option, appGrids)
            val start = GeodeticPoint(40.4, -3.7, 650.0)
            val there = (transformation.forward(start) as TransformResult.Ok).point
            val back = (transformation.inverse(there) as TransformResult.Ok).point

            abs(back.latitudeDeg - start.latitudeDeg) shouldBe (0.0).plusOrMinus(1e-9)
            abs(back.longitudeDeg - start.longitudeDeg) shouldBe (0.0).plusOrMinus(1e-9)
            back.ellipsoidalHeightM shouldBe start.ellipsoidalHeightM.plusOrMinus(1e-4)
        }
    }

    @Test
    fun `S034 RF-12 una rejilla ausente se nombra y una instalada o incluida en la app no falta`() {
        val sped = catalog.optionsFor(4230, CorrectionFrame.ETRS89).first { it.gridNames == listOf("es_ign_SPED2ETV2.tif") }
        val missingSource = GridSource { null }
        val oneLarge = catalog.options.first { option -> option.gridNames.any { catalog.grids.getValue(it).bundled.not() } }
        val name = oneLarge.gridNames.first { !catalog.grids.getValue(it).bundled }

        GridRequirement.missing(catalog, sped) { false }.shouldBeEmpty() // viene en la app
        GridRequirement.missing(catalog, oneLarge) { false }.map { it.name } shouldBe listOf(name)
        GridRequirement.missing(catalog, oneLarge) { it == name }.shouldBeEmpty()
        CatalogTransformations.build(oneLarge, missingSource).missingGrid() shouldBe name
    }

    @Test
    fun `S034 RF-10 se propone la mas precisa cuyo ambito cubre el centro y se ofrecen las demas`() {
        val madrid = TransformationPicker.pick(catalog, 4230, CorrectionFrame.ETRS89, GeoPoint(40.4, -3.7))
        val barcelona = TransformationPicker.pick(catalog, 4230, CorrectionFrame.ETRS89, GeoPoint(41.4, 2.15))
        val anywhere = TransformationPicker.pick(catalog, 4230, CorrectionFrame.ETRS89, null)

        madrid.best.shouldNotBeNull().gridNames shouldBe listOf("es_ign_SPED2ETV2.tif")
        barcelona.best.shouldNotBeNull().gridNames shouldBe listOf("es_cat_icgc_100800401.tif")
        madrid.applicable.size + madrid.others.size shouldBe anywhere.applicable.size
        madrid.others.shouldBeInstanceOf<List<TransformationOption>>()
        madrid.applicable.all { it.area == null || it.area.contains(40.4, -3.7) } shouldBe true
        val accuracies = madrid.applicable.mapNotNull { it.accuracyM }
        accuracies shouldBe accuracies.sorted()
    }

    @Test
    fun `S034 RF-11 REGCAN95 se trata como ETRS89 y WGS 84 como ITRF`() {
        CorrectionsDatum.ETRS89.frame() shouldBe CorrectionFrame.ETRS89
        CorrectionsDatum.REGCAN95.frame() shouldBe CorrectionFrame.ETRS89
        CorrectionsDatum.ITRF_WGS84.frame() shouldBe CorrectionFrame.WGS84
    }

    private companion object {
        const val ARC_SECOND_RAD = 4.84813681109536e-6
    }
}
