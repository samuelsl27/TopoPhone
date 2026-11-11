package es.topophone.core.formats.shp

import es.topophone.core.formats.gpkg.GpkgPartKind
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Test

/** Los registros de un `.shp` en todos sus tipos, con la Z, sin la M y con su envolvente (S030/RF-15, RF-16, RF-17). */
class ShpReaderTest {

    private fun reader(path: String): ShpReader = ShpReader.open(ShpByteSource.of(ShpFixtures.bytes(path))).shouldBeInstanceOf<ShpReader>()

    private fun records(path: String): List<ShpRecord> {
        val out = ArrayList<ShpRecord>()
        reader(path).forEachRecord { out += it } shouldBe true
        return out
    }

    @Test
    fun `S030 RF-15 los vertices de cada elemento son los esperados`() {
        val expected = ShpFixtures.expected("esperado.csv").filter { it.getValue("estado") == "OK" }
            .groupBy { Triple(it.getValue("escenario"), it.getValue("capa"), it.getValue("registro").toInt()) }
        var checked = 0
        for ((key, rows) in expected) {
            val (scenario, layer, number) = key
            val record = records("$scenario/$layer.shp")[number - 1]
            val shape = record.shape.shouldBeInstanceOf<ShpShape.Parts>()

            shape.parts.size shouldBe rows.size
            for ((part, row) in shape.parts.zip(rows)) {
                val want = row.getValue("vertices_fuente").split(";").map { it.split(" ") }
                part.vertexCount shouldBe want.size
                want.forEachIndexed { i, (x, y, z) ->
                    part.x[i] shouldBe x.toDouble()
                    part.y[i] shouldBe y.toDouble()
                    if (z == "_") part.z shouldBe null else part.z!![i] shouldBe z.toDouble()
                }
                part.kind shouldBe if (row.getValue("capa_diseno") == "POINTS") GpkgPartKind.POINT else GpkgPartKind.LINE
                part.ring shouldBe row.getValue("anillo").takeIf { it.isNotEmpty() }?.toInt()
            }
            checked++
        }
        (checked > 40) shouldBe true
    }

    @Test
    fun `S030 RF-15 un poligono da una linea cerrada por anillo con su posicion y las lineas de varias partes una por parte`() {
        val parcelas = records("poligonos-25830/parcelas.shp")
        val parcela2 = parcelas[1].shape.shouldBeInstanceOf<ShpShape.Parts>()
        parcela2.parts.map { it.ring } shouldBe listOf(0, 1, 2)
        parcela2.parts.forEach { (it.x.first() to it.y.first()) shouldBe (it.x.last() to it.y.last()) }

        val manzana = records("poligonos-25830/manzanas.shp")[0].shape.shouldBeInstanceOf<ShpShape.Parts>()
        manzana.parts.map { it.ring } shouldBe listOf(0, 1, 2)

        val camino = records("lineas-4258/ejes.shp")[2].shape.shouldBeInstanceOf<ShpShape.Parts>()
        camino.parts.map { it.kind to it.ring } shouldBe listOf(GpkgPartKind.LINE to null, GpkgPartKind.LINE to null)
        camino.parts.map { it.vertexCount } shouldBe listOf(4, 2)

        val grupo = records("multipuntos-25830/multipuntos.shp")[0].shape.shouldBeInstanceOf<ShpShape.Parts>()
        grupo.parts.map { it.kind } shouldBe List(3) { GpkgPartKind.POINT }
    }

    @Test
    fun `S030 RF-17 la Z se lee con y sin M y la M no cuenta`() {
        val zOnly = records("altura-25830/puntos_z.shp")[0].shape.shouldBeInstanceOf<ShpShape.Parts>().parts.single()
        zOnly.z!![0] shouldBe 646.377
        val zm = records("altura-25830/puntos_zm.shp")[0].shape.shouldBeInstanceOf<ShpShape.Parts>().parts.single()
        zm.z!![0] shouldBe 646.377
        val onlyM = records("altura-25830/puntos_m.shp")[0].shape.shouldBeInstanceOf<ShpShape.Parts>().parts.single()
        onlyM.z shouldBe null
        for (layer in listOf("lineas_m", "poligonos_m", "multipuntos_m")) {
            records("altura-25830/$layer.shp")[0].shape.shouldBeInstanceOf<ShpShape.Parts>().parts.forEach { it.z shouldBe null }
        }
        for (layer in listOf("lineas_z", "lineas_zm", "poligonos_z", "multipuntos_z")) {
            records("altura-25830/$layer.shp")[0].shape.shouldBeInstanceOf<ShpShape.Parts>().parts.forEach { (it.z != null) shouldBe true }
        }
        records("altura-25830/lineas_z.shp")[0].shape.shouldBeInstanceOf<ShpShape.Parts>().parts.single().z!!.toList() shouldBe
            listOf(100.0, 101.0, 102.0)
    }

    @Test
    fun `S030 RF-16 una forma nula y un MultiPatch no son geometria`() {
        val nulos = records("borrados-y-nulos/nulos.shp")
        nulos.map { it.shape::class } shouldBe listOf(
            ShpShape.Parts::class,
            ShpShape.Null::class,
            ShpShape.Parts::class,
            ShpShape.Null::class,
        )
        val patches = records("borrados-y-nulos/multipatch.shp")
        patches.size shouldBe 2
        patches.forEach { it.shape shouldBe ShpShape.MultiPatch }
        reader("borrados-y-nulos/multipatch.shp").header.shapeType shouldBe 31
    }

