package es.topophone.core.model.point

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * El informe de replanteo: las filas, el último replanteo de cada punto de diseño, los sin evaluar y el resumen
 * (S019/RF-17, RF-18, RF-19, RF-21, RF-26), frente a `fixtures/replanteo/` calculado aparte (constitución nº 2).
 */
class StakeoutReportTest {

    private val rows = ReplanteoFixtures.informe()
    private val projects = listOf("UTM30", "UTM28", "GEOGRAFICO")

    /** Los puntos replanteados de un proyecto, con el id de cada uno y su fila. */
    private fun staked(project: String): List<Pair<Map<String, String>, Point>> =
        rows.withIndex().filter { it.value.getValue("proyecto") == project }.map { (index, row) ->
            row to
                ReplanteoFixtures.point(row, index)
        }

    private fun measuredPoint(): Point {
        val staked = ReplanteoFixtures.point(rows.first(), 0)
        return Point.measured("m-1", "UTM30", "1", "", staked.measurement!!, createdAtMs = 1_000)
    }

    @Test
    fun `S019 RF-21 hay una fila por cada punto replanteado, ordenadas por nombre natural del diseno y numero de replanteo`() {
        projects.forEach { project ->
            val points = staked(project).map { it.second }

            val report = StakeoutReport.of(points.shuffled(java.util.Random(7)))

            report.rows.size shouldBe 10
            report.rows.map { it.point.name } shouldBe listOf(
                "P1_stkd", "P1_stkd2", "P1_stkd3", "P2_stkd", "P3_stkd", "P4_stkd", "P5_stkd", "P5_stkd2", "P6_stkd", "P10_stkd",
            )
            report.rows.map { it.number } shouldBe listOf(1, 2, 3, 1, 1, 1, 1, 2, 1, 1)
        }
    }

    @Test
    fun `S019 RF-17 el ultimo replanteo de cada punto de diseno es el mas reciente`() {
        projects.forEach { project ->
            val pairs = staked(project)
            val report = StakeoutReport.of(pairs.map { it.second })

            val expected = pairs.associate { (row, point) -> point.id to (row.getValue("ultimo") == "1") }
            report.rows.associate { it.point.id to it.last } shouldBe expected
            report.rows.count { it.last } shouldBe 7
        }
    }

    @Test
    fun `S019 RF-17 el resumen coincide con el calculado aparte en todos los puntos y en una seleccion`() {
        ReplanteoFixtures.resumen().forEach { expected ->
            val project = expected.getValue("proyecto")
            val scope = expected.getValue("ambito")
            val chosen = staked(project).filter { scope == "todos" || it.first.getValue("en_seleccion") == "1" }
            val selection = if (scope == "todos") null else chosen.map { it.second.id }.toSet()

            val summary = StakeoutReport.of(staked(project).map { it.second }, selection).summary

            summary.designPoints shouldBe expected.getValue("puntos").toInt()
            summary.within shouldBe expected.getValue("dentro").toInt()
            summary.outside shouldBe expected.getValue("fuera").toInt()
            summary.notEvaluated shouldBe expected.getValue("sin_evaluar").toInt()
            summary.horizontalMaxM!! shouldBe (expected.getValue("h_max").toDouble() plusOrMinus 1e-4)
            summary.horizontalMeanM!! shouldBe (expected.getValue("h_media").toDouble() plusOrMinus 1e-4)
            summary.verticalCount shouldBe expected.getValue("v_n").toInt()
            summary.verticalMaxM!! shouldBe (expected.getValue("v_max").toDouble() plusOrMinus 1e-4)
            summary.verticalMeanM!! shouldBe (expected.getValue("v_media").toDouble() plusOrMinus 1e-4)
        }
    }

    @Test
    fun `S019 RF-18 con seleccion solo entran los replanteos seleccionados`() {
        val pairs = staked("UTM30")
        val ids = pairs.filter { it.first.getValue("diseno") in setOf("P1", "P3") }.map { it.second.id }.toSet()

        val report = StakeoutReport.of(pairs.map { it.second }, ids)

        report.rows.map { it.point.id }.toSet() shouldBe ids
        report.rows.size shouldBe 4
        report.summary.designPoints shouldBe 2
    }

    @Test
    fun `S019 RF-18 el ultimo se decide entre los seleccionados, no entre todos`() {
        val pairs = staked("UTM30").filter { it.first.getValue("diseno") == "P1" }
        val firstTwo = pairs.take(2).map { it.second.id }.toSet()

        val report = StakeoutReport.of(pairs.map { it.second }, firstTwo)

        report.rows.map { it.number } shouldBe listOf(1, 2)
        report.rows.map { it.last } shouldBe listOf(false, true)
        report.summary.designPoints shouldBe 1
    }

