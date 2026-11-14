package es.topophone.core.model.point

import es.topophone.core.model.cogo.CogoEntry
import es.topophone.core.model.cogo.CogoOperation
import es.topophone.core.model.cogo.CogoRef
import es.topophone.core.model.cogo.CogoValue
import es.topophone.core.model.cogo.IntersectionSide
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El punto COGO: origen propio, sin medida, con el rastro del cálculo (S038/RF-22, RF-24). */
class PointOriginCogoTest {

    private val coordinates = PointCoordinates(GeodeticPosition(40.4168, -3.7038, 697.489), 440_290.458, 4_474_257.382, 646.377)
    private val typed = TypedCoordinates(EntryForm.PROJECT_CRS, 440_290.458, 4_474_257.382, 646.377, HeightKind.PROJECT)

    private fun cogoPoint(cogo: CogoRef? = null) = Point(
        id = "p1",
        projectId = "proj",
        name = "7",
        description = "",
        origin = PointOrigin.COGO,
        coordinates = coordinates,
        measurement = null,
        typed = typed,
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 5_000,
        deletedAtMs = null,
        cogo = cogo,
    )

    @Test
    fun `S038 RF-24 el origen COGO existe junto a los demas`() {
        PointOrigin.entries.toSet() shouldBe
            setOf(
                PointOrigin.MEASURED,
                PointOrigin.MANUAL,
                PointOrigin.STAKED,
                PointOrigin.IMPORTED,
                PointOrigin.ON_ALIGNMENT,
                PointOrigin.COGO,
            )
        PointOrigin.valueOf("COGO") shouldBe PointOrigin.COGO
    }

    @Test
    fun `S038 RF-24 un punto COGO no tiene medida y se trata como uno manual`() {
        PointOrigin.COGO.hasMeasurement shouldBe false
        PointOrigin.MANUAL.hasMeasurement shouldBe false
        PointOrigin.MEASURED.hasMeasurement shouldBe true
        PointOrigin.STAKED.hasMeasurement shouldBe true
        cogoPoint().measurement.shouldBeNull()
    }

    @Test
    fun `S038 RF-22 el punto COGO guarda la operacion, las entradas y los valores escritos`() {
        val ref = CogoRef(
            operation = CogoOperation.INTERSECTION_DISTANCE_DISTANCE,
            entries = listOf(
                CogoEntry(role = "first", name = "A", easting = 440_000.0, northing = 4_474_000.0, height = 650.5),
                CogoEntry(role = "second", name = null, easting = 440_100.25, northing = 4_474_000.0, height = null),
            ),
            values = listOf(CogoValue("distance1", 60.0), CogoValue("distance2", 70.125)),
            solution = IntersectionSide.LEFT,
        )

        val point = cogoPoint(ref)

        point.cogo shouldBe ref
        point.cogo!!.entries.map { it.name } shouldBe listOf("A", null)
        point.cogo!!.value("distance2") shouldBe 70.125
        point.cogo!!.value("azimuth") shouldBe null
        // Un punto que no es COGO no lleva rastro.
        cogoPoint().cogo.shouldBeNull()
    }
}
