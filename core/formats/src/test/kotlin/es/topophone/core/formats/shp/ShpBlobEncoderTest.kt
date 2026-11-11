package es.topophone.core.formats.shp

import es.topophone.core.formats.gpkg.GpkgGeometry
import es.topophone.core.formats.gpkg.GpkgGeometryReader
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** El blob de geometría de GeoPackage que sale de un registro del `.shp` se lee igual que el registro (S030/RF-13, RF-15). */
class ShpBlobEncoderTest {

    private fun parts(path: String): List<ShpShape.Parts> {
        val out = ArrayList<ShpShape.Parts>()
        ShpReader.open(ShpByteSource.of(ShpFixtures.bytes(path)))!!.forEachRecord { record ->
            (record.shape as? ShpShape.Parts)?.let { out += it }
        }
        return out
    }

    @Test
    fun `S030 RF-15 el blob se lee con las mismas partes que el registro en todas las capas de ejemplo`() {
        var checked = 0
        for (row in ShpFixtures.expected("esperado-capas.csv")) {
            for (shape in parts("${row.getValue("escenario")}/${row.getValue("capa")}.shp")) {
                val geometry = GpkgGeometryReader.read(ShpBlobEncoder.encode(shape)).shouldBeInstanceOf<GpkgGeometry.Parts>()

                geometry.parts.size shouldBe shape.parts.size
                for ((got, want) in geometry.parts.zip(shape.parts)) {
                    got.kind shouldBe want.kind
                    got.x.toList() shouldBe want.x.toList()
                    got.y.toList() shouldBe want.y.toList()
                    got.z?.toList() shouldBe want.z?.toList()
                    got.ring shouldBe want.ring
                }
                checked++
            }
        }
        (checked > 50) shouldBe true
    }

    @Test
    fun `S030 RF-13 la cabecera del blob trae la envolvente de la forma y se lee con solo sus primeros 40 bytes`() {
        for (shape in parts("poligonos-25830/parcelas.shp") + parts("lineas-4258/ejes.shp") + parts("puntos-25830/vertices.shp")) {
            val blob = ShpBlobEncoder.encode(shape)
            val header = GpkgGeometryReader.header(blob.copyOf(40))!!

            header.empty shouldBe false
            header.srsId shouldBe 0
            header.wkbOffset shouldBe 40
            header.envelope shouldBe shape.envelope
            GpkgGeometryReader.header(blob)!!.envelope shouldBe shape.envelope
        }
    }

    @Test
    fun `S030 RF-15 un poligono de varios anillos es un solo poligono y conserva la posicion de cada anillo`() {
        val manzana = parts("poligonos-25830/manzanas.shp")[0]

        val geometry = GpkgGeometryReader.read(ShpBlobEncoder.encode(manzana)) as GpkgGeometry.Parts

        geometry.parts.map { it.ring } shouldBe listOf(0, 1, 2)
        geometry.parts.map { it.vertexCount } shouldBe manzana.parts.map { it.vertexCount }
    }

    @Test
    fun `S030 RF-15 la Z se conserva en puntos lineas y poligonos`() {
        for (layer in listOf("puntos_z", "lineas_z", "poligonos_z", "multipuntos_z", "puntos_zm", "lineas_zm")) {
            for (shape in parts("altura-25830/$layer.shp")) {
                val geometry = GpkgGeometryReader.read(ShpBlobEncoder.encode(shape)) as GpkgGeometry.Parts
                geometry.parts.forEach { (it.z != null) shouldBe true }
            }
        }
        for (layer in listOf("puntos_m", "lineas_m", "poligonos_m")) {
            for (shape in parts("altura-25830/$layer.shp")) {
                (GpkgGeometryReader.read(ShpBlobEncoder.encode(shape)) as GpkgGeometry.Parts).parts.forEach { it.z shouldBe null }
            }
        }
    }
}
