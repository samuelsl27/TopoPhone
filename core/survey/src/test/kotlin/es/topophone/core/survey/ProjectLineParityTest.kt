package es.topophone.core.survey

import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.survey.LineFixtures.number
import es.topophone.core.survey.LineFixtures.optional
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * La misma geometría como línea de un dibujo (S041) y como línea del proyecto (S027) da el mismo PK, distancia lateral, error lateral,
 * punto más cercano, serie de intervalos y lista de vértices, y coincide con lo calculado aparte con pyproj (S027/RF-2, RF-3, RF-5, RF-6).
 */
class ProjectLineParityTest {

    private data class Source(val east: DoubleArray, val north: DoubleArray, val height: DoubleArray?, val closed: Boolean)

    private val sources: Map<String, Source> by lazy {
        LineFixtures.rows("lineas.csv").groupBy { it.getValue("linea") }.mapValues { (_, vertices) ->
            val withHeight = vertices.first().getValue("cota").isNotEmpty()
            Source(
                DoubleArray(vertices.size) { vertices[it].number("este") },
                DoubleArray(vertices.size) { vertices[it].number("norte") },
                if (withHeight) DoubleArray(vertices.size) { vertices[it].number("cota") } else null,
                vertices.first().getValue("cerrada") == "1",
            )
        }
    }

    /** La línea como la hace S041 desde un elemento del dibujo: una cerrada repite el primer vértice al final. */
    private fun asDrawing(name: String): StakeLine {
        val s = sources.getValue(name)
        val east = if (s.closed) s.east + s.east.first() else s.east
        val north = if (s.closed) s.north + s.north.first() else s.north
        val height = s.height?.let { if (s.closed) it + it.first() else it }
        return StakeLine.of(CadEntity(CadEntityKind.POLYLINE, east, north, height, null))!!
    }

    private fun asProject(name: String): ProjectStakeLineResult.Built {
        val s = sources.getValue(name)
        val vertices = s.east.indices.map { ProjectStakeVertex("V$it", s.east[it], s.north[it], s.height?.get(it)) }
        return ProjectStakeLine.of(vertices, s.closed).shouldBeInstanceOf<ProjectStakeLineResult.Built>()
    }

    @Test
    fun `S027 RF-2 el cercano el PK y las distancias de cada punta coinciden con pyproj y con la linea del dibujo`() {
        val rows = LineFixtures.rows("consultas.csv")

        (rows.size > 400) shouldBe true
        rows.forEach { row ->
            val name = row.getValue("linea")
            val project = LineLocator(asProject(name).line).locate(row.number("punta_e"), row.number("punta_n"))
            val drawing = LineLocator(asDrawing(name)).locate(row.number("punta_e"), row.number("punta_n"))

            project.chainageM shouldBe (row.number("pk") plusOrMinus 1e-3)
            project.distanceM shouldBe (row.number("distancia") plusOrMinus 1e-3)
            project.lateralM shouldBe (row.number("lateral") plusOrMinus 1e-3)
            project.nearest.easting shouldBe (row.number("cercano_e") plusOrMinus 1e-3)
            project.chainageM shouldBe (drawing.chainageM plusOrMinus 1e-9)
            project.lateralM shouldBe (drawing.lateralM plusOrMinus 1e-9)
            project.position shouldBe drawing.position
            project.remainingM shouldBe (drawing.remainingM plusOrMinus 1e-9)
        }
    }

    @Test
    fun `S027 RF-2 las series de intervalos coinciden con pyproj y con la linea del dibujo, con su cota`() {
        val cases = LineFixtures.rows("series.csv")
            .groupBy { listOf(it["linea"], it["pk_a"], it["pk_b"], it["intervalo"], it["desplazamiento"]) }

        (cases.size >= 20) shouldBe true
        cases.forEach { (key, expected) ->
            fun series(line: StakeLine) =
                LineSeries(line, LineRange(key[1]!!.toDouble(), key[2]!!.toDouble()), key[3]!!.toDouble(), key[4]!!.toDouble())
            val project = series(asProject(key[0]!!).line)
            val drawing = series(asDrawing(key[0]!!))

            project.size shouldBe expected.size
            expected.forEach { row ->
                val p = project[row.getValue("i").toInt()]
                p.chainageM shouldBe (row.number("pk") plusOrMinus 1e-6)
                p.point.easting shouldBe (row.number("este") plusOrMinus 1e-3)
                p.point.northing shouldBe (row.number("norte") plusOrMinus 1e-3)
                val z = row.optional("cota")
                if (z == null) p.heightM.shouldBeNull() else p.heightM!! shouldBe (z plusOrMinus 1e-6)
                p.heightM shouldBe drawing[row.getValue("i").toInt()].heightM
            }
        }
    }

