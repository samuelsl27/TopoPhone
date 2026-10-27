package es.topophone.core.formats.gpkg

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Test

/** La geometría de un objeto de GeoPackage: cabecera del blob y WKB ISO o EWKB, Z, M, múltiples, vacíos y curvas (S048/RF-17 a RF-19). */
class GpkgGeometryTest {

    private val blobs = GpkgFixtures.blobs()
    private val expected = GpkgFixtures.expected("esperado.csv")
    private val layers = GpkgFixtures.expected("esperado-capas.csv")

    private fun objects(state: String) = expected.filter {
        it["estado"] == state
    }.groupBy { Triple(it.getValue("archivo"), it.getValue("tabla"), it.getValue("fid").toInt()) }

    private fun parts(key: Triple<String, String, Int>): List<GpkgPart> {
        val geometry = GpkgGeometryReader.read(blobs.getValue(key)!!)
        return geometry.shouldBeInstanceOf<GpkgGeometry.Parts>().parts
    }

    /** "x y z;x y z" con `_` si no hay altura. */
    private fun vertices(text: String): List<List<Double?>> = text.split(";").map { v ->
        v.split(" ").map {
            if (it ==
                "_"
            ) {
                null
            } else {
                it.toDouble()
            }
        }
    }

    @Test
    fun `S048 RF-17 los elementos de cada objeto de los ejemplos son los esperados bit a bit`() {
        var checked = 0
        for ((key, rows) in objects("OK")) {
            val result = parts(key)
            result.size shouldBe rows.size
            for ((part, row) in result.zip(rows)) {
                part.kind shouldBe if (row["capa_diseno"] == "POINTS") GpkgPartKind.POINT else GpkgPartKind.LINE
                part.ring shouldBe row.getValue("anillo").toIntOrNull()
                part.isPolygonRing shouldBe (row["contorno"] == "SI")
                val wanted = vertices(row.getValue("vertices_fuente"))
                part.vertexCount shouldBe wanted.size
                for (i in wanted.indices) {
                    part.x[i] shouldBe wanted[i][0]
                    part.y[i] shouldBe wanted[i][1]
                    part.z?.get(i) shouldBe wanted[i][2]
                }
                checked++
            }
        }
        (checked > 60) shouldBe true
    }

    @Test
    fun `S048 RF-17 un poligono con huecos da el contorno exterior y un anillo por hueco`() {
        val rings = parts(Triple("poligonos-25830.gpkg", "parcelas", 2))

        rings.map { it.ring } shouldBe listOf(0, 1, 2)
        rings.all { it.kind == GpkgPartKind.LINE && it.isPolygonRing } shouldBe true
        rings.map { it.vertexCount } shouldBe listOf(5, 5, 5)
    }

    @Test
    fun `S048 RF-17 los multiples y las colecciones se separan por partes en el orden del fichero`() {
        parts(Triple("multiples-25830.gpkg", "multipuntos", 1)).map { it.kind } shouldBe List(3) { GpkgPartKind.POINT }
        parts(Triple("multiples-25830.gpkg", "multilineas", 1)).map { it.vertexCount } shouldBe listOf(2, 3)
        parts(Triple("multiples-25830.gpkg", "mixta", 3)).map { it.kind to it.ring } shouldBe
            listOf(GpkgPartKind.POINT to null, GpkgPartKind.LINE to null, GpkgPartKind.LINE to 0, GpkgPartKind.LINE to 1)
        // Un multipolígono: el anillo vuelve a empezar en 0 en cada polígono.
        parts(Triple("poligonos-25830.gpkg", "manzanas", 1)).map { it.ring } shouldBe listOf(0, 0, 1)
    }

    @Test
    fun `S048 RF-19 Z se conserva y M se descarta`() {
        parts(Triple("altura-25830.gpkg", "puntos_z", 1)).single().z!!.toList() shouldBe listOf(646.377)
        parts(Triple("altura-25830.gpkg", "puntos_z", 2)).single().z!!.toList() shouldBe listOf(0.0)
        parts(Triple("altura-25830.gpkg", "lineas_z", 1)).single().z!!.toList() shouldBe listOf(100.0, 101.0, 102.0)
        parts(Triple("altura-25830.gpkg", "lineas_zm", 1)).single().z!!.toList() shouldBe listOf(100.0, 101.0, 102.0)
        parts(Triple("altura-25830.gpkg", "lineas_m", 1)).single().z shouldBe null
        parts(Triple("puntos-25830.gpkg", "vertices", 1)).single().z shouldBe null
        parts(Triple("altura-25830.gpkg", "poligonos_z", 1)).all { it.z != null } shouldBe true
    }

    @Test
    fun `S048 RF-17 el WKB en big endian, el EWKB con Z y SRID y la cabecera en big endian dan lo mismo`() {
        val little = parts(Triple("codificaciones-25830.gpkg", "wkb_big_endian", 2))
        val header = parts(Triple("codificaciones-25830.gpkg", "cabecera_big_endian", 2))
        val ewkb = parts(Triple("codificaciones-25830.gpkg", "ewkb_z", 2))

        little.single().x.toList() shouldBe header.single().x.toList()
        ewkb.single().x.toList() shouldBe little.single().x.toList()
        ewkb.single().z!!.toList() shouldBe listOf(50.0, 50.0)
        parts(Triple("codificaciones-25830.gpkg", "ewkb_z", 3)).map { it.ring } shouldBe listOf(0, 1)
        GpkgGeometryReader.header(blobs.getValue(Triple("codificaciones-25830.gpkg", "cabecera_big_endian", 1))!!)!!.srsId shouldBe 25830
    }

