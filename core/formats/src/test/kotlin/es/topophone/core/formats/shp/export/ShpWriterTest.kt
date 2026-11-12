package es.topophone.core.formats.shp.export

import es.topophone.core.formats.shp.ShpByteSource
import es.topophone.core.formats.shp.ShpReader
import es.topophone.core.formats.shp.ShpRecord
import es.topophone.core.formats.shp.ShpShape
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Test

/** El `.shp` y el `.shx` que escribe la exportación: los tres tipos, las envolventes, el contorno horario y el registro nulo (S055/RF-3, RF-5, RF-12 a RF-14b). */
class ShpWriterTest {
    private class Written(val shp: ByteArray, val shx: ByteArray)

    private fun write(type: ShpType, geometries: List<ShpGeometry>): Written {
        val shp = ByteArrayOutputStream()
        val shx = ByteArrayOutputStream()
        ShpWriter.write(type, geometries, shp, shx)
        return Written(shp.toByteArray(), shx.toByteArray())
    }

    private fun v(x: Double, y: Double, z: Double = 0.0) = ShpVertex(x, y, z)

    private fun big(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

    private fun little(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private fun records(shp: ByteArray): List<ShpRecord> {
        val reader = ShpReader.open(ShpByteSource.of(shp))!!
        val all = ArrayList<ShpRecord>()
        reader.forEachRecord { all += it } shouldBe true
        return all
    }

    private fun squareCcw() = listOf(v(0.0, 0.0, 1.0), v(10.0, 0.0, 2.0), v(10.0, 10.0, 3.0), v(0.0, 10.0, 4.0))

    @Test
    fun `S055 RF-3 los shp y shx de puntos lineas y poligonos coinciden byte a byte con la referencia de tools`() {
        for (layer in ShpExportFixtures.layers) {
            val written = write(ShpExportFixtures.type(layer), ShpExportFixtures.geometries(layer))

            written.shp.toList() shouldBe ShpExportFixtures.bytes("ejemplo-$layer.shp").toList()
            written.shx.toList() shouldBe ShpExportFixtures.bytes("ejemplo-$layer.shx").toList()
        }
    }

    @Test
    fun `S055 RF-3 la cabecera lleva el codigo 9994, la version 1000, el tipo y la longitud en palabras`() {
        val written = write(ShpType.POINT_Z, listOf(ShpGeometry.Point(v(1.0, 2.0, 3.0))))

        big(written.shp).getInt(0) shouldBe 9994
        big(written.shp).getInt(24) shouldBe written.shp.size / 2
        little(written.shp).getInt(28) shouldBe 1000
        little(written.shp).getInt(32) shouldBe 11
        big(written.shx).getInt(24) shouldBe written.shx.size / 2
        little(written.shx).getInt(32) shouldBe 11
        written.shp.size shouldBe 100 + 8 + 36
        written.shx.size shouldBe 100 + 8
    }

    @Test
    fun `S055 RF-3 el tipo de cada capa es 11, 13 o 15`() {
        ShpType.POINT_Z.code shouldBe 11
        ShpType.POLYLINE_Z.code shouldBe 13
        ShpType.POLYGON_Z.code shouldBe 15
    }

    @Test
    fun `S055 RF-12 un punto es un PointZ con X, Y, Z y M sin dato`() {
        val written = write(ShpType.POINT_Z, listOf(ShpGeometry.Point(v(440000.123456789, 4474000.987654321, 648.25))))
        val content = little(written.shp)

        big(written.shp).getInt(100) shouldBe 1 // el número de registro
        big(written.shp).getInt(104) shouldBe 18 // 36 bytes = 18 palabras
        content.getInt(108) shouldBe 11
        content.getDouble(112) shouldBe 440000.123456789
        content.getDouble(120) shouldBe 4474000.987654321
        content.getDouble(128) shouldBe 648.25
        content.getDouble(136) shouldBe -1e38
    }

    @Test
    fun `S055 RF-12 las coordenadas se escriben sin redondear`() {
        val x = 440000.12345678912345
        val y = -3.703800000123456
        val written = write(ShpType.POINT_Z, listOf(ShpGeometry.Point(v(x, y, 0.1 + 0.2))))

        val shape = records(written.shp).single().shape as ShpShape.Parts
        shape.parts.single().x.single() shouldBe x
        shape.parts.single().y.single() shouldBe y
        shape.parts.single().z!!.single() shouldBe 0.1 + 0.2
    }

    @Test
    fun `S055 RF-14b un objeto sin posicion es un registro nulo que conserva su numero de registro`() {
        val written = write(
            ShpType.POINT_Z,
            listOf(ShpGeometry.Point(v(1.0, 2.0, 3.0)), ShpGeometry.Null, ShpGeometry.Point(v(4.0, 5.0, 6.0))),
        )

        val all = records(written.shp)
        all.map { it.number } shouldBe listOf(1, 2, 3)
        all[1].shape shouldBe ShpShape.Null
        all[1].contentBytes shouldBe 4
        // el .shx apunta a los tres registros, también al nulo
        big(written.shx).getInt(100 + 8 + 0) shouldBe (100 + 44) / 2
        big(written.shx).getInt(100 + 8 + 4) shouldBe 2
    }

    @Test
    fun `S055 RF-3 el shx lleva la posicion y la longitud en palabras de cada registro`() {
        val written = write(
            ShpType.POLYLINE_Z,
            listOf(
                ShpGeometry.Line(listOf(v(0.0, 0.0), v(1.0, 1.0))),
                ShpGeometry.Line(listOf(v(0.0, 0.0), v(1.0, 1.0), v(2.0, 0.0))),
            ),
        )
        val index = big(written.shx)

        index.getInt(100) shouldBe 50
        index.getInt(104) shouldBe (4 + 32 + 12 + 2 * 16 + 16 + 2 * 8) / 2
        index.getInt(108) shouldBe 50 + 4 + index.getInt(104)
        index.getInt(112) shouldBe (4 + 32 + 12 + 3 * 16 + 16 + 3 * 8) / 2
    }

    @Test
    fun `S055 RF-3 la envolvente de la cabecera es la de los valores escritos, con las Z de los vertices sin altura`() {
        val written = write(
            ShpType.POINT_Z,
            listOf(
                ShpGeometry.Point(v(10.0, 20.0, 5.0)),
                ShpGeometry.Null,
                ShpGeometry.Point(v(-3.0, 40.0, 0.0)), // sin altura: Z = 0, que cuenta en la envolvente
                ShpGeometry.Point(v(7.0, -2.0, 9.5)),
            ),
        )
        val header = little(written.shp)

        listOf(36, 44, 52, 60, 68, 76).map { header.getDouble(it) } shouldBe listOf(-3.0, -2.0, 10.0, 40.0, 0.0, 9.5)
        header.getDouble(84) shouldBe 0.0 // sin M
        header.getDouble(92) shouldBe 0.0
        little(written.shx).getDouble(36) shouldBe -3.0 // el .shx lleva la misma envolvente
    }

    @Test
    fun `S055 RF-14b una capa con todos los objetos sin posicion lleva la envolvente a cero`() {
        val written = write(ShpType.POINT_Z, listOf(ShpGeometry.Null, ShpGeometry.Null))

        (36..92 step 8).map { little(written.shp).getDouble(it) } shouldBe List(8) { 0.0 }
        records(written.shp).map { it.shape } shouldBe listOf(ShpShape.Null, ShpShape.Null)
    }

    @Test
    fun `S055 RF-3 una capa sin objetos es solo la cabecera`() {
        val written = write(ShpType.POLYGON_Z, emptyList())

        written.shp.size shouldBe 100
        written.shx.size shouldBe 100
        big(written.shp).getInt(24) shouldBe 50
    }

    @Test
    fun `S055 RF-12 una linea es una PolyLineZ de una parte con la Z de cada vertice y la caja del registro`() {
        val line = listOf(v(0.0, 0.0, 648.0), v(10.5, 0.25, 648.5), v(20.0, 10.75, 0.0))
        val written = write(ShpType.POLYLINE_Z, listOf(ShpGeometry.Line(line)))
        val content = little(written.shp)

        content.getInt(108) shouldBe 13
        listOf(112, 120, 128, 136).map { content.getDouble(it) } shouldBe listOf(0.0, 0.0, 20.0, 10.75)
        content.getInt(144) shouldBe 1 // una parte
        content.getInt(148) shouldBe 3 // tres puntos
        content.getInt(152) shouldBe 0 // la parte empieza en el primer punto
        val part = records(written.shp).single().shape as ShpShape.Parts
        part.parts.single().x.toList() shouldBe listOf(0.0, 10.5, 20.0)
        part.parts.single().z!!.toList() shouldBe listOf(648.0, 648.5, 0.0)
        // sin M: tras las Z no queda nada
        written.shp.size shouldBe 100 + 8 + (4 + 32 + 12 + 3 * 16 + 16 + 3 * 8)
    }

    @Test
    fun `S055 RF-5 un poligono antihorario se invierte, conserva su primer vertice y lo repite al final`() {
        val written = write(ShpType.POLYGON_Z, listOf(ShpGeometry.Polygon(squareCcw())))

        val ring = (records(written.shp).single().shape as ShpShape.Parts).parts.single()
        ring.x.toList() shouldBe listOf(0.0, 0.0, 10.0, 10.0, 0.0)
        ring.y.toList() shouldBe listOf(0.0, 10.0, 10.0, 0.0, 0.0)
        ring.z!!.toList() shouldBe listOf(1.0, 4.0, 3.0, 2.0, 1.0) // la Z viaja con su vértice
        ring.isPolygonRing shouldBe true
    }

    @Test
    fun `S055 RF-5 un poligono ya horario queda como esta y solo se le repite el primer vertice`() {
        val clockwise = squareCcw().let { listOf(it[0]) + it.drop(1).reversed() }
        val written = write(ShpType.POLYGON_Z, listOf(ShpGeometry.Polygon(clockwise)))

        val ring = (records(written.shp).single().shape as ShpShape.Parts).parts.single()
        ring.x.toList() shouldBe (clockwise.map { it.x } + clockwise[0].x)
        ring.y.toList() shouldBe (clockwise.map { it.y } + clockwise[0].y)
    }

    @Test
    fun `S055 RF-5 el contorno escrito es horario en el plano X Y aunque el original no lo fuera`() {
        for (input in listOf(squareCcw(), squareCcw().reversed(), squareCcw().drop(1) + squareCcw().first())) {
            val closed = ShpWriter.clockwiseClosed(input)

            (ShpWriter.signedArea(closed.dropLast(1)) < 0.0) shouldBe true
            closed.last() shouldBe closed.first()
            closed.first() shouldBe input.first()
            closed.dropLast(1).toSet() shouldBe input.toSet()
        }
    }

    @Test
    fun `S055 RF-5 el signo del area no se pierde con coordenadas grandes ni con poligonos de un centimetro`() {
        val x = 440_000.0
        val y = 4_474_000.0
        val ccw = listOf(v(x, y), v(x + 0.01, y), v(x + 0.01, y + 0.01), v(x, y + 0.01))

        (ShpWriter.signedArea(ccw) > 0.0) shouldBe true
        (ShpWriter.signedArea(ccw.reversed()) < 0.0) shouldBe true
        (ShpWriter.signedArea(ShpWriter.clockwiseClosed(ccw).dropLast(1)) < 0.0) shouldBe true
    }

    @Test
    fun `S055 RF-5 un poligono de area cero no se invierte`() {
        val line = listOf(v(0.0, 0.0), v(1.0, 1.0), v(2.0, 2.0))

        ShpWriter.clockwiseClosed(line) shouldBe line + line[0]
    }

    @Test
    fun `S055 RF-12 un poligono es una PolygonZ de una parte con n mas 1 puntos y la caja del anillo`() {
        val written = write(ShpType.POLYGON_Z, listOf(ShpGeometry.Polygon(squareCcw())))
        val content = little(written.shp)

        content.getInt(108) shouldBe 15
        content.getInt(144) shouldBe 1
        content.getInt(148) shouldBe 5
        listOf(112, 120, 128, 136).map { content.getDouble(it) } shouldBe listOf(0.0, 0.0, 10.0, 10.0)
        written.shp.size shouldBe 100 + 8 + (4 + 32 + 12 + 5 * 16 + 16 + 5 * 8)
        listOf(36, 44, 52, 60, 68, 76).map { little(written.shp).getDouble(it) } shouldBe listOf(0.0, 0.0, 10.0, 10.0, 1.0, 4.0)
    }

    @Test
    fun `S055 RF-3 los registros se numeran desde 1 en el orden de los objetos`() {
        val written = write(
            ShpType.POLYGON_Z,
            List(4) { i -> ShpGeometry.Polygon(squareCcw().map { it.copy(x = it.x + 100.0 * i) }) },
        )

        records(written.shp).map { it.number } shouldBe listOf(1, 2, 3, 4)
        records(written.shp).map { ((it.shape as ShpShape.Parts).parts.single().x.min()) } shouldBe listOf(0.0, 100.0, 200.0, 300.0)
    }

    @Test
    fun `S055 RF-3 una geometria que no es del tipo de la capa es un error`() {
        shouldThrow<IllegalArgumentException> { write(ShpType.POINT_Z, listOf(ShpGeometry.Line(listOf(v(0.0, 0.0), v(1.0, 1.0))))) }
        shouldThrow<IllegalArgumentException> { write(ShpType.POLYLINE_Z, listOf(ShpGeometry.Point(v(0.0, 0.0)))) }
        shouldThrow<IllegalArgumentException> { write(ShpType.POLYLINE_Z, listOf(ShpGeometry.Polygon(squareCcw()))) }
        shouldThrow<IllegalArgumentException> { write(ShpType.POLYGON_Z, listOf(ShpGeometry.Line(listOf(v(0.0, 0.0), v(1.0, 1.0))))) }
    }

    @Test
    fun `S055 RF-3 una linea necesita 2 vertices y un poligono 3`() {
        shouldThrow<IllegalArgumentException> { ShpGeometry.Line(listOf(v(0.0, 0.0))) }
        shouldThrow<IllegalArgumentException> { ShpGeometry.Polygon(listOf(v(0.0, 0.0), v(1.0, 1.0))) }
    }

    @Test
    fun `S055 RF-12 la escritura se relee con el lector de S030 y las coordenadas son las mismas`() {
        for (layer in ShpExportFixtures.layers) {
            val geometries = ShpExportFixtures.geometries(layer)
            val written = write(ShpExportFixtures.type(layer), geometries)

            val read = records(written.shp)
            read.size shouldBe geometries.size
            geometries.zip(read).forEach { (geometry, record) ->
                when (geometry) {
                    ShpGeometry.Null -> record.shape shouldBe ShpShape.Null
                    is ShpGeometry.Point -> (record.shape as ShpShape.Parts).parts.single().let {
                        listOf(it.x.single(), it.y.single(), it.z!!.single()) shouldBe
                            listOf(geometry.vertex.x, geometry.vertex.y, geometry.vertex.z)
                    }
                    is ShpGeometry.Line -> (record.shape as ShpShape.Parts).parts.single().let {
                        it.x.toList() shouldBe geometry.vertices.map { v -> v.x }
                        it.z!!.toList() shouldBe geometry.vertices.map { v -> v.z }
                    }
                    is ShpGeometry.Polygon -> (record.shape as ShpShape.Parts).parts.single().let {
                        it.vertexCount shouldBe geometry.vertices.size + 1
                    }
                }
            }
        }
    }
}
