package es.topophone.core.formats.kml.export

import es.topophone.core.formats.shp.export.ShpExportSamples.attribute
import es.topophone.core.formats.shp.export.ShpExportSamples.code
import es.topophone.core.formats.shp.export.ShpExportSamples.line
import es.topophone.core.formats.shp.export.ShpExportSamples.point
import es.topophone.core.formats.shp.export.ShpExportSamples.project
import es.topophone.core.model.codes.AttributeType
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.time.measureTimedValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Exportar 10.000 puntos con todas sus columnas, más unas líneas, a un KML tarda menos de 5 s (S031, RNF). La JVM sirve de cota; la medida
 * en el móvil de referencia es de la demo. El peso del fichero no se fija: se anota (suposición de la spec).
 */
class KmlExportPerformanceTest {

    @Test
    fun `S031 RNF exportar 10000 puntos con sus columnas a un KML tarda menos de 5 s`(@TempDir folder: File) {
        val tree = code("ARBOL", attribute("Diámetro", AttributeType.NUMBER, "0.35"), attribute("Ramas", AttributeType.INTEGER, "4"))
        val points = (0 until POINTS).map { i ->
            point(
                id = "$i",
                easting = 440_000.0 + i,
                northing = 4_474_000.0 + i * 0.5,
                latitude = 40.4168 + i * 1e-6,
                longitude = -3.7038 + i * 1e-6,
                code = if (i % 3 == 0) tree else null,
                description = "Punto de prueba $i",
            )
        }
        val lines = (0 until LINES).map { i -> line("l$i", (0 until 20).map { "${i * 20 + it}" }, name = "Línea $i") }
        val file = File(folder, "obra.kml")

        val timed = measureTimedValue {
            val export = KmlExportBuilder().build(points, project(), KmlExportOptions(includeHeight = true), lines = lines)
            file.writer(Charsets.UTF_8).buffered(1 shl 20).use { KmlWriter.write(export.document, it) }
            export
        }

        val millis = timed.duration.inWholeMilliseconds
        println("S031 exportar $POINTS puntos y $LINES líneas: $millis ms, ${file.length() / (1024 * 1024)} MB")
        timed.value.summary.points shouldBe POINTS
        timed.value.summary.lines shouldBe LINES
        (timed.duration.inWholeMilliseconds < 5_000) shouldBe true
    }

    private companion object {
        const val POINTS = 10_000
        const val LINES = 50
    }
}
