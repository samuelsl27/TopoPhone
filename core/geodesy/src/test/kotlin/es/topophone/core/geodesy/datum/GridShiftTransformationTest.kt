package es.topophone.core.geodesy.datum

import es.topophone.core.geodesy.GeodeticPoint
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.abs
import org.junit.jupiter.api.Test

/** Rejilla de desplazamiento horizontal como transformación de datum, frente al `hgridshift` de PROJ (S033/RF-7, RF-8; ADR-5). */
class GridShiftTransformationTest {

    private val vectors = GridShiftVector.all()
    private val transformation =
        GridShiftTransformation(GridShiftVector.GRID_NAME, ClasspathGridSource("/shift/"), TransformationInfo("SPED2ETV2"))

    @Test
    fun `S033 RF-7 la directa coincide con pyproj a menos de 1 mm en las dos subrejillas`() {
        vectors.size shouldBeGreaterThan 100
        vectors.map { it.group }.toSet() shouldBe setOf("peninsula", "baleares", "borde_dentro", "borde_fuera", "fuera")

        val failures = vectors.filter { it.forward != null }.mapNotNull { v ->
            val out = transformation.forward(v.input).shouldBeInstanceOf<TransformResult.Ok>().point
            val dLat = abs(out.latitudeDeg - v.forward!!.latitudeDeg)
            val dLon = abs(out.longitudeDeg - v.forward.longitudeDeg)
            if (dLat > 5e-9 || dLon > 5e-9) "${v.group} (${v.input.latitudeDeg}, ${v.input.longitudeDeg}): $dLat°, $dLon°" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S033 RF-7 la rejilla no toca la altura`() {
        val v = vectors.first { it.group == "peninsula" }

        transformation.forward(v.input).shouldBeInstanceOf<TransformResult.Ok>().point.ellipsoidalHeightM shouldBe
            (v.input.ellipsoidalHeightM plusOrMinus 0.0)
    }

    @Test
    fun `S033 RF-8 fuera de la rejilla el punto se rechaza con su nombre y sin extrapolar`() {
        val outside = vectors.filter { it.forward == null }
        outside.size shouldBeGreaterThan 8

        outside.forEach { v ->
            transformation.forward(v.input) shouldBe TransformResult.OutsideGrid(GridShiftVector.GRID_NAME)
        }
    }

    @Test
    fun `S033 RF-8 los puntos a 1e-4 grados dentro del borde se calculan y a 1e-4 fuera se rechazan`() {
        vectors.filter { it.group == "borde_dentro" }.forEach {
            transformation.forward(it.input).shouldBeInstanceOf<TransformResult.Ok>()
        }
        vectors.filter { it.group == "borde_fuera" }.forEach {
            transformation.forward(it.input).shouldBeInstanceOf<TransformResult.OutsideGrid>()
        }
    }

    @Test
    fun `S033 RF-8 una entrada no finita se rechaza sin lanzar excepcion`() {
        transformation.forward(GeodeticPoint(Double.NaN, -3.0, 0.0)).shouldBeInstanceOf<TransformResult.NotConverged>()
    }
}
