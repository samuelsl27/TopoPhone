package es.topophone.core.survey

import es.topophone.core.model.cad.CadColor
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
import java.io.File
import org.junit.jupiter.api.Test

/** De las coordenadas de un DXF a las del proyecto, frente a pyproj (S040/RF-6, RF-7, RF-17, RF-21; constitución nº 2). */
class CadProjectorTest {

    /** Una fila de `fixtures/formatos/dxf/esperado-conversion.csv`. */
    private data class Row(
        val file: String,
        val source: String,
        val unit: String,
        val x: Double,
        val y: Double,
        val z: Double,
        val east: Double,
        val north: Double,
        val height: Double,
    )

    private val rows: List<Row> = run {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        File(root, "fixtures/formatos/dxf/esperado-conversion.csv").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .drop(1)
            .map { line ->
                val c = line.split(',')
                Row(c[0], c[2], c[3], c[4].toDouble(), c[5].toDouble(), c[6].toDouble(), c[7].toDouble(), c[8].toDouble(), c[9].toDouble())
            }
    }

    private fun options(file: String) = when (file) {
        "origen-ed50-utm30.dxf" -> CadImportOptions(source = CadSourceCrs.Utm(GeodeticDatum.ED50, 30))
        "origen-etrs89-utm29.dxf" -> CadImportOptions(source = CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29))
        "origen-etrs89-utm30-pies.dxf" -> CadImportOptions(unit = LinearUnit.INTERNATIONAL_FOOT)
        "origen-etrs89-utm30-pies-us.dxf" -> CadImportOptions(unit = LinearUnit.US_SURVEY_FOOT)
        else -> error(file)
    }

    private fun point(x: Double, y: Double, z: Double? = null) =
        CadEntity(CadEntityKind.POINT, doubleArrayOf(x), doubleArrayOf(y), z?.let { doubleArrayOf(it) }, null)

    private fun converted(projector: CadProjector, entity: CadEntity): CadEntity =
        (projector.convert(entity) as CadProjection.Converted).entity

    @Test
    fun `S040 RF-6 y RF-7 cada vertice de los ficheros de ejemplo sale como en pyproj a menos de 1 mm`() {
        rows.size shouldBe 16
        rows.forEach { r ->
            val projector = CadProjector(ProjectCrs.ETRS89_UTM30, options(r.file))

            val out = converted(projector, point(r.x, r.y, r.z))

            out.easting[0] shouldBe (r.east plusOrMinus 1e-3)
            out.northing[0] shouldBe (r.north plusOrMinus 1e-3)
            out.height!![0] shouldBe (r.height plusOrMinus 1e-9)
        }
    }

    @Test
    fun `S040 RF-6 la ida y vuelta entre dos husos difiere menos de 0,1 mm`() {
        val there = CadProjector(ProjectCrs.ETRS89_UTM30, CadImportOptions(source = CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29)))
        val back = CadProjector(ProjectCrs.ETRS89_UTM29, CadImportOptions(source = CadSourceCrs.Utm(GeodeticDatum.ETRS89, 30)))

        rows.filter { it.file == "origen-etrs89-utm29.dxf" }.forEach { r ->
            val forward = converted(there, point(r.x, r.y))
            val again = converted(back, point(forward.easting[0], forward.northing[0]))

            again.easting[0] shouldBe (r.x plusOrMinus 1e-4)
            again.northing[0] shouldBe (r.y plusOrMinus 1e-4)
        }
    }

    @Test
    fun `S040 RF-6 ED50 pasa por la rejilla del IGN y el resultado se desplaza unos cientos de metros`() {
        val projector = CadProjector(ProjectCrs.ETRS89_UTM30, options("origen-ed50-utm30.dxf"))

        rows.filter { it.file == "origen-ed50-utm30.dxf" }.forEach { r ->
            val out = converted(projector, point(r.x, r.y))
            val shift = Math.hypot(out.easting[0] - r.x, out.northing[0] - r.y)

            (shift in 50.0..300.0) shouldBe true
        }
    }

    @Test
    fun `S040 RF-6 un proyecto en ED50 recibe las coordenadas del dibujo en ED50 sin cambiarlas`() {
        val projector = CadProjector(ProjectCrs.ED50_UTM30, CadImportOptions())
        val ed50 = rows.first { it.file == "origen-ed50-utm30.dxf" }

        val out = converted(projector, point(ed50.x, ed50.y))

        out.easting[0] shouldBe ed50.x // lo escrito en el SC del proyecto se guarda tal cual
        out.northing[0] shouldBe ed50.y
    }

    @Test
    fun `S040 RF-6 un dibujo en el sistema del proyecto no cambia sus Este y Norte`() {
        val projector = CadProjector(ProjectCrs.ETRS89_UTM30, CadImportOptions())

        val out = converted(projector, point(440297.98753, 4474244.00112))

        out.easting[0] shouldBe 440297.98753
        out.northing[0] shouldBe 4474244.00112
    }

    @Test
    fun `S040 RF-7 los pies convierten el Este el Norte y la altura a metros`() {
        val projector = CadProjector(ProjectCrs.ETRS89_UTM30, CadImportOptions(unit = LinearUnit.INTERNATIONAL_FOOT))

        val out = converted(projector, point(1_444_547.2032, 14_679_278.2189, 2120.6594))

        out.easting[0] shouldBe (1_444_547.2032 * 0.3048 plusOrMinus 1e-9)
        out.height!![0] shouldBe (2120.6594 * 0.3048 plusOrMinus 1e-9)
    }

    @Test
    fun `S040 RF-17 un vertice fuera del ambito del sistema rechaza el elemento entero`() {
        val projector = CadProjector(ProjectCrs.ETRS89_UTM30, CadImportOptions())
        val line = CadEntity(
            CadEntityKind.LINE,
            doubleArrayOf(440000.0, 100.0),
            doubleArrayOf(4474000.0, 100.0),
            null,
            null,
        )

        (projector.convert(line) as CadProjection.Rejected).reason shouldBe CadIgnoredReason.OUTSIDE_CRS_AREA
    }

    @Test
    fun `S040 RF-17 Madrid no cabe en un proyecto de Canarias ni Tenerife en uno de la Peninsula`() {
        val canarias = CadProjector(ProjectCrs.REGCAN95_UTM28, CadImportOptions(source = CadSourceCrs.Utm(GeodeticDatum.ETRS89, 30)))
        val peninsula = CadProjector(ProjectCrs.ETRS89_UTM30, CadImportOptions(source = CadSourceCrs.Utm(GeodeticDatum.REGCAN95, 28)))

        (canarias.convert(point(440298.0, 4474244.0)) as CadProjection.Rejected).reason shouldBe CadIgnoredReason.OUTSIDE_CRS_AREA
        (peninsula.convert(point(374_000.0, 3_150_000.0)) as CadProjection.Rejected).reason shouldBe CadIgnoredReason.OUTSIDE_CRS_AREA
    }

    @Test
    fun `S040 RF-21 la altura no se convierte entre sistemas y sin cotas se descarta`() {
        val ed50 = CadSourceCrs.Utm(GeodeticDatum.ED50, 30)
        val withZ = CadProjector(ProjectCrs.ETRS89_UTM30, CadImportOptions(source = ed50))
        val without = CadProjector(ProjectCrs.ETRS89_UTM30, CadImportOptions(source = ed50, useElevations = false))
        val r = rows.first { it.file == "origen-ed50-utm30.dxf" }

        converted(withZ, point(r.x, r.y, r.z)).height!![0] shouldBe r.z // la Z del DXF es ya la del sistema de alturas del proyecto
        converted(without, point(r.x, r.y, r.z)).height shouldBe null
        converted(withZ, point(r.x, r.y)).height shouldBe null // sin Z, sin cota
    }

    @Test
    fun `S040 RF-19 el tipo el color y los vertices de un elemento se conservan`() {
        val projector = CadProjector(ProjectCrs.ETRS89_UTM30, CadImportOptions())
        val polyline = CadEntity(
            CadEntityKind.POLYLINE,
            doubleArrayOf(440000.123456789, 440010.5, 440020.25),
            doubleArrayOf(4474000.987654321, 4474010.5, 4474020.25),
            doubleArrayOf(1.5, 2.5, 3.5),
            CadColor.rgb(0x00AA55),
        )

        val out = converted(projector, polyline)

        out.kind shouldBe CadEntityKind.POLYLINE
        out.color shouldBe CadColor.rgb(0x00AA55)
        out.easting.toList() shouldBe polyline.easting.toList() // sin redondear
        out.northing.toList() shouldBe polyline.northing.toList()
        out.height!!.toList() shouldBe listOf(1.5, 2.5, 3.5)
    }

    @Test
    fun `S040 RF-6 un proyecto en geograficas recibe longitud y latitud en grados sin tocar la unidad`() {
        val projector = CadProjector(ProjectCrs.ETRS89_GEOGRAPHIC, CadImportOptions(unit = LinearUnit.INTERNATIONAL_FOOT))

        val out = converted(projector, point(-3.70371, 40.41668, 100.0))

        out.easting[0] shouldBe -3.70371 // grados, no pies
        out.northing[0] shouldBe 40.41668
        out.height!![0] shouldBe (30.48 plusOrMinus 1e-9)
    }

    @Test
    fun `S040 RF-6 de UTM a un proyecto en geograficas da la longitud y la latitud de pyproj`() {
        val projector = CadProjector(ProjectCrs.ETRS89_GEOGRAPHIC, CadImportOptions(source = CadSourceCrs.Utm(GeodeticDatum.ETRS89, 30)))
        // Las cuatro ciudades de los ficheros de conversión: sus UTM 30 salen de pyproj de estas latitudes y longitudes.
        val utm30 = rows.filter { it.file == "origen-etrs89-utm29.dxf" }
        val cities = listOf(
            Triple(40.41668, -3.70371, utm30[0]),
            Triple(41.6488, -0.8891, utm30[1]),
            Triple(40.9701, -5.6635, utm30[2]),
            Triple(37.3891, -5.9845, utm30[3]),
        )

        cities.forEach { (lat, lon, r) ->
            val out = converted(projector, point(r.east, r.north))

            out.northing[0] shouldBe (lat plusOrMinus 1e-8)
            out.easting[0] shouldBe (lon plusOrMinus 1e-8)
        }
    }

    @Test
    fun `S040 RF-17 en geograficas un punto fuera del ambito se rechaza`() {
        val projector = CadProjector(ProjectCrs.ETRS89_GEOGRAPHIC, CadImportOptions())

        (projector.convert(point(100.0, 0.0)) as CadProjection.Rejected).reason shouldBe CadIgnoredReason.OUTSIDE_CRS_AREA
    }
}