    @Test
    fun `S019 RF-18 un punto seleccionado que no es replanteado se ignora`() {
        val measured = measuredPoint()
        val replanteado = staked("UTM30").first().second

        val report = StakeoutReport.of(listOf(measured, replanteado), setOf(measured.id, replanteado.id))

        report.rows.map { it.point.id } shouldBe listOf(replanteado.id)
    }

    @Test
    fun `S019 RF-18 los puntos medidos no replanteados nunca entran en el informe`() {
        val report = StakeoutReport.of(listOf(measuredPoint()))

        report.rows.size shouldBe 0
        report.isEmpty shouldBe true
    }

    @Test
    fun `S019 RF-19 los puntos de la papelera no entran en el informe`() {
        val points = staked("UTM30").map { it.second }
        val trashed = points[2].copy(deletedAtMs = 5_000)

        val report = StakeoutReport.of(points.take(2) + trashed + points.drop(3))

        report.rows.size shouldBe 9
        report.rows.none { it.point.id == trashed.id } shouldBe true
    }

    @Test
    fun `S019 RF-19 si el ultimo replanteo esta en la papelera el anterior pasa a ser el ultimo`() {
        val p1 = staked("UTM30").filter { it.first.getValue("diseno") == "P1" }.map { it.second }
        val trashedLast = p1.last().copy(deletedAtMs = 5_000)

        val report = StakeoutReport.of(p1.dropLast(1) + trashedLast)

        report.rows.map { it.number } shouldBe listOf(1, 2)
        report.rows.last().last shouldBe true
    }

    @Test
    fun `S019 RF-20 sin ningun punto replanteado el informe esta vacio y el resumen a cero`() {
        val report = StakeoutReport.of(emptyList())

        report.isEmpty shouldBe true
        report.summary.designPoints shouldBe 0
        report.summary.within shouldBe 0
        report.summary.outside shouldBe 0
        report.summary.notEvaluated shouldBe 0
        report.summary.horizontalMaxM.shouldBeNull()
        report.summary.horizontalMeanM.shouldBeNull()
        report.summary.verticalCount shouldBe 0
        report.summary.verticalMaxM.shouldBeNull()
        report.summary.verticalMeanM.shouldBeNull()
    }

    @Test
    fun `S019 RF-26 los replanteos sin tolerancias cuentan como sin evaluar y entran en las desviaciones`() {
        val points = staked("UTM30").map { it.second }.filter { it.staked!!.designName == "P6" }
        points.single().staked!!.notEvaluated shouldBe true

        val summary = StakeoutReport.of(points).summary

        summary.designPoints shouldBe 1
        summary.notEvaluated shouldBe 1
        summary.within shouldBe 0
        summary.outside shouldBe 0
        summary.verticalCount shouldBe 1
        summary.verticalMaxM!! shouldBe (2.0 plusOrMinus 1e-4)
    }

    @Test
    fun `S019 RF-26 el ultimo replanteo decide si el punto de diseno esta dentro aunque el anterior fuera de antes`() {
        val p5 = staked("UTM30").filter { it.first.getValue("diseno") == "P5" }.map { it.second }

        val summary = StakeoutReport.of(p5).summary

        summary.designPoints shouldBe 1
        summary.within shouldBe 1
        summary.notEvaluated shouldBe 0
    }

    @Test
    fun `S019 RF-21 un nombre editado sin el patron toma el numero por orden de creacion`() {
        val p1 = staked("UTM30").filter { it.first.getValue("diseno") == "P1" }.map { it.second }
        val renamed = p1.map { it.copy(name = "renombrado-${it.id}") }

        val report = StakeoutReport.of(renamed)

        report.rows.map { it.number } shouldBe listOf(1, 2, 3)
        report.rows.map { it.point.id } shouldBe p1.map { it.id }
    }

    @Test
    fun `S019 RF-17 dos disenos con el mismo nombre pero distinto identificador no se mezclan`() {
        val p2 = staked("UTM30").first { it.first.getValue("diseno") == "P2" }.second
        val other = p2.copy(id = "otro", staked = p2.staked!!.copy(designId = "design-otro"), createdAtMs = p2.createdAtMs + 1)

        val report = StakeoutReport.of(listOf(p2, other))

        report.summary.designPoints shouldBe 2
        report.rows.map { it.last } shouldBe listOf(true, true)
    }
}
