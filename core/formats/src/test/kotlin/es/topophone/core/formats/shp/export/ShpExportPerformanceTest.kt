package es.topophone.core.formats.shp.export

import es.topophone.core.formats.shp.export.ShpExportSamples.attribute
import es.topophone.core.formats.shp.export.ShpExportSamples.code
import es.topophone.core.formats.shp.export.ShpExportSamples.measured
import es.topophone.core.formats.shp.export.ShpExportSamples.project
import es.topophone.core.model.codes.AttributeType
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream
import kotlin.time.measureTimedValue
import org.junit.jupiter.api.Test

/**
 * Exportar 10.000 puntos con todas sus columnas y los atributos de su código tarda menos de 5 s (S055, RNF). La JVM del equipo de desarrollo
 * sirve de cota; la medida en el móvil de referencia es de la demo.
 */
class ShpExportPerformanceTest {

    @Test
    fun `S055 RNF exportar 10000 puntos con sus columnas a un ZIP tarda menos de 5 s`() {
        val arbol = code(
            "ARBOL",
            attribute("Diámetro", AttributeType.NUMBER, "0.35"),
            attribute("Ramas", AttributeType.INTEGER, "4"),
            attribute("Especie", AttributeType.TEXT, "Pino carrasco"),
        )
        val points = List(POINTS) { i ->
            val base = measured("$i")
            base.copy(
                code = arbol,
                coordinates = base.coordinates.copy(
                    easting = 440_000.0 + (i % 100) * 3.1234567,
                    northing = 4_474_000.0 + (i / 100) * 2.7654321,
                ),
            )
        }
        val builder = ShpExportBuilder()
        val out = ByteArrayOutputStream()

        val timed = measureTimedValue {
            val export = builder.build(points, project())
            ShapefileZipWriter.write(export, "Obra", out, LocalDate.of(2026, 11, 12))
        }

        println("S055 exportar $POINTS puntos: ${timed.duration.inWholeMilliseconds} ms, ${out.size() / 1024} kB de ZIP")
        timed.value.points shouldBe POINTS
        (timed.duration.inWholeMilliseconds < MAX_MS) shouldBe true
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            generateSequence { zip.nextEntry }.forEach { names += it.name }
        }
        names.size shouldBe 5
    }

    @Test
    fun `S055 RNF exportar 10000 puntos con lineas y poligonos tampoco pasa de 5 s`() {
        val points = List(POINTS) { i -> ShpExportSamples.point("$i", easting = 440_000.0 + i, northing = 4_474_000.0 + (i % 7)) }
        val lines = (0 until POINTS / 100).map { l ->
            ShpExportSamples.line("l$l", (0 until 100).map { "${l * 100 + it}" }, closed = l % 2 == 0)
        }
        val builder = ShpExportBuilder()
        val out = ByteArrayOutputStream()

        val timed = measureTimedValue {
            ShapefileZipWriter.write(builder.build(points, project(), lines = lines), "Obra", out, LocalDate.of(2026, 11, 12))
        }

        println("S055 exportar $POINTS puntos y ${lines.size} líneas y polígonos: ${timed.duration.inWholeMilliseconds} ms")
        (timed.value.lines + timed.value.polygons) shouldBe lines.size
        (timed.duration.inWholeMilliseconds < MAX_MS) shouldBe true
    }

    private companion object {
        const val POINTS = 10_000
        const val MAX_MS = 5_000
    }
}
