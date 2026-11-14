package es.topophone.core.model.cogo

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Los tipos del cálculo COGO que se guardan con el punto (S038/RF-22) y en el borrador (S038/RF-26). */
class CogoRefTest {

    @Test
    fun `S038 RF-22 las clases de tramo son las cinco de la spec`() {
        LegKind.entries.map { it.name } shouldBe
            listOf("AZIMUTH", "ANGLE_RIGHT", "ANGLE_LEFT", "DEFLECTION_RIGHT", "DEFLECTION_LEFT")
    }

    @Test
    fun `S038 RF-22 las operaciones guardables son la poligonal y las tres intersecciones`() {
        CogoOperation.entries.map { it.name } shouldBe
            listOf("TRAVERSE", "INTERSECTION_DISTANCE_DISTANCE", "INTERSECTION_DIRECTION_DIRECTION", "INTERSECTION_DISTANCE_DIRECTION")
    }

    @Test
    fun `S038 RF-14 las soluciones se nombran con texto`() {
        IntersectionSide.entries.map { it.name } shouldBe listOf("LEFT", "RIGHT", "FIRST", "SECOND", "SINGLE")
    }

    @Test
    fun `S038 RF-22 una poligonal guarda los tramos hasta el punto con lo que se escribio`() {
        val ref = CogoRef(
            operation = CogoOperation.TRAVERSE,
            entries = listOf(
                CogoEntry("start", "P1", 440_100.0, 4_474_200.0, 650.2),
                CogoEntry("reference", "P0", 440_000.0, 4_474_100.0, null),
            ),
            legs = listOf(
                TraverseLeg(LegKind.AZIMUTH, 0.7853981633974483, 50.0, 0.4),
                TraverseLeg(LegKind.DEFLECTION_RIGHT, 0.5235987755982988, 35.5, null),
            ),
        )

        ref.legs.size shouldBe 2
        ref.legs[1].kind shouldBe LegKind.DEFLECTION_RIGHT
        ref.legs[1].dHeightM shouldBe null
        ref.solution shouldBe null
    }

    @Test
    fun `S038 RF-26 un tramo a medio escribir admite valores sin dar`() {
        val leg = TraverseLeg(LegKind.ANGLE_LEFT, angleRad = null, distanceM = 12.5, dHeightM = null)

        leg.isComplete shouldBe false
        TraverseLeg(LegKind.AZIMUTH, 1.0, 12.5, null).isComplete shouldBe true
    }
}
