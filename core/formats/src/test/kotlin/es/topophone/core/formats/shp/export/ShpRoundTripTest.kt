package es.topophone.core.formats.shp.export

import es.topophone.core.formats.gpkg.GpkgCrs
import es.topophone.core.formats.shp.DbfReader
import es.topophone.core.formats.shp.ShpByteSource
import es.topophone.core.formats.shp.ShpCrsCatalog
import es.topophone.core.formats.shp.ShpEncoding
import es.topophone.core.formats.shp.ShpEncodings
import es.topophone.core.formats.shp.ShpEntry
import es.topophone.core.formats.shp.ShpReader
import es.topophone.core.formats.shp.ShpRecord
import es.topophone.core.formats.shp.ShpSelection
import es.topophone.core.formats.shp.ShpShape
import es.topophone.core.formats.shp.ShxIndex
import es.topophone.core.formats.shp.export.ShpExportSamples.attribute
import es.topophone.core.formats.shp.export.ShpExportSamples.code
import es.topophone.core.formats.shp.export.ShpExportSamples.measured
import es.topophone.core.formats.shp.export.ShpExportSamples.point
import es.topophone.core.formats.shp.export.ShpExportSamples.project
import es.topophone.core.model.cad.GpkgValue
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream
import kotlin.math.abs
import org.junit.jupiter.api.Test

/**
 * La ida y vuelta de la exportación: el ZIP que se escribe se relee con los lectores de S030 y da las mismas coordenadas, los mismos
 * atributos y el mismo sistema (S055/RF-5, RF-10 a RF-15, constitución nº 2).
 */
class ShpRoundTripTest {
    private val builder = ShpExportBuilder()

    private class Unzipped(val files: Map<String, ByteArray>, val summary: ShpExportSummary)