    @Test
    fun `S048 RF-18 los vacios y las curvas se distinguen`() {
        for ((key, _) in objects("ignorado_vacia")) {
            val blob = blobs.getValue(key)
            if (blob != null) GpkgGeometryReader.read(blob) shouldBe GpkgGeometry.Empty
        }
        for ((key, _) in objects("ignorado_curva")) GpkgGeometryReader.read(blobs.getValue(key)!!) shouldBe GpkgGeometry.Curve
        objects("ignorado_vacia").size shouldBe 3
        objects("ignorado_curva").size shouldBe 3
    }

    @Test
    fun `S048 RF-18 una coleccion con una curva dentro es una curva y un multipunto con un punto vacio solo pierde ese punto`() {
        val collection = wkb(7) {
            putInt(2)
            put(point(1.0, 2.0))
            put(wkb(8) { putInt(0) })
        }
        GpkgGeometryReader.read(blob(collection)) shouldBe GpkgGeometry.Curve

        val multipoint = wkb(4) {
            putInt(2)
            put(point(1.0, 2.0))
            put(point(Double.NaN, Double.NaN))
        }
        GpkgGeometryReader.read(blob(multipoint)).shouldBeInstanceOf<GpkgGeometry.Parts>().parts.size shouldBe 1

        val onlyEmpty = wkb(4) {
            putInt(1)
            put(point(Double.NaN, Double.NaN))
        }
        GpkgGeometryReader.read(blob(onlyEmpty)) shouldBe GpkgGeometry.Empty
    }

    @Test
    fun `S048 RF-18 un poligono sin anillo exterior es vacio y sus huecos no salen solos`() {
        val polygon = wkb(3) {
            putInt(2)
            putInt(0)
            putInt(4)
            ring(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 0.0)
        }

        GpkgGeometryReader.read(blob(polygon)) shouldBe GpkgGeometry.Empty
        GpkgGeometryReader.read(blob(wkb(3) { putInt(0) })) shouldBe GpkgGeometry.Empty
        GpkgGeometryReader.read(blob(wkb(2) { putInt(0) })) shouldBe GpkgGeometry.Empty
    }

    @Test
    fun `S048 RF-13 la cabecera da el SRS y la envolvente y con 40 bytes basta`() {
        var withEnvelope = 0
        for (row in layers) {
            val table = row.getValue("tabla")
            val file = row.getValue("archivo")
            for (fid in 1..row.getValue("objetos").toInt()) {
                val blob = blobs.getValue(Triple(file, table, fid)) ?: continue
                val header = GpkgGeometryReader.header(blob)!!
                header.srsId shouldBe row.getValue("srs_id").toInt()
                if (header.envelope != null) {
                    withEnvelope++
                    GpkgGeometryReader.header(blob.copyOf(40)) shouldBe header
                }
                (header.envelope != null) shouldBe (row["envolvente"] == "SI" && !header.empty)
            }
        }
        (withEnvelope > 50) shouldBe true
    }

    @Test
    fun `S048 RF-13 la envolvente de la cabecera es el recuadro de los vertices`() {
        val blob = blobs.getValue(Triple("lineas-4258.gpkg", "ejes", 2))!!
        val line = parts(Triple("lineas-4258.gpkg", "ejes", 2)).single()

        val box = GpkgGeometryReader.header(blob)!!.envelope!!

        box shouldBe GpkgEnvelope(line.x.min(), line.x.max(), line.y.min(), line.y.max())
    }

    @Test
    fun `S048 RF-13 un objeto marcado vacio no trae envolvente y uno sin envolvente tampoco`() {
        val empty = blobs.getValue(Triple("curvas-y-vacios-25830.gpkg", "raros", 3))!!
        val bare = blobs.getValue(Triple("sin-rtree-25830.gpkg", "sin_envolvente", 1))!!

        GpkgGeometryReader.header(empty)!!.empty shouldBe true
        GpkgGeometryReader.header(empty)!!.envelope shouldBe null
        GpkgGeometryReader.header(bare)!!.envelope shouldBe null
        GpkgGeometryReader.header(bare)!!.wkbOffset shouldBe 8
        GpkgGeometryReader.header(blobs.getValue(Triple("altura-25830.gpkg", "lineas_z", 1))!!)!!.wkbOffset shouldBe 8 + 48
    }

