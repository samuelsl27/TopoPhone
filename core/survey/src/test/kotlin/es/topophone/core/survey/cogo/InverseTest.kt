package es.topophone.core.survey.cogo

import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.cogo.CogoFixtures.ANGLE_TOLERANCE_RAD
import es.topophone.core.survey.cogo.CogoFixtures.LENGTH_TOLERANCE_M
import es.topophone.core.survey.cogo.CogoFixtures.number
import es.topophone.core.survey.cogo.CogoFixtures.optional
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** La inversa entre dos puntos, frente a un cálculo aparte (S038/RF-6 a RF-8). */
class InverseTest {

    private fun inverse(row: Map<String, String>): InverseResult {
        val crs = CogoFixtures.crs(row)
        val points = CogoFixtures.points(crs)
        val mode = if (row.getValue("modo") == "ground") DistanceMode.GROUND else DistanceMode.GRID
        val a = points.getValue(row.getValue("punto_a"))
        val b = points.getValue(row.getValue("punto_b"))
        return Inverse.of(a, b, CogoScale.forFirst(a, crs, mode))
    }

    @Test
    fun `S038 RF-6 distancias azimuts y diferencias coinciden con la referencia en todos los casos`() {
        val rows = CogoFixtures.rows("inversas.csv")
        rows.size shouldBe 3 * 14 * 2
        for (row in rows) {
            val result = inverse(row)
            val label = "${row["caso"]} ${row["crs"]} ${row["modo"]}"

            withClue(label) {
                result.gridDistance shouldBe (row.number("dist_cuadricula") plusOrMinus LENGTH_TOLERANCE_M)
                result.groundDistance shouldBe (row.number("dist_terreno") plusOrMinus LENGTH_TOLERANCE_M)
                result.dEasting shouldBe (row.number("d_este") plusOrMinus LENGTH_TOLERANCE_M)
                result.dNorthing shouldBe (row.number("d_norte") plusOrMinus LENGTH_TOLERANCE_M)
                val az = row.optional("azimut_directo_rad")
                if (az == null) {
                    result.azimuthRad.shouldBeNull()
                    result.reverseAzimuthRad.shouldBeNull()
                } else {
                    CogoFixtures.angleDiff(result.azimuthRad!!, az) shouldBe (0.0 plusOrMinus ANGLE_TOLERANCE_RAD)
                    CogoFixtures.angleDiff(result.reverseAzimuthRad!!, row.number("azimut_inverso_rad")) shouldBe
                        (0.0 plusOrMinus ANGLE_TOLERANCE_RAD)
                }
            }
        }
    }

    @Test
    fun `S038 RF-7 con altura en los dos puntos salen la distancia inclinada y la pendiente de la distancia activa`() {
        for (row in CogoFixtures.rows("inversas.csv")) {
            val result = inverse(row)
            val dh = row.optional("d_altura")
            if (dh == null) {
                result.dHeight.shouldBeNull()
                result.slantDistance.shouldBeNull()
                result.slopePercent.shouldBeNull()
            } else {
                result.dHeight!! shouldBe (dh plusOrMinus LENGTH_TOLERANCE_M)
                result.slantDistance!! shouldBe (row.number("dist_inclinada") plusOrMinus LENGTH_TOLERANCE_M)
                val slope = row.optional("pendiente_pct")
                if (slope == null) result.slopePercent.shouldBeNull() else result.slopePercent!! shouldBe (slope plusOrMinus 1e-6)
            }
        }
    }

    @Test
    fun `S038 RF-8 dos puntos coincidentes dan distancia 0 y azimut indefinido sin error`() {
        val row = CogoFixtures.rows("inversas.csv").first { it["caso"] == "coincidentes" && it["modo"] == "grid" }

        val result = inverse(row)

        result.coincident shouldBe true
        result.gridDistance shouldBe 0.0
        result.groundDistance shouldBe 0.0
        result.horizontalDistance shouldBe 0.0
        result.azimuthRad.shouldBeNull()
        result.reverseAzimuthRad.shouldBeNull()
        result.slopePercent.shouldBeNull() // con altura, pero distancia 0
        result.dHeight shouldBe 0.0
    }

    @Test
    fun `S038 RF-6 la distancia activa es la de cuadricula o la de terreno segun el modo`() {
        val crs = ProjectCrs.ETRS89_UTM30
        val points = CogoFixtures.points(crs)
        val a = points.getValue("P1")
        val b = points.getValue("P6")

        val grid = Inverse.of(a, b, CogoScale.forFirst(a, crs, DistanceMode.GRID))
        val ground = Inverse.of(a, b, CogoScale.forFirst(a, crs, DistanceMode.GROUND))

        grid.horizontalDistance shouldBe grid.gridDistance
        ground.horizontalDistance shouldBe ground.groundDistance
        (ground.groundDistance > ground.gridDistance) shouldBe true
    }
}
