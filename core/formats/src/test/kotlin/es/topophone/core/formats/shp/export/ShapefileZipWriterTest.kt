package es.topophone.core.formats.shp.export

import es.topophone.core.formats.shp.export.ShpExportSamples.point
import es.topophone.core.formats.shp.export.ShpExportSamples.project
import es.topophone.core.model.project.ProjectCrs
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream
import org.junit.jupiter.api.Test

/** El ZIP de la exportación: un Shapefile por capa con sus cinco ficheros y el nombre del proyecto limpiado (S055/RF-3, RF-4, RF-10, RF-11). */
class ShapefileZipWriterTest {
    private val builder = ShpExportBuilder()
    private val date = LocalDate.of(2026, 11, 12)

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { files[it.name] = zip.readBytes() }
        }
        return files
    }

    private fun zip(export: ShpExport, name: String = "Calle Mayor 12"): Pair<Map<String, ByteArray>, ShpExportSummary> {
        val out = ByteArrayOutputStream()
        val summary = ShapefileZipWriter.write(export, name, out, date)
        return unzip(out.toByteArray()) to summary
    }

    private fun withLines() = builder.build(
        points = listOf(point("1"), point("2", easting = 440_010.0), point("3", easting = 440_010.0, northing = 4_474_010.0)),
        project = project(),
        lines = listOf(
            ShpExportSamples.line("l1", listOf("1", "2")),
            ShpExportSamples.line("p1", listOf("1", "2", "3"), closed = true),
        ),
    )

    @Test
    fun `S055 RF-3 un proyecto solo con puntos da un Shapefile de puntos con sus cinco ficheros`() {
        val (files, _) = zip(builder.build(listOf(point("1")), project()))

        files.keys.toList() shouldBe listOf(
            "Calle_Mayor_12_puntos.shp",
            "Calle_Mayor_12_puntos.shx",
            "Calle_Mayor_12_puntos.dbf",
            "Calle_Mayor_12_puntos.prj",
            "Calle_Mayor_12_puntos.cpg",
        )
    }

    @Test
    fun `S055 RF-3 con lineas y poligonos hay tres Shapefiles, en ese orden y con sus cinco ficheros cada uno`() {
        val (files, _) = zip(withLines())

        files.keys.map { it.substringBeforeLast('.') }.distinct() shouldBe
            listOf("Calle_Mayor_12_puntos", "Calle_Mayor_12_lineas", "Calle_Mayor_12_poligonos")
        files.keys.count { it.endsWith(".shp") } shouldBe 3
        files.keys.count { it.endsWith(".shx") } shouldBe 3
        files.keys.count { it.endsWith(".dbf") } shouldBe 3
        files.keys.count { it.endsWith(".prj") } shouldBe 3
        files.keys.count { it.endsWith(".cpg") } shouldBe 3
        files.size shouldBe 15
    }

    @Test
    fun `S055 RF-3 un proyecto con lineas pero sin poligonos no lleva el Shapefile de poligonos`() {
        val export = builder.build(
            listOf(point("1"), point("2", easting = 440_010.0)),
            project(),
            lines = listOf(ShpExportSamples.line("l1", listOf("1", "2"))),
        )

        zip(export).first.keys.none { it.contains("poligonos") } shouldBe true
    }

    @Test
    fun `S055 RF-3 ningun fichero del ZIP esta vacio`() {
        val (files, _) = zip(withLines())

        files.values.all { it.isNotEmpty() } shouldBe true
    }

    @Test
    fun `S055 RF-11 el cpg de cada capa declara UTF-8`() {
        val (files, _) = zip(withLines())

        files.filterKeys { it.endsWith(".cpg") }.values.map { String(it, Charsets.US_ASCII) } shouldBe List(3) { "UTF-8" }
    }

    @Test
    fun `S055 RF-15 el prj de cada capa es el del sistema escrito`() {
        val definitions = ShpPrjDefinitions()
        val (utm, _) = zip(builder.build(listOf(point("1")), project(ProjectCrs.ETRS89_UTM30)))
        val (geo, _) = zip(builder.build(listOf(point("1")), project(ProjectCrs.ETRS89_UTM30), ShpExportOptions(ShpCoordinates.GEOGRAPHIC)))

        String(utm.getValue("Calle_Mayor_12_puntos.prj"), Charsets.US_ASCII) shouldBe definitions.of(ProjectCrs.ETRS89_UTM30)
        String(geo.getValue("Calle_Mayor_12_puntos.prj"), Charsets.US_ASCII) shouldBe definitions.geographicOf(ProjectCrs.ETRS89_UTM30)
    }

    @Test
    fun `S055 RF-16 sin definicion del sistema no se escribe el prj y los demas ficheros si`() {
        val export = ShpExportBuilder(ShpPrjDefinitions(emptyMap())).build(listOf(point("1")), project())

        val (files, summary) = zip(export)

        val stem = "Calle_Mayor_12_puntos"
        files.keys.toList() shouldBe listOf("$stem.shp", "$stem.shx", "$stem.dbf", "$stem.cpg")
        summary.prjMissing shouldBe true
        ShapefileZipWriter.entryNames("Calle Mayor 12", export.layers.single()) shouldBe files.keys.toList()
    }

    @Test
    fun `S055 RF-4 el nombre del proyecto queda con letras sin acentos, cifras, guion bajo y guion`() {
        ShapefileZipWriter.baseName("Calle Mayor 12") shouldBe "Calle_Mayor_12"
        ShapefileZipWriter.baseName("Parcela Ñandú-3") shouldBe "Parcela_Nandu-3"
        ShapefileZipWriter.baseName("Obra (fase 1)/2") shouldBe "Obra__fase_1__2"
        ShapefileZipWriter.baseName("año_2026") shouldBe "ano_2026"
        ShapefileZipWriter.baseName("A:B*C?") shouldBe "A_B_C_"
        ShapefileZipWriter.baseName("Línea ñ €") shouldBe "Linea_n__"
    }

    @Test
    fun `S055 RF-4 un nombre sin nada aprovechable se llama proyecto`() {
        ShapefileZipWriter.baseName("") shouldBe "proyecto"
        ShapefileZipWriter.baseName("   ") shouldBe "proyecto"
    }

    @Test
    fun `S055 RF-4 los ficheros llevan el nombre limpiado y el sufijo de su capa`() {
        val (files, _) = zip(builder.build(listOf(point("1")), project()), name = "Año 2026: Pozos/Arquetas")

        files.keys.all { it.startsWith("Ano_2026__Pozos_Arquetas_puntos.") } shouldBe true
    }

    @Test
    fun `S055 RF-10 el resumen cuenta los textos recortados a 254 bytes`() {
        val long = point("1", description = "x".repeat(253) + "éé")
        val other = point("2", description = "y".repeat(300))
        val export = builder.build(listOf(long, other, point("3")), project())

        val (_, summary) = zip(export)

        summary.truncatedTexts shouldBe 2
        summary.points shouldBe 3
    }

    @Test
    fun `S055 RF-22 el resumen del ZIP conserva lo del constructor y solo anade los textos recortados`() {
        val export = withLines()

        val (_, summary) = zip(export)

        summary shouldBe export.summary.copy(truncatedTexts = 0)
        summary.points shouldBe 3
        summary.lines shouldBe 1
        summary.polygons shouldBe 1
    }

    @Test
    fun `S055 RF-3 no se escribe un ZIP sin ninguna capa`() {
        val empty = builder.build(emptyList(), project())

        shouldThrow<IllegalArgumentException> { ShapefileZipWriter.write(empty, "p", ByteArrayOutputStream(), date) }
    }

    @Test
    fun `S055 RF-3 la salida no se cierra y el ZIP queda terminado y legible`() {
        class Spy : ByteArrayOutputStream() {
            var closed = false

            override fun close() {
                closed = true
                super.close()
            }
        }
        val spy = Spy()

        ShapefileZipWriter.write(builder.build(listOf(point("1")), project()), "p", spy, date)

        spy.closed shouldBe false
        unzip(spy.toByteArray()).size shouldBe 5
    }
}
