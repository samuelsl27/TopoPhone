package es.topophone.core.survey

import es.topophone.core.geodesy.Utm
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadIgnoredReason
import es.topophone.core.model.cad.CadImportOptions
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot
import org.junit.jupiter.api.Test

/** De las coordenadas de una capa de GeoPackage (geográficas o UTM de otro huso o datum) a las del proyecto, frente a pyproj (S048/RF-8, RF-11; constitución nº 2). */
class CadProjectorGeographicTest {

    /** Un vértice de `fixtures/formatos/gpkg/esperado.csv`: lo que hay en el fichero y lo que da pyproj en ETRS89 UTM 30. */
    private data class Vertex(val file: String, val table: String, val x: Double, val y: Double, val east: Double, val north: Double)

    private val rows: List<Map<String, String>> = run {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        val lines = File(root, "fixtures/formatos/gpkg/esperado.csv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val header = lines.first().split(',')
        lines.drop(1).map { header.zip(it.split(',')).toMap() }
    }

    private fun vertices(file: String, table: String): List<Vertex> = rows
        .filter { it["archivo"] == file && it["tabla"] == table && it["estado"] == "OK" }
        .flatMap { row ->
            row.getValue("vertices_fuente").split(';').zip(row.getValue("vertices_proyecto").split(';')).map { (source, project) ->
                val s = source.split(' ')
                val p = project.split(' ')
                Vertex(file, table, s[0].toDouble(), s[1].toDouble(), p[0].toDouble(), p[1].toDouble())
            }
        }

    private fun point(x: Double, y: Double, z: Double? = null) =
        CadEntity(CadEntityKind.POINT, doubleArrayOf(x), doubleArrayOf(y), z?.let { doubleArrayOf(it) }, null)

    private fun converted(projector: CadProjector, entity: CadEntity): CadEntity =
        projector.convert(entity).shouldBeInstanceOf<CadProjection.Converted>().entity

    private val etrs89 = CadImportOptions(source = CadSourceCrs.Geographic(GeodeticDatum.ETRS89))

    @Test
    fun `S048 RF-8 las capas en geograficas ETRS89 y WGS 84 salen como en pyproj a menos de 1 mm`() {
        val all =
            vertices("lineas-4258.gpkg", "ejes") + vertices("wgs84-4326.gpkg", "puntos") +
                vertices("sistemas-mixtos.gpkg", "geograficas_4258")
        val projector = CadProjector(ProjectCrs.ETRS89_UTM30, etrs89)

        (all.size >= 15) shouldBe true
        all.forEach { v ->
            val out = converted(projector, point(v.x, v.y))

            out.easting[0] shouldBe (v.east plusOrMinus 1e-3)
            out.northing[0] shouldBe (v.north plusOrMinus 1e-3)
        }
    }

    @Test
    fun `S048 RF-8 las capas en UTM de otro huso o en ED50 salen como en pyproj a menos de 1 mm`() {
        val cases = listOf(
            "utm29" to CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29),
            "utm31" to CadSourceCrs.Utm(GeodeticDatum.ETRS89, 31),
            "ed50_utm30" to CadSourceCrs.Utm(GeodeticDatum.ED50, 30),
        )

        for ((table, source) in cases) {
            val projector = CadProjector(ProjectCrs.ETRS89_UTM30, CadImportOptions(source = source))
            val list = vertices("sistemas-mixtos.gpkg", table)
            list.size shouldBe 3
            list.forEach { v ->
                val out = converted(projector, point(v.x, v.y))

                out.easting[0] shouldBe (v.east plusOrMinus 1e-3)
                out.northing[0] shouldBe (v.north plusOrMinus 1e-3)
            }
        }
    }

    @Test
    fun `S048 RF-8 las coordenadas geograficas son grados y la unidad de la capa no las cambia`() {
        val v = vertices("lineas-4258.gpkg", "ejes").first()
        val metres = converted(CadProjector(ProjectCrs.ETRS89_UTM30, etrs89), point(v.x, v.y))
        val feet = converted(CadProjector(ProjectCrs.ETRS89_UTM30, etrs89.copy(unit = LinearUnit.US_SURVEY_FOOT)), point(v.x, v.y))

        feet.easting[0] shouldBe metres.easting[0]
        feet.northing[0] shouldBe metres.northing[0]
    }

