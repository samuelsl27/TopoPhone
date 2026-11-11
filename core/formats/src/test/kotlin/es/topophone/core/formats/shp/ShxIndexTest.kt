package es.topophone.core.formats.shp

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.ByteBuffer
import org.junit.jupiter.api.Test

/** Dónde empieza cada registro: del `.shx` si cuadra con el `.shp` y, si no, de una pasada por el `.shp` (S030/RF-19). */
class ShxIndexTest {

    private fun shp(path: String): ShpReader = ShpReader.open(ShpByteSource.of(ShpFixtures.bytes(path))).shouldBeInstanceOf<ShpReader>()

    private fun sequentialOffsets(path: String): List<Long> {
        val out = ArrayList<Long>()
        shp(path).forEachRecord { out += it.offset } shouldBe true
        return out
    }

    private fun shx(path: String): ShpByteSource? = ShpFixtures.file(path).takeIf { it.exists() }?.let { ShpByteSource.of(it.readBytes()) }

    @Test
    fun `S030 RF-19 con el shx los desplazamientos son los de los registros y el indice se lee del shx`() {
        val index = ShxIndex.read(shx("puntos-25830/vertices.shx")!!, shp("puntos-25830/vertices.shp"))

        index!!.fromShx shouldBe true
        index.size shouldBe 5
        index.offsets.toList() shouldBe sequentialOffsets("puntos-25830/vertices.shp")
        index.offsetOf(1) shouldBe 100L
    }

    @Test
    fun `S030 RF-19 sin shx una pasada por el shp da los mismos desplazamientos`() {
        val index = ShxIndex.of(shx("incompletos/sin_shx.shx"), shp("incompletos/sin_shx.shp"))

        index.fromShx shouldBe false
        index.complete shouldBe true
        index.offsets.toList() shouldBe sequentialOffsets("incompletos/sin_shx.shp")
    }

    @Test
    fun `S030 RF-19 un shx que no cuadra con el shp se descarta y se recorre el shp`() {
        ShxIndex.read(shx("incompletos/shx_roto.shx")!!, shp("incompletos/shx_roto.shp")) shouldBe null

        val index = ShxIndex.of(shx("incompletos/shx_roto.shx"), shp("incompletos/shx_roto.shp"))
        index.fromShx shouldBe false
        index.offsets.toList() shouldBe sequentialOffsets("incompletos/shx_roto.shp")
    }

    @Test
    fun `S030 RF-19 todas las capas de ejemplo dan los desplazamientos de sus registros`() {
        var fromFile = 0
        for (row in ShpFixtures.expected("esperado-capas.csv")) {
            val path = "${row.getValue("escenario")}/${row.getValue("capa")}"
            val index = ShxIndex.of(shx("$path.shx"), shp("$path.shp"))

            index.offsets.toList() shouldBe sequentialOffsets("$path.shp")
            index.size shouldBe row.getValue("objetos").toInt()
            index.fromShx shouldBe (row.getValue("shx") == "SI")
            if (index.fromShx) fromFile++
        }
        (fromFile > 30) shouldBe true
    }

    @Test
    fun `S030 RF-19 la posicion de un registro se busca por su numero desde 1`() {
        val reader = shp("poligonos-25830/parcelas.shp")
        val index = ShxIndex.of(shx("poligonos-25830/parcelas.shx"), reader)

        index.offsetOf(2) shouldBe sequentialOffsets("poligonos-25830/parcelas.shp")[1]
        reader.readAt(index.offsetOf(2), 2)!!.number shouldBe 2
    }

    @Test
    fun `S030 RF-19 un shp cortado da los registros enteros y dice que no esta completo`() {
        val good = ShpFixtures.bytes("poligonos-25830/parcelas.shp")
        val reader = ShpReader.open(ShpByteSource.of(good.copyOf(good.size - 20))).shouldBeInstanceOf<ShpReader>()

        val index = ShxIndex.scan(reader)

        index.size shouldBe 1
        index.complete shouldBe false
    }

    @Test
    fun `S030 RF-19 un shx con un registro de menos o de mas no cuadra`() {
        val reader = shp("puntos-25830/vertices.shp")
        val good = ShpFixtures.bytes("puntos-25830/vertices.shx")

        ShxIndex.read(ShpByteSource.of(good.copyOf(good.size - 8)), reader) shouldBe null // uno de menos
        ShxIndex.read(ShpByteSource.of(good.copyOf(good.size + 8)), reader) shouldBe null // uno de más (a cero)
        ShxIndex.read(ShpByteSource.of(good.copyOf(good.size - 3)), reader) shouldBe null // no es múltiplo de 8
        ShxIndex.read(ShpByteSource.of(good.copyOf(50)), reader) shouldBe null // sin cabecera completa
        val shifted = good.copyOf()
        ByteBuffer.wrap(shifted).putInt(100 + 8, 999) // el segundo registro empieza donde no es
        ShxIndex.read(ShpByteSource.of(shifted), reader) shouldBe null
    }
}