    @Test
    fun `S030 RF-15 la cabecera da el tipo y el numero de registro es la posicion desde 1`() {
        for (row in ShpFixtures.expected("esperado-capas.csv")) {
            val shp = reader("${row.getValue("escenario")}/${row.getValue("capa")}.shp")
            shp.header.shapeType shouldBe row.getValue("tipo_shp").toInt()
            val all = ArrayList<ShpRecord>()
            shp.forEachRecord { all += it } shouldBe true
            all.size shouldBe row.getValue("objetos").toInt()
            all.map { it.index } shouldBe (1..all.size).toList()
            all.map { it.number } shouldBe (1..all.size).toList()
        }
    }

    @Test
    fun `S030 RF-15 la envolvente de cada registro es el recuadro de sus vertices`() {
        val shp = reader("poligonos-25830/parcelas.shp")
        shp.forEachRecord { record ->
            val shape = record.shape.shouldBeInstanceOf<ShpShape.Parts>()
            val xs = shape.parts.flatMap { it.x.asList() }
            val ys = shape.parts.flatMap { it.y.asList() }
            shape.envelope.minX shouldBe xs.min()
            shape.envelope.maxX shouldBe xs.max()
            shape.envelope.minY shouldBe ys.min()
            shape.envelope.maxY shouldBe ys.max()
        }
        val point = records("puntos-25830/vertices.shp")[0].shape.shouldBeInstanceOf<ShpShape.Parts>()
        point.envelope.minX shouldBe point.envelope.maxX
        point.envelope.minY shouldBe point.envelope.maxY
    }

    @Test
    fun `S030 RF-15 un registro se lee por su posicion en el fichero`() {
        val shp = reader("poligonos-25830/parcelas.shp")
        val all = ArrayList<ShpRecord>()
        shp.forEachRecord { all += it }

        for (record in all) {
            val again = shp.readAt(record.offset, record.index)!!
            again.index shouldBe record.index
            again.number shouldBe record.number
            (again.shape as ShpShape.Parts).parts.map { it.vertexCount } shouldBe
                (record.shape as ShpShape.Parts).parts.map { it.vertexCount }
        }
        shp.readAt(10_000_000, 1) shouldBe null
        shp.readAt(1, 1) shouldBe null // no es el comienzo de un registro: la longitud no cuadra
    }

    @Test
    fun `S030 RF-15 los ficheros cortados o con cuentas imposibles no rompen la lectura`() {
        val good = ShpFixtures.bytes("poligonos-25830/parcelas.shp")

        // cortado a mitad del segundo registro: se lee el primero y se avisa de que no está entero
        val first = ArrayList<ShpRecord>()
        ShpReader.open(ShpByteSource.of(good.copyOf(good.size - 20))).shouldBeInstanceOf<ShpReader>().forEachRecord { first += it } shouldBe
            false
        first.size shouldBe 1

        // un registro con tres partes que dice tener un millón de puntos
        val broken = good.copyOf()
        val offset = 100 + 8 // el primer registro
        ByteBuffer.wrap(broken).order(ByteOrder.LITTLE_ENDIAN).putInt(offset + 40, 1_000_000)
        val shapes = ArrayList<ShpShape>()
        ShpReader.open(ShpByteSource.of(broken))!!.forEachRecord { shapes += it.shape }
        shapes[0] shouldBe ShpShape.Invalid

        // una cabecera que no es de Shapefile no se abre
        ShpReader.open(ShpByteSource.of(ByteArray(120))) shouldBe null
        ShpReader.open(ShpByteSource.of(ByteArray(10))) shouldBe null
    }

    @Test
    fun `S030 RF-16 un punto con coordenadas que no son numeros es una forma vacia`() {
        val file = ShpFixtures.bytes("puntos-25830/vertices.shp")
        val patched = file.copyOf()
        ByteBuffer.wrap(patched).order(ByteOrder.LITTLE_ENDIAN).putDouble(100 + 8 + 4, Double.NaN)
        val shapes = ArrayList<ShpShape>()
        ShpReader.open(ShpByteSource.of(patched))!!.forEachRecord { shapes += it.shape }

        shapes[0] shouldBe ShpShape.Null
        shapes[1].shouldBeInstanceOf<ShpShape.Parts>()
    }

    @Test
    fun `S030 RF-15 las coordenadas leidas coinciden con las que escribe QGIS`() {
        val expected = ShpFixtures.expected("qgis/esperado-qgis-geometria.csv")
        (expected.size > 10) shouldBe true
        for (row in expected) {
            val record = records("qgis/${row.getValue("escenario")}/${row.getValue("capa")}.shp")[row.getValue("registro").toInt() - 1]
            val parts = record.shape.shouldBeInstanceOf<ShpShape.Parts>().parts
            val want = row.getValue("vertices").split(";").map { it.split(" ") }
            val part = parts[row.getValue("parte").toInt()]
            part.vertexCount shouldBe want.size
            want.forEachIndexed { i, (x, y) ->
                part.x[i] shouldBe (x.toDouble() plusOrMinus 1e-9)
                part.y[i] shouldBe (y.toDouble() plusOrMinus 1e-9)
            }
        }
    }
}
