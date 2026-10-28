package es.topophone.core.formats.gpkg

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Test

/** El blob de geometría de un punto de la exportación a GeoPackage, leído con el lector de S048 (S053/RF-5, RF-6). */
class GpkgGeometryWriterTest {

    private fun read(blob: ByteArray) = GpkgGeometryReader.read(blob).shouldBeInstanceOf<GpkgGeometry.Parts>()

    @Test
    fun `S053 RF-5 un punto con altura se escribe como Point Z con su sistema y vuelve igual sin redondear`() {
        val blob = GpkgGeometryWriter.point(25830, 440_313.1221195372, 4_474_258.876880059, 648.3113666754832)

        val parts = read(blob)
        parts.srsId shouldBe 25830
        val point = parts.parts.single()
        point.kind shouldBe GpkgPartKind.POINT
        point.x.single() shouldBe 440_313.1221195372
        point.y.single() shouldBe 4_474_258.876880059
        point.z.shouldNotBeNull().single() shouldBe 648.3113666754832
    }

    @Test
    fun `S053 RF-5 un punto sin altura se escribe como Point y no lleva Z`() {
        val blob = GpkgGeometryWriter.point(4258, -3.703533016654321, 40.416815094123456, null)

        val point = read(blob).parts.single()

        point.x.single() shouldBe -3.703533016654321 // x es la longitud y y la latitud
        point.y.single() shouldBe 40.416815094123456
        point.z.shouldBeNull()
    }

    @Test
    fun `S053 RF-5 la cabecera es GP version 0 little-endian sin envolvente y el WKB empieza justo detras`() {
        val blob = GpkgGeometryWriter.point(25830, 1.0, 2.0, 3.0)

        blob[0] shouldBe 'G'.code.toByte()
        blob[1] shouldBe 'P'.code.toByte()
        blob[2] shouldBe 0
        blob[3] shouldBe 1 // little-endian, sin envolvente, no vacía
        val header = GpkgGeometryReader.header(blob).shouldNotBeNull()
        header.srsId shouldBe 25830
        header.empty shouldBe false
        header.envelope.shouldBeNull()
        header.wkbOffset shouldBe 8
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        buffer.get(8) shouldBe 1 // WKB little-endian
        buffer.getInt(9) shouldBe 1001 // Point Z en códigos ISO
        blob.size shouldBe 8 + 1 + 4 + 3 * 8
    }

    @Test
    fun `S053 RF-5 un punto sin altura mide el WKB de un Point y el codigo ISO es 1`() {
        val blob = GpkgGeometryWriter.point(25830, 1.0, 2.0, null)

        ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).getInt(9) shouldBe 1
        blob.size shouldBe 8 + 1 + 4 + 2 * 8
    }

    @Test
    fun `S053 RF-5 una geometria vacia lleva el indicador de vacia y el lector la da por vacia`() {
        val blob = GpkgGeometryWriter.empty(25830)

        GpkgGeometryReader.header(blob).shouldNotBeNull().empty shouldBe true
        GpkgGeometryReader.read(blob) shouldBe GpkgGeometry.Empty
        GpkgGeometryReader.header(blob)!!.srsId shouldBe 25830
    }

    @Test
    fun `S053 RF-5 un punto que no es finito se escribe como geometria vacia y no como un NaN en una coordenada`() {
        GpkgGeometryReader.read(GpkgGeometryWriter.of(25830, Double.NaN, 1.0, null)) shouldBe GpkgGeometry.Empty
        GpkgGeometryReader.read(GpkgGeometryWriter.of(25830, 1.0, null, null)) shouldBe GpkgGeometry.Empty
        read(GpkgGeometryWriter.of(25830, 1.0, 2.0, 3.0)).parts.single().z.shouldNotBeNull().single() shouldBe 3.0
    }

    @Test
    fun `S053 RF-5 una altura que no es finita se deja sin Z y el punto conserva su posicion`() {
        val point = read(GpkgGeometryWriter.of(25830, 1.0, 2.0, Double.NaN)).parts.single()

        point.x.single() shouldBe 1.0
        point.z.shouldBeNull()
    }
}