    @Test
    fun `S027 RF-2 la lista de Por segmentos tiene los mismos vertices que la del dibujo, todos originales`() {
        for (name in sources.keys) {
            val project = LineVertices.of(asProject(name).line, CadEntityKind.POLYLINE)
            val drawing = LineVertices.of(asDrawing(name), CadEntityKind.POLYLINE)

            project.entries shouldBe drawing.entries
            project.needsReimport shouldBe false
        }
    }

    @Test
    fun `S027 RF-5 el nombre de cada vertice sale del punto de su vertice aunque se quiten repetidos`() {
        val vertices = listOf(
            ProjectStakeVertex("A", 0.0, 0.0, null),
            ProjectStakeVertex("B", 0.0, 0.0, null),
            ProjectStakeVertex("C", 30.0, 40.0, null),
            ProjectStakeVertex("D", 60.0, 80.0, null),
        )

        val built = ProjectStakeLine.of(vertices, closed = false).shouldBeInstanceOf<ProjectStakeLineResult.Built>()

        built.line.vertexCount shouldBe 3
        (0 until 3).map { built.name(it) } shouldBe listOf("A", "C", "D")
    }

    @Test
    fun `S027 RF-8 el vertice sin altura entre dos con altura se marca como interpolado`() {
        val vertices = listOf(
            ProjectStakeVertex("A", 0.0, 0.0, 10.0),
            ProjectStakeVertex("B", 30.0, 40.0, null),
            ProjectStakeVertex("C", 60.0, 80.0, 20.0),
            ProjectStakeVertex("D", 90.0, 120.0, null),
        )

        val built = ProjectStakeLine.of(vertices, closed = false).shouldBeInstanceOf<ProjectStakeLineResult.Built>()

        built.isInterpolated(0) shouldBe false
        built.isInterpolated(1) shouldBe true
        built.isInterpolated(3) shouldBe false // despues del ultimo con altura no hay cota que interpolar
        built.line.height(1).shouldBeNull()
        built.line.heightAt(built.line.chainageOf(1)).let { it!! shouldBe (15.0 plusOrMinus 1e-9) }
    }

    @Test
    fun `S027 RF-3 con menos de dos vertices distintos no se puede replantear`() {
        ProjectStakeLine.of(emptyList(), closed = false) shouldBe ProjectStakeLineResult.TooFewVertices
        ProjectStakeLine.of(listOf(ProjectStakeVertex("A", 1.0, 2.0, null)), closed = false) shouldBe ProjectStakeLineResult.TooFewVertices
        val same = listOf(ProjectStakeVertex("A", 1.0, 2.0, null), ProjectStakeVertex("B", 1.0, 2.0, null))
        ProjectStakeLine.of(same, closed = false) shouldBe ProjectStakeLineResult.TooFewVertices
    }

    @Test
    fun `S027 RF-3 un vertice sin Este y Norte no tiene plano donde replantear`() {
        val vertices = listOf(ProjectStakeVertex("A", null, null, null), ProjectStakeVertex("B", 1.0, 2.0, null))

        ProjectStakeLine.of(vertices, closed = false) shouldBe ProjectStakeLineResult.NoPlanePosition
    }

    @Test
    fun `S027 RF-6 un poligono cierra con el ultimo vertice hacia el primero y la longitud es el perimetro`() {
        val vertices = listOf(
            ProjectStakeVertex("A", 0.0, 0.0, null),
            ProjectStakeVertex("B", 30.0, 0.0, null),
            ProjectStakeVertex("C", 30.0, 40.0, null),
            ProjectStakeVertex("D", 0.0, 40.0, null),
        )

        val built = ProjectStakeLine.of(vertices, closed = true).shouldBeInstanceOf<ProjectStakeLineResult.Built>()

        built.line.closed shouldBe true
        built.line.lengthM shouldBe (140.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S027 RF-8 la lista de Por segmentos da al vertice sin altura la cota interpolada`() {
        val vertices = listOf(
            ProjectStakeVertex("A", 0.0, 0.0, 10.0),
            ProjectStakeVertex("B", 30.0, 40.0, null),
            ProjectStakeVertex("C", 60.0, 80.0, 20.0),
            ProjectStakeVertex("D", 90.0, 120.0, null),
        )
        val built = ProjectStakeLine.of(vertices, closed = false).shouldBeInstanceOf<ProjectStakeLineResult.Built>()

        val entries = LineVertices.of(built.line, CadEntityKind.POLYLINE).entries

        entries.map { it.heightM }.let {
            it[0] shouldBe 10.0
            it[1]!! shouldBe (15.0 plusOrMinus 1e-9)
            it[2] shouldBe 20.0
            it[3].shouldBeNull()
        }
    }
}