    private fun roundTrip(export: ShpExport, name: String = "Obra"): Unzipped {
        val out = ByteArrayOutputStream()
        val summary = ShapefileZipWriter.write(export, name, out, LocalDate.of(2026, 11, 12))
        val files = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            generateSequence { zip.nextEntry }.forEach { files[it.name] = zip.readBytes() }
        }
        return Unzipped(files, summary)
    }

    private fun Unzipped.shp(stem: String) = ShpReader.open(ShpByteSource.of(files.getValue("$stem.shp")))!!

    private fun Unzipped.dbf(stem: String) = DbfReader.open(ShpByteSource.of(files.getValue("$stem.dbf")), ShpEncoding.UTF_8)!!

    private fun records(reader: ShpReader) = ArrayList<ShpRecord>().also { all -> reader.forEachRecord { all += it } }

    private fun text(value: GpkgValue) = (value as GpkgValue.Text).value

    private fun real(value: GpkgValue) = (value as GpkgValue.Real).value

    private fun sampleLines() = listOf(
        ShpExportSamples.line("l1", listOf("1", "2")),
        ShpExportSamples.line("p1", listOf("1", "2", "4"), closed = true),
    )

    private val tree = code(
        "ARBOL",
        attribute("Diámetro", AttributeType.NUMBER, "0.35"),
        attribute("Ramas", AttributeType.INTEGER, "4"),
    )

    private val points = listOf(
        point("1", description = "Árbol ñandú €", code = tree),
        point("2", easting = 440_010.5, northing = 4_474_010.25, height = null),
        point("3", easting = null, northing = null),
        point("4", easting = 440_020.0, northing = 4_474_000.0, description = "x".repeat(300)),
        measured("5"),
    )

    @Test
    fun `S055 RF-12 las coordenadas proyectadas se releen exactamente iguales`() {
        val export = builder.build(points, project())
        val result = roundTrip(export)

        val read = records(result.shp("Obra_puntos"))
        read.size shouldBe points.size
        points.forEachIndexed { i, p ->
            val shape = read[i].shape
            val c = p.coordinates
            if (c.easting == null) {
                shape shouldBe ShpShape.Null
            } else {
                val part = (shape as ShpShape.Parts).parts.single()
                part.x.single() shouldBe c.easting
                part.y.single() shouldBe c.northing
                part.z!!.single() shouldBe (c.height ?: 0.0)
            }
        }
    }

    @Test
    fun `S055 RF-13 las coordenadas geograficas y la altura elipsoidal se releen exactamente iguales`() {
        val export = builder.build(points, project(), ShpExportOptions(ShpCoordinates.GEOGRAPHIC))
        val result = roundTrip(export)

        val read = records(result.shp("Obra_puntos"))
        points.forEachIndexed { i, p ->
            val part = (read[i].shape as ShpShape.Parts).parts.single()
            part.x.single() shouldBe p.coordinates.ground.longitudeDeg
            part.y.single() shouldBe p.coordinates.ground.latitudeDeg
            part.z!!.single() shouldBe (p.coordinates.ground.ellipsoidalHeightM ?: 0.0)
        }
    }

    @Test
    fun `S055 RF-14b el punto sin Este y Norte se relee como registro nulo y conserva su fila del dbf`() {
        val result = roundTrip(builder.build(points, project()))

        val read = records(result.shp("Obra_puntos"))
        read[2].shape shouldBe ShpShape.Null
        result.dbf("Obra_puntos").record(3)!!.values[0] shouldBe GpkgValue.Text("P-3")
        result.dbf("Obra_puntos").recordCount shouldBe points.size
    }

    @Test
    fun `S055 RF-6 los atributos se releen con su tipo y dentro de 5e-10 los decimales`() {
        val result = roundTrip(builder.build(points, project()))
        val dbf = result.dbf("Obra_puntos")
        val columns = dbf.columns.map { it.name }

        val first = dbf.record(1)!!.values
        text(first[columns.indexOf("NOMBRE")]) shouldBe "P-1"
        text(first[columns.indexOf("DESCRIP")]) shouldBe "Árbol ñandú €"
        text(first[columns.indexOf("CODIGO")]) shouldBe "ARBOL"
        text(first[columns.indexOf("SC")]) shouldBe "EPSG:25830"
        (abs(real(first[columns.indexOf("ESTE")]) - 440_000.123456789) <= 1e-9) shouldBe true
        (abs(real(first[columns.indexOf("LATITUD")]) - 40.4168) <= 5e-10) shouldBe true
        (abs(real(first[columns.indexOf("LONGITUD")]) - -3.7038) <= 5e-10) shouldBe true
        real(first[columns.indexOf("COTA")]) shouldBe 648.25
        real(first[columns.indexOf("DIAMETRO")]) shouldBe 0.35
        first[columns.indexOf("RAMAS")] shouldBe GpkgValue.Integer(4)
        first[columns.indexOf("SATELITES")] shouldBe GpkgValue.Null // un punto manual no tiene calidad
        val measured = dbf.record(5)!!.values
        measured[columns.indexOf("SATELITES")] shouldBe GpkgValue.Integer(24)
        measured[columns.indexOf("REPRODUC")] shouldBe GpkgValue.Integer(1)
        text(measured[columns.indexOf("MED_INICIO")]) shouldBe "2026-10-18T18:03:34Z"
        dbf.columns.first { it.name == "SATELITES" }.type shouldBe "INTEGER"
        dbf.columns.first { it.name == "ESTE" }.type shouldBe "REAL"
        dbf.columns.first { it.name == "NOMBRE" }.type shouldBe "TEXT"
    }

    @Test
    fun `S055 RF-14 el punto sin altura vuelve con la columna vacia aunque su Z sea 0`() {
        val result = roundTrip(builder.build(points, project()))
        val columns = result.dbf("Obra_puntos").columns.map { it.name }

        result.dbf("Obra_puntos").record(2)!!.values[columns.indexOf("COTA")] shouldBe GpkgValue.Null
        (records(result.shp("Obra_puntos"))[1].shape as ShpShape.Parts).parts.single().z!!.single() shouldBe 0.0
        result.summary.verticesWithoutHeight shouldBe 1
    }

    @Test
    fun `S055 RF-10 el texto de mas de 254 bytes vuelve recortado y el resumen lo cuenta`() {
        val result = roundTrip(builder.build(points, project()))
        val columns = result.dbf("Obra_puntos").columns.map { it.name }

        text(result.dbf("Obra_puntos").record(4)!!.values[columns.indexOf("DESCRIP")]) shouldBe "x".repeat(254)
        result.summary.truncatedTexts shouldBe 1
    }

    @Test
    fun `S055 RF-11 el dbf es UTF-8 y se lee igual con la codificacion que dice el cpg y con la que detecta el lector`() {
        val result = roundTrip(builder.build(points, project()))
        val dbf = result.dbf("Obra_puntos")

        ShpEncodings.fromCpg(String(result.files.getValue("Obra_puntos.cpg"), Charsets.US_ASCII))!!.encoding shouldBe ShpEncoding.UTF_8
        dbf.detectEncoding() shouldBe ShpEncoding.UTF_8
    }

    @Test
    fun `S055 RF-15 el prj se relee como el sistema del proyecto y como las geograficas en cada caso`() {
        for (crs in ProjectCrs.entries.filter { it.isUtm }) {
            val utm = roundTrip(builder.build(points, project(crs)))
            val geo = roundTrip(builder.build(points, project(crs), ShpExportOptions(ShpCoordinates.GEOGRAPHIC)))

            val projected = ShpCrsCatalog.resolve(String(utm.files.getValue("Obra_puntos.prj"), Charsets.US_ASCII)) as GpkgCrs.Supported
            val geographic = ShpCrsCatalog.resolve(String(geo.files.getValue("Obra_puntos.prj"), Charsets.US_ASCII)) as GpkgCrs.Supported
            projected.epsg shouldBe crs.epsg
            geographic.epsg shouldBe ShpPrjDefinitions.geographicEpsg(crs)
        }
    }

    @Test
    fun `S055 RF-5 los poligonos se releen en sentido horario con el primer vertice repetido`() {
        val export = builder.build(
            points = listOf(point("1"), point("2", easting = 440_010.0), point("3", easting = 440_010.0, northing = 4_474_010.0)),
            project = project(),
            lines = listOf(
                ShpExportSamples.line("p1", listOf("1", "2", "3"), closed = true),
                ShpExportSamples.line("p2", listOf("3", "2", "1"), closed = true),
            ),
        )

        val read = records(roundTrip(export).shp("Obra_poligonos"))
        read.size shouldBe 2
        for (record in read) {
            val ring = (record.shape as ShpShape.Parts).parts.single()
            ring.isPolygonRing shouldBe true
            ring.vertexCount shouldBe 4
            ring.x.first() shouldBe ring.x.last()
            ring.y.first() shouldBe ring.y.last()
            var area = 0.0
            for (i in 0 until ring.vertexCount - 1) area += ring.x[i] * ring.y[i + 1] - ring.x[i + 1] * ring.y[i]
            (area < 0.0) shouldBe true // horario
        }
    }

    @Test
    fun `S055 RF-3 las lineas se releen con sus vertices y su Z`() {
        val export = builder.build(
            points = listOf(point("1"), point("2", easting = 440_010.0, height = null), point("3", easting = 440_020.0, height = 650.0)),
            project = project(),
            lines = listOf(ShpExportSamples.line("l1", listOf("1", "2", "3"), name = "Muro")),
        )
        val result = roundTrip(export)

        val line = (records(result.shp("Obra_lineas")).single().shape as ShpShape.Parts).parts.single()
        line.x.toList() shouldBe listOf(440_000.123456789, 440_010.0, 440_020.0)
        line.z!!.toList() shouldBe listOf(648.25, 0.0, 650.0)
        val values = result.dbf("Obra_lineas").record(1)!!.values
        values[0] shouldBe GpkgValue.Text("Muro")
        values[2] shouldBe GpkgValue.Integer(3)
    }

    @Test
    fun `S055 RF-3 el shx apunta a los mismos registros que el shp en las tres capas`() {
        val export = builder.build(
            points = points,
            project = project(),
            lines = sampleLines(),
        )
        val result = roundTrip(export)

        for (stem in listOf("Obra_puntos", "Obra_lineas", "Obra_poligonos")) {
            val shp = result.shp(stem)
            val index = ShxIndex.read(ShpByteSource.of(result.files.getValue("$stem.shx")), shp)!!
            index.fromShx shouldBe true
            index.complete shouldBe true
            index.offsets.toList() shouldBe records(shp).map { it.offset }
            result.dbf(stem).recordCount shouldBe records(shp).size
        }
    }

    @Test
    fun `S055 RF-3 el clasificador de S030 reconoce el ZIP exportado como tres capas completas y sin sobrantes`() {
        val export = builder.build(
            points = points,
            project = project(),
            lines = sampleLines(),
        )
        val result = roundTrip(export)

        val entries = result.files.map { (name, bytes) -> ShpEntry(name, bytes.copyOf(minOf(bytes.size, 100)), bytes.size.toLong()) }
        val grouping = ShpSelection.group(entries)

        // S030 ordena las capas por nombre
        grouping.layers.map { it.name } shouldBe listOf("Obra_lineas", "Obra_poligonos", "Obra_puntos")
        grouping.layers.all { it.shx != null && it.dbf != null && it.prj != null && it.cpg != null } shouldBe true
        grouping.orphans shouldBe emptyList()
        grouping.dropped shouldBe emptyList()
    }

    @Test
    fun `S055 RF-12 un proyecto geografico se relee con su longitud y su latitud y su prj geografico`() {
        val geographic = project(crs = ProjectCrs.ETRS89_GEOGRAPHIC)
        val result = roundTrip(builder.build(points, geographic))

        val first = (records(result.shp("Obra_puntos"))[0].shape as ShpShape.Parts).parts.single()
        first.x.single() shouldBe -3.7038
        first.y.single() shouldBe 40.4168
        first.z!!.single() shouldBe 648.25
        (ShpCrsCatalog.resolve(String(result.files.getValue("Obra_puntos.prj"), Charsets.US_ASCII)) as GpkgCrs.Supported).epsg shouldBe 4258
    }
}
