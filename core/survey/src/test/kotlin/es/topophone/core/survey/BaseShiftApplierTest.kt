package es.topophone.core.survey

import es.topophone.core.geodesy.Ecef
import es.topophone.core.model.point.BaseShiftVector
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.project.HeightSystem
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import org.junit.jupiter.api.Test

/** Sumar el desplazamiento a la punta antes del SC, frente a pyproj (S020/RF-7; constitución nº 2). */
class BaseShiftApplierTest {

    private val positions = ProjectPositionCalculator()

    @Test
    fun `S020 RF-7 las coordenadas del SC tras aplicar el vector coinciden con pyproj a menos de 1 mm`() {
        BaseShiftFixtures.applied.size shouldBeGreaterThan 20

        val failures = BaseShiftFixtures.applied.mapNotNull { row ->
            val ground = BaseShiftApplier.apply(
                GeodeticPosition(row.lat, row.lon, row.height),
                BaseShiftVector(row.dx, row.dy, row.dz),
            )
            val position = positions.fromGround(ground, row.crs, HeightSystem.ELLIPSOIDAL)
            val d = maxOf(
                abs(position.easting!! - row.easting),
                abs(position.northing!! - row.northing),
                abs(position.height!! - row.projectHeight),
            )
            if (d > 0.001) "${row.name}: ${d * 1000} mm" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S020 RF-7 la ida y vuelta con el vector contrario vuelve al punto a menos de 0,1 mm`() {
        BaseShiftFixtures.applied.forEach { row ->
            val original = GeodeticPosition(row.lat, row.lon, row.height)
            val v = BaseShiftVector(row.dx, row.dy, row.dz)

            val back = BaseShiftApplier.apply(BaseShiftApplier.apply(original, v), BaseShiftVector(-row.dx, -row.dy, -row.dz))

            Ecef.fromGeodetic(back.latitudeDeg, back.longitudeDeg, back.ellipsoidalHeightM!!)
                .distanceTo(Ecef.fromGeodetic(row.lat, row.lon, row.height)) shouldBe (0.0 plusOrMinus 1e-4)
        }
    }

    @Test
    fun `S020 RF-7 sin desplazamiento la punta queda tal cual`() {
        val ground = GeodeticPosition(40.4168, -3.7038, 650.123)

        BaseShiftApplier.apply(ground, null) shouldBe ground
    }

    @Test
    fun `S020 RF-7 una punta sin altura se desplaza en planta y sigue sin altura`() {
        val ground = GeodeticPosition(40.4168, -3.7038, null)

        val shifted = BaseShiftApplier.apply(ground, BaseShiftVector(2.0, -1.0, 0.5))

        shifted.ellipsoidalHeightM.shouldBeNull()
        (shifted.latitudeDeg != ground.latitudeDeg) shouldBe true
    }
}