    @Test
    fun `S048 RF-8 la ida y vuelta de geograficas a UTM y de UTM a geograficas difiere menos de 0,1 mm`() {
        val projector = CadProjector(ProjectCrs.ETRS89_UTM30, etrs89)

        vertices("lineas-4258.gpkg", "ejes").forEach { v ->
            val out = converted(projector, point(v.x, v.y))
            val back = Utm.inverse(out.easting[0], out.northing[0], 30)

            val dNorth = (back.latitudeDeg - v.y) * METRES_PER_DEGREE
            val dEast = (back.longitudeDeg - v.x) * METRES_PER_DEGREE * cos(Math.toRadians(v.y))
            hypot(dEast, dNorth) shouldBe (0.0 plusOrMinus 1e-4)
        }
    }

    @Test
    fun `S048 RF-8 un proyecto en geograficas recibe la longitud y la latitud tal cual`() {
        val v = vertices("lineas-4258.gpkg", "ejes").first()

        val out = converted(CadProjector(ProjectCrs.ETRS89_GEOGRAPHIC, etrs89), point(v.x, v.y))

        out.easting[0] shouldBe (v.x plusOrMinus 1e-12)
        out.northing[0] shouldBe (v.y plusOrMinus 1e-12)
    }

    @Test
    fun `S048 RF-19 la altura no se convierte entre sistemas ni por la unidad geografica`() {
        val v = vertices("lineas-4258.gpkg", "ejes").first()

        val out = converted(CadProjector(ProjectCrs.ETRS89_UTM30, etrs89), point(v.x, v.y, 646.377))

        out.height!![0] shouldBe 646.377
    }

    @Test
    fun `S048 RF-11 un vertice fuera del ambito rechaza el elemento entero con su motivo`() {
        val projector = CadProjector(ProjectCrs.ETRS89_UTM30, etrs89)
        val inside = vertices("lineas-4258.gpkg", "ejes").first()
        val line = CadEntity(CadEntityKind.POLYLINE, doubleArrayOf(inside.x, -15.4), doubleArrayOf(inside.y, 28.1), null, null)

        projector.convert(line) shouldBe CadProjection.Rejected(CadIgnoredReason.OUTSIDE_CRS_AREA)
        projector.convert(point(-15.4, 28.1)) shouldBe CadProjection.Rejected(CadIgnoredReason.OUTSIDE_CRS_AREA)
    }

    @Test
    fun `S048 RF-11 una capa de Canarias en un proyecto de la Peninsula esta fuera del ambito`() {
        val projector = CadProjector(ProjectCrs.ETRS89_UTM30, CadImportOptions(source = CadSourceCrs.Utm(GeodeticDatum.REGCAN95, 28)))
        val canarias = rows.filter { it["tabla"] == "canarias_utm28" }

        canarias.size shouldBe 3
        canarias.all { it["estado"] == "fuera_de_ambito" } shouldBe true
        projector.convert(point(440_000.0, 3_110_000.0)) shouldBe CadProjection.Rejected(CadIgnoredReason.OUTSIDE_CRS_AREA)
    }

    @Test
    fun `S048 RF-11 geograficas REGCAN95 en un proyecto de Canarias entran y en uno de la Peninsula no`() {
        val source = CadImportOptions(source = CadSourceCrs.Geographic(GeodeticDatum.REGCAN95))

        val canarias = converted(CadProjector(ProjectCrs.REGCAN95_UTM28, source), point(-15.4, 28.1))
        CadProjector(ProjectCrs.ETRS89_UTM30, source).convert(point(-15.4, 28.1)) shouldBe
            CadProjection.Rejected(CadIgnoredReason.OUTSIDE_CRS_AREA)

        canarias.easting[0] shouldBe (Utm.forward(28.1, -15.4, 28).easting plusOrMinus 1e-3)
    }

    private companion object {
        const val METRES_PER_DEGREE = 111_319.49079327357
    }
}