    @Test
    fun `S048 RF-17 un blob que no es de GeoPackage o esta danado es invalido y nunca lanza`() {
        GpkgGeometryReader.read(ByteArray(0)) shouldBe GpkgGeometry.Invalid
        GpkgGeometryReader.read("GX".toByteArray() + ByteArray(20)) shouldBe GpkgGeometry.Invalid
        GpkgGeometryReader.read(blob(byteArrayOf(), flags = 1)) shouldBe GpkgGeometry.Invalid
        GpkgGeometryReader.read(blob(point(1.0, 2.0), flags = 1 or (5 shl 1))) shouldBe GpkgGeometry.Invalid
        GpkgGeometryReader.read(blob(point(1.0, 2.0), flags = 1 or 32)) shouldBe GpkgGeometry.Invalid
        GpkgGeometryReader.read(blob(point(1.0, 2.0)).also { it[2] = 1 }) shouldBe GpkgGeometry.Invalid
        GpkgGeometryReader.read(blob(wkb(99) { putDouble(1.0) })) shouldBe GpkgGeometry.Invalid
        GpkgGeometryReader.read(blob(byteArrayOf(7, 1, 0, 0, 0))) shouldBe GpkgGeometry.Invalid
    }

    @Test
    fun `S048 RF-17 un WKB cortado o con cuentas imposibles es invalido`() {
        val whole = blob(
            wkb(2) {
                putInt(3)
                repeat(6) { putDouble(it.toDouble()) }
            },
        )
        GpkgGeometryReader.read(whole).shouldBeInstanceOf<GpkgGeometry.Parts>()
        GpkgGeometryReader.read(whole.copyOf(whole.size - 4)) shouldBe GpkgGeometry.Invalid
        GpkgGeometryReader.read(blob(wkb(2) { putInt(Int.MAX_VALUE) })) shouldBe GpkgGeometry.Invalid
        GpkgGeometryReader.read(blob(wkb(2) { putInt(-1) })) shouldBe GpkgGeometry.Invalid
        GpkgGeometryReader.read(blob(wkb(3) { putInt(Int.MAX_VALUE) })) shouldBe GpkgGeometry.Invalid
        GpkgGeometryReader.read(blob(wkb(6) { putInt(1_000_000) })) shouldBe GpkgGeometry.Invalid
        GpkgGeometryReader.read(blob(wkb(1) { putDouble(1.0) })) shouldBe GpkgGeometry.Invalid
    }

    @Test
    fun `S048 RF-17 una coordenada no finita en una linea es invalida y colecciones muy anidadas tambien`() {
        GpkgGeometryReader.read(
            blob(
                wkb(2) {
                    putInt(1)
                    putDouble(Double.NaN)
                    putDouble(1.0)
                },
            ),
        ) shouldBe GpkgGeometry.Invalid
        GpkgGeometryReader.read(
            blob(
                wkb(1) {
                    putDouble(Double.POSITIVE_INFINITY)
                    putDouble(1.0)
                },
            ),
        ) shouldBe GpkgGeometry.Invalid

        var nested = point(1.0, 2.0)
        repeat(20) {
            nested = wkb(7) {
                putInt(1)
                put(nested)
            }
        }
        GpkgGeometryReader.read(blob(nested)) shouldBe GpkgGeometry.Invalid
    }

    @Test
    fun `S048 RF-19 los codigos ISO de Z M y ZM y los de EWKB dan las mismas coordenadas`() {
        val iso = blob(
            wkb(1001) {
                putDouble(1.0)
                putDouble(2.0)
                putDouble(3.0)
            },
        )
        val ewkb = blob(
            wkb((0x80000000L or 1L).toInt()) {
                putDouble(1.0)
                putDouble(2.0)
                putDouble(3.0)
            },
        )
        val zm = blob(
            wkb(3001) {
                putDouble(1.0)
                putDouble(2.0)
                putDouble(3.0)
                putDouble(9.0)
            },
        )
        val m = blob(
            wkb(2001) {
                putDouble(1.0)
                putDouble(2.0)
                putDouble(9.0)
            },
        )

        for (b in listOf(iso, ewkb, zm)) {
            val part = GpkgGeometryReader.read(b).shouldBeInstanceOf<GpkgGeometry.Parts>().parts.single()
            part.x.toList() shouldBe listOf(1.0)
            part.y.toList() shouldBe listOf(2.0)
            part.z!!.toList() shouldBe listOf(3.0)
        }
        GpkgGeometryReader.read(m).shouldBeInstanceOf<GpkgGeometry.Parts>().parts.single().z shouldBe null
    }

    // ---- WKB a mano para los casos que los ejemplos no tienen ----

    private fun wkb(type: Int, body: ByteBuffer.() -> Unit): ByteArray {
        val buffer = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(1).putInt(type)
        buffer.body()
        return buffer.array().copyOf(buffer.position())
    }

    private fun ByteBuffer.ring(vararg xy: Double) {
        xy.forEach { putDouble(it) }
    }

    private fun point(x: Double, y: Double): ByteArray = wkb(1) {
        putDouble(x)
        putDouble(y)
    }

    /** El blob de GeoPackage (little endian por defecto, sin envolvente) alrededor de un WKB. */
    private fun blob(wkb: ByteArray, flags: Int = 1, srs: Int = 25830): ByteArray {
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        header.put('G'.code.toByte()).put('P'.code.toByte()).put(0).put(flags.toByte()).putInt(srs)
        return header.array() + wkb
    }
}
