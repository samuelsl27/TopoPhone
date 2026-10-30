package es.topophone.core.survey

import es.topophone.core.geodesy.Ecef
import es.topophone.core.model.point.BaseShiftClass
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.abs
import org.junit.jupiter.api.Test

/** El desplazamiento de base: del punto conocido y la medida al vector ECEF, frente a pyproj (S020/RF-3 a RF-6; constitución nº 2). */
class BaseShiftCalculatorTest {

    private val calculator = BaseShiftCalculator()

    private fun compute(
        row: BaseShiftFixtures.Vector,
        known: KnownPoint = KnownPoint(row.knownEasting, row.knownNorthing, row.knownHeight),
        heights: HeightSystem = HeightSystem.ELLIPSOIDAL,
        acceptHorizontalOnly: Boolean = true,
    ): BaseShiftResult = calculator.compute(
        known = known,
        measured = GeodeticPosition(row.measuredLat, row.measuredLon, row.measuredHeight),
        crs = row.crs,
        heightSystem = heights,
        acceptHorizontalOnly = acceptHorizontalOnly,
    )

    private fun row(name: String) = BaseShiftFixtures.vectors.first { it.name == name }

    private fun groundOf(row: BaseShiftFixtures.Vector, height: Double?, kind: HeightKind, heights: HeightSystem) =
        ManualCoordinates().compute(
            TypedCoordinates(EntryForm.PROJECT_CRS, row.knownEasting, row.knownNorthing, height, kind),
            row.crs,
            heights,
        ).shouldBeInstanceOf<ManualResult.Computed>().coordinates

    @Test
    fun `S020 RF-3 el vector coincide con pyproj a menos de 1 mm en ETRS89, REGCAN95 e ITRF`() {
        BaseShiftFixtures.vectors.size shouldBeGreaterThan 10

        val failures = BaseShiftFixtures.vectors.mapNotNull { row ->
            val result = compute(row) as? BaseShiftResult.Computed ?: return@mapNotNull "${row.name}: no se calculó"
            val d = maxOf(abs(result.vector.dx - row.dx), abs(result.vector.dy - row.dy), abs(result.vector.dz - row.dz))
            if (d > 0.001) "${row.name}: ${d * 1000} mm" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S020 RF-3 la diferencia de datum ITRF queda dentro del vector sin transformar`() {
        val result = compute(row("itrf-sobre-etrs89")).shouldBeInstanceOf<BaseShiftResult.Computed>()

        (result.vector.moduleM in 0.5..1.3) shouldBe true
    }

    @Test
    fun `S020 RF-4 el equivalente en Este, Norte y altura del SC coincide con pyproj`() {
        val failures = BaseShiftFixtures.vectors.mapNotNull { row ->
            val result = compute(row) as BaseShiftResult.Computed
            val d = maxOf(
                abs(result.deltaEastM - row.deltaEast),
                abs(result.deltaNorthM - row.deltaNorth),
                abs(result.deltaHeightM - row.deltaHeight),
            )
            if (d > 0.001) "${row.name}: ${d * 1000} mm" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S020 RF-5 el modulo y la clasificacion coinciden con pyproj`() {
        BaseShiftFixtures.vectors.forEach { row ->
            val result = compute(row) as BaseShiftResult.Computed

            result.vector.moduleM shouldBe (row.module plusOrMinus 0.001)
            result.classification shouldBe BaseShiftClass.valueOf(row.classification)
        }
    }

    @Test
    fun `S020 RF-6 sin altura del punto conocido el vector no corrige la altura`() {
        val case = row("etrs89-sin-altura")

        val result = compute(case).shouldBeInstanceOf<BaseShiftResult.Computed>()

        result.horizontalOnly shouldBe true
        result.deltaHeightM shouldBe (case.deltaHeight plusOrMinus 0.001)
    }

    @Test
    fun `S020 RF-6 con altura el vector si corrige la altura`() {
        compute(row("etrs89-single-3m")).shouldBeInstanceOf<BaseShiftResult.Computed>().horizontalOnly shouldBe false
    }

    @Test
    fun `S020 RF-6 sin altura y sin aceptar solo horizontal no se calcula`() {
        val result = compute(row("etrs89-sin-altura"), acceptHorizontalOnly = false)

        result shouldBe BaseShiftResult.Rejected(PositionUnavailable.NO_HEIGHT)
    }

    @Test
    fun `S020 RF-3 una altitud ortometrica da el mismo vector que su altura elipsoidal`() {
        val case = row("etrs89-single-3m")
        val altitude = groundOf(case, case.knownHeight, HeightKind.ELLIPSOIDAL, HeightSystem.ORTHOMETRIC).height!!

        val ortho = compute(
            case,
            known = KnownPoint(case.knownEasting, case.knownNorthing, altitude),
            heights = HeightSystem.ORTHOMETRIC,
        ).shouldBeInstanceOf<BaseShiftResult.Computed>()

        ortho.vector.dx shouldBe (case.dx plusOrMinus 0.001)
        ortho.vector.dy shouldBe (case.dy plusOrMinus 0.001)
        ortho.vector.dz shouldBe (case.dz plusOrMinus 0.001)
    }

    @Test
    fun `S020 RF-3 sumar el vector a la medida deja la posicion del conocido a menos de 0,1 mm`() {
        BaseShiftFixtures.vectors.filter { it.knownHeight != null }.forEach { case ->
            val result = compute(case) as BaseShiftResult.Computed
            val measured = Ecef.fromGeodetic(case.measuredLat, case.measuredLon, case.measuredHeight)
            val known = groundOf(case, case.knownHeight, HeightKind.PROJECT, HeightSystem.ELLIPSOIDAL).ground
            val knownEcef = Ecef.fromGeodetic(known.latitudeDeg, known.longitudeDeg, known.ellipsoidalHeightM!!)

            val shifted = Ecef(measured.x + result.vector.dx, measured.y + result.vector.dy, measured.z + result.vector.dz)

            shifted.distanceTo(knownEcef) shouldBe (0.0 plusOrMinus 1e-4)
        }
    }

    @Test
    fun `S020 RF-3 un punto conocido fuera del ambito del SC no se calcula`() {
        val result = compute(row("etrs89-single-3m"), known = KnownPoint(0.0, 0.0, 100.0))

        result.shouldBeInstanceOf<BaseShiftResult.Rejected>()
    }

    @Test
    fun `S020 RF-3 el SC del proyecto se respeta en REGCAN95`() {
        compute(row("regcan95-huso28")).shouldBeInstanceOf<BaseShiftResult.Computed>()
        row("regcan95-huso28").crs shouldBe ProjectCrs.REGCAN95_UTM28
    }
}
