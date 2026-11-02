package es.topophone.core.survey

import es.topophone.core.model.alignment.ElementKind
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** El eje tomado con el receptor frente a `fixtures/ejes/toma/` (S052/RF-3, RF-5, RF-6, RF-7; constitución nº 2). */
class FieldAxisBuilderTest {
    private val points = SurveyFixtures.rows("toma/puntos-eje.csv", "ejes").map { row -> row.drop(1).map { it.toDouble() } }

    private fun vertices(radii: List<Double>, withHeight: Boolean = false) = points.mapIndexed { i, p ->
        FieldVertex(p[0], p[1], if (withHeight) p[2] else null, radii[i])
    }

    private fun built(vertices: List<FieldVertex>, start: Double = 0.0, withGrade: Boolean = false) =
        FieldAxisBuilder.build(vertices, start, withGrade).shouldBeInstanceOf<FieldAxisResult.Built>()

    private fun expected(case: String) = SurveyFixtures.rows("toma/esperado-eje.csv", "ejes").filter { it[0] == case }

    private fun compare(result: FieldAxisResult.Built, case: String) {
        val rows = expected(case)
        result.elements.size shouldBe rows.size
        for ((e, row) in result.elements.zip(rows)) {
            e.kind shouldBe if (row[2] == "line") ElementKind.LINE else ElementKind.ARC
            e.startChainage shouldBe (row[3].toDouble() plusOrMinus 1e-6)
            e.length shouldBe (row[4].toDouble() plusOrMinus 1e-6)
            e.startCurvature shouldBe (row[5].toDouble() plusOrMinus 1e-12)
            e.endCurvature shouldBe (row[6].toDouble() plusOrMinus 1e-12)
            e.startEasting shouldBe (row[7].toDouble() plusOrMinus 1e-6)
            e.startNorthing shouldBe (row[8].toDouble() plusOrMinus 1e-6)
            val end = ElementGeometry.stateAt(e, e.length)
            end.easting shouldBe (row[9].toDouble() plusOrMinus 1e-6)
            end.northing shouldBe (row[10].toDouble() plusOrMinus 1e-6)
        }
    }

    @Test
    fun `S052 RF-5 un eje de rectas por los puntos coincide con tools a menos de 1 mm`() {
        compare(built(vertices(List(5) { 0.0 })), "rectas")
    }

    @Test
    fun `S052 RF-5 un eje con radios en los vertices coincide con tools a menos de 1 mm`() {
        compare(built(vertices(listOf(0.0, 150.0, 200.0, 100.0, 0.0)), 250.0), "radios")
    }

    @Test
    fun `S052 RF-5 el radio de los extremos se ignora`() {
        compare(built(vertices(listOf(80.0, 150.0, 200.0, 100.0, 80.0)), 250.0), "radios")
    }

    @Test
    fun `S052 RF-3 la longitud acumulada es la de la poligonal de los puntos`() {
        val all = vertices(List(5) { 0.0 })
        for (row in SurveyFixtures.rows("toma/esperado-longitud.csv", "ejes")) {
            PolylineLength.of(all.take(row[0].toInt())) shouldBe (row[1].toDouble() plusOrMinus 1e-9)
        }
        PolylineLength.of(emptyList()) shouldBe 0.0
        PolylineLength.of(all.take(1)) shouldBe 0.0
    }

    @Test
    fun `S052 RF-6 cada rechazo da su motivo y su vertice como tools`() {
        val cases = SurveyFixtures.rows("toma/rechazos-eje.csv", "ejes")
            .groupBy({ it[0] }) { FieldVertex(it[2].toDouble(), it[3].toDouble(), null, it[4].toDouble()) }
        val expected = SurveyFixtures.rows("toma/esperado-rechazos.csv", "ejes").associateBy { it[0] }

        cases.keys shouldBe expected.keys
        for ((name, vertices) in cases) {
            val issue = FieldAxisBuilder.build(vertices).shouldBeInstanceOf<FieldAxisResult.Failed>().issue
            val row = expected.getValue(name)
            when (row[1]) {
                "pocos_puntos" -> issue shouldBe FieldAxisIssue.TooFewPoints
                "duplicado" -> issue shouldBe FieldAxisIssue.CoincidentPoints(row[2].toInt())
                else -> issue shouldBe FieldAxisIssue.RadiusDoesNotFit(row[2].toInt())
            }
        }
    }

    @Test
    fun `S052 RF-6 dos puntos a 1 cm exactos se aceptan y a menos de 1 cm no`() {
        FieldAxisBuilder.build(listOf(FieldVertex(0.0, 0.0), FieldVertex(0.01, 0.0))).shouldBeInstanceOf<FieldAxisResult.Built>()
        FieldAxisBuilder.build(listOf(FieldVertex(0.0, 0.0), FieldVertex(0.0099, 0.0)))
            .shouldBeInstanceOf<FieldAxisResult.Failed>().issue shouldBe FieldAxisIssue.CoincidentPoints(2)
    }

    @Test
    fun `S052 RF-6 un radio negativo se rechaza con su vertice`() {
        val v = listOf(FieldVertex(0.0, 0.0), FieldVertex(100.0, 0.0, null, -5.0), FieldVertex(100.0, 100.0))

        FieldAxisBuilder.build(v).shouldBeInstanceOf<FieldAxisResult.Failed>().issue shouldBe FieldAxisIssue.InvalidRadius(2)
    }

    @Test
    fun `S052 RF-7 sin pedirla no hay rasante y con ella los vertices llevan el PK de tools y la cota del punto`() {
        val radii = listOf(0.0, 150.0, 200.0, 100.0, 0.0)
        built(vertices(radii, withHeight = true), 250.0).profile.shouldBeNull()

        val grade = built(vertices(radii, withHeight = true), 250.0, withGrade = true).profile.shouldNotBeNull()
        val expected = SurveyFixtures.rows("toma/esperado-rasante.csv", "ejes")

        grade.vertices.size shouldBe expected.size
        for ((v, row) in grade.vertices.zip(expected)) {
            v.chainage shouldBe (row[1].toDouble() plusOrMinus 1e-6)
            v.elevation shouldBe (row[2].toDouble() plusOrMinus 1e-12)
        }
    }

    @Test
    fun `S052 RF-7 si algun punto no tiene cota no se crea la rasante`() {
        val v = vertices(List(5) { 0.0 }, withHeight = true).toMutableList()
        v[2] = v[2].copy(heightM = null)

        built(v, 0.0, withGrade = true).profile.shouldBeNull()
    }
}
