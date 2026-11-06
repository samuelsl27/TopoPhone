package es.topophone.core.formats.dxf.export

import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import kotlin.time.measureTimedValue
import org.junit.jupiter.api.Test

/** Con 10 000 puntos con dos etiquetas cada uno, el DXF se escribe en menos de 5 s y pesa menos de 10 MB (S029, RNF). La JVM es la cota. */
class DxfExportPerformanceTest {
    private val code = DxfTestData.code("ARBOL", 0x008000)
    private val points = List(POINTS) { i ->
        DxfTestData.point("P$i", 440_000.123456 + i, 4_474_000.654321 + i / 100.0, 650.0 + i / 1000.0, code, "Bordillo tramo $i")
    }
    private val lines = listOf(DxfTestData.line("Todo", false, code, *points.toTypedArray()))

    @Test
    fun `S029 RNF 10000 puntos con nombre y cota se escriben en menos de 5 s y pesan menos de 10 MB`() {
        val timed = measureTimedValue {
            val build = DxfDocumentBuilder.build(points, lines, points, DxfTestData.project(), DxfExportOptions())
            val out = ByteArrayOutputStream()
            DxfWriter.write(build.document, out)
            build.summary to out.size()
        }

        val (summary, size) = timed.value
        summary.points shouldBe POINTS
        timed.duration.inWholeMilliseconds shouldBeLessThan 5_000L
        size.toLong() shouldBeLessThan 10L * 1024 * 1024
    }

    @Test
    fun `S029 RNF con tres etiquetas por punto tambien cabe`() {
        val timed = measureTimedValue {
            val options = DxfExportOptions(labels = DxfLabel.entries.toSet())
            val build = DxfDocumentBuilder.build(points, emptyList(), points, DxfTestData.project(), options)
            val out = ByteArrayOutputStream()
            DxfWriter.write(build.document, out)
            out.size()
        }

        timed.duration.inWholeMilliseconds shouldBeLessThan 5_000L
        timed.value.toLong() shouldBeLessThan 10L * 1024 * 1024
    }

    private companion object {
        const val POINTS = 10_000
    }
}
