package es.topophone.core.model.point

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El punto importado: origen propio, sin medida, con el identificador de su importación (S028/RF-31, RF-33). */
class PointImportedTest {

    private val coordinates = PointCoordinates(GeodeticPosition(40.4168, -3.7038, 697.489), 440_290.458, 4_474_257.382, 646.377)
    private val typed = TypedCoordinates(EntryForm.PROJECT_CRS, 440_290.458, 4_474_257.382, 646.377, HeightKind.PROJECT)

    private fun imported(importId: String? = "imp-1", origin: PointOrigin = PointOrigin.IMPORTED) = Point(
        id = "p1",
        projectId = "proj",
        name = "12",
        description = "esquina",
        origin = origin,
        coordinates = coordinates,
        measurement = null,
        typed = typed,
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 5_000,
        deletedAtMs = null,
        importId = importId,
    )

    @Test
    fun `S028 RF-31 el origen importado existe y es distinto del manual y del medido`() {
        PointOrigin.entries.toSet() shouldBe setOf(PointOrigin.MEASURED, PointOrigin.MANUAL, PointOrigin.STAKED, PointOrigin.IMPORTED)
        PointOrigin.IMPORTED shouldBe PointOrigin.valueOf("IMPORTED")
    }

    @Test
    fun `S028 RF-33 un punto importado no tiene medida, como el manual`() {
        PointOrigin.IMPORTED.hasMeasurement shouldBe false
        PointOrigin.MANUAL.hasMeasurement shouldBe false
        PointOrigin.MEASURED.hasMeasurement shouldBe true
        PointOrigin.STAKED.hasMeasurement shouldBe true
    }

    @Test
    fun `S028 RF-31 el punto importado lleva el identificador de su importacion y lo escrito sin redondear`() {
        val point = imported()

        point.importId shouldBe "imp-1"
        point.typed shouldBe typed
        point.measurement.shouldBeNull()
        point.edited shouldBe false
        point.hasHeight shouldBe true
    }

    @Test
    fun `S028 RF-33 sin pasar el identificador un punto no viene de ninguna importacion`() {
        val manual = imported(importId = null, origin = PointOrigin.MANUAL)

        manual.importId.shouldBeNull()
        Point.measured("m1", "proj", "1", "", measurementOf(coordinates), 1_000).importId.shouldBeNull()
    }

    @Test
    fun `S028 RF-31 el resumen de un punto importado lleva su origen`() {
        val summary = imported().summary()

        summary.origin shouldBe PointOrigin.IMPORTED
        summary.name shouldBe "12"
        summary.hasHeight shouldBe true
        summary.stakeCount shouldBe 0
    }

    @Test
    fun `S028 RF-33 copiar un punto importado conserva su identificador`() {
        val renamed = imported().copy(name = "13")

        renamed.importId shouldBe "imp-1"
        renamed.origin shouldBe PointOrigin.IMPORTED
    }

    private fun measurementOf(c: PointCoordinates) = Measurement(
        antenna = c.ground,
        poleHeightM = 1.8,
        quality = PointQuality("FIX", 24, 0.6, 1.0, 0.01, 0.015, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
        rawNmea = "",
        outOfTolerance = emptySet(),
        measuredFromMs = 1_000,
        measuredToMs = 1_200,
        coordinates = c,
    )
}
