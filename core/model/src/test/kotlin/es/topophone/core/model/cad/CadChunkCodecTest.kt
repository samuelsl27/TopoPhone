package es.topophone.core.model.cad

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.random.Random
import org.junit.jupiter.api.Test

/** El bloque binario de un trozo del dibujo (S040/RF-19, RF-22, RF-23). */
class CadChunkCodecTest {

    private fun line(zs: DoubleArray? = null, color: CadColor? = null) = CadEntity(
        CadEntityKind.LINE,
        doubleArrayOf(440000.123456789, 440010.987654321),
        doubleArrayOf(4474000.000000001, 4474010.5),
        zs,
        color,
    )

    @Test
    fun `S040 RF-19 las coordenadas vuelven bit a bit sin redondear`() {
        val original = line()

        val back = CadChunkCodec.decode(CadChunkCodec.encode(listOf(original))).single()

        back.easting.map { it.toRawBits() } shouldBe original.easting.map { it.toRawBits() }
        back.northing.map { it.toRawBits() } shouldBe original.northing.map { it.toRawBits() }
        back shouldBe original
    }

    @Test
    fun `S040 RF-14 cada tipo de elemento vuelve con su tipo`() {
        val entities = CadEntityKind.entries.map {
            CadEntity(it, doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(4.0, 5.0, 6.0), null, null)
        }

        CadChunkCodec.decode(CadChunkCodec.encode(entities)).map { it.kind } shouldBe CadEntityKind.entries
    }

    @Test
    fun `S040 RF-23 la altura se guarda solo en los elementos que la traen`() {
        val withZ = line(zs = doubleArrayOf(646.377, 0.0))
        val withoutZ = line()

        val back = CadChunkCodec.decode(CadChunkCodec.encode(listOf(withZ, withoutZ, withZ)))

        back[0].hasZ shouldBe true
        back[0].height!!.toList() shouldBe listOf(646.377, 0.0)
        back[1].hasZ shouldBe false
        back[1].height shouldBe null
        back[2] shouldBe withZ
    }

    @Test
    fun `S040 RF-22 una altura de 0 se conserva como altura y no como falta de ella`() {
        val zero = line(zs = doubleArrayOf(0.0, 0.0))

        CadChunkCodec.decode(CadChunkCodec.encode(listOf(zero))).single().hasZ shouldBe true
    }

    @Test
    fun `S040 RF-24 y RF-25 el color propio y el de contraste vuelven`() {
        val own = line(color = CadColor.rgb(0x00AA55))
        val auto = line(color = CadColor.AUTO_CONTRAST)
        val byLayer = line(color = null)

        val back = CadChunkCodec.decode(CadChunkCodec.encode(listOf(own, auto, byLayer)))

        back[0].color shouldBe CadColor.rgb(0x00AA55)
        back[1].color shouldBe CadColor.AUTO_CONTRAST
        back[2].color shouldBe null
    }

    @Test
    fun `S040 RF-14 una lista vacia y un punto solo`() {
        CadChunkCodec.decode(CadChunkCodec.encode(emptyList())) shouldBe emptyList()

        val point = CadEntity(CadEntityKind.POINT, doubleArrayOf(1.5), doubleArrayOf(2.5), doubleArrayOf(3.5), null)
        CadChunkCodec.decode(CadChunkCodec.encode(listOf(point))).single() shouldBe point
    }

    @Test
    fun `S040 RF-14 una polilinea de miles de vertices vuelve entera`() {
        val random = Random(40)
        val n = 5000
        val big = CadEntity(
            CadEntityKind.POLYLINE,
            DoubleArray(n) { 440000 + random.nextDouble() * 1000 },
            DoubleArray(n) { 4474000 + random.nextDouble() * 1000 },
            DoubleArray(n) { random.nextDouble() * 100 },
            null,
        )

        val back = CadChunkCodec.decode(CadChunkCodec.encode(listOf(big))).single()

        back.vertexCount shouldBe n
        back shouldBe big
    }

    @Test
    fun `S040 RNF el tamano calculado es el del bloque y un trozo cabe en 256 kB`() {
        val entities = List(100) { line(zs = if (it % 2 == 0) doubleArrayOf(1.0, 2.0) else null) }

        val bytes = CadChunkCodec.encode(entities)

        CadChunkCodec.encodedSize(entities) shouldBe bytes.size
        (bytes.size < CadChunkCodec.MAX_CHUNK_BYTES) shouldBe true
        CadChunkCodec.MAX_CHUNK_BYTES shouldBe 256 * 1024
    }

    @Test
    fun `S040 RF-29 el recuadro de un elemento sale de sus vertices`() {
        val box = line().bounds

        box.minEasting shouldBe 440000.123456789
        box.maxEasting shouldBe 440010.987654321
        box.minNorthing shouldBe 4474000.000000001
        box.maxNorthing shouldBe 4474010.5
    }

    @Test
    fun `S040 RF-13 un bloque cortado o corrupto da un error y no un elemento a medias`() {
        val bytes = CadChunkCodec.encode(listOf(line(zs = doubleArrayOf(1.0, 2.0))))

        shouldThrow<IllegalArgumentException> { CadChunkCodec.decode(bytes.copyOf(bytes.size - 3)) }
        shouldThrow<IllegalArgumentException> { CadChunkCodec.decode(byteArrayOf(5, 99, 0)) }
    }

    private fun marked(original: BooleanArray?) = CadEntity(
        CadEntityKind.ARC,
        doubleArrayOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0),
        doubleArrayOf(0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9),
        null,
        null,
        original,
    )

    @Test
    fun `S040 RF-34 la marca de vertice original de cada vertice vuelve igual`() {
        val marks = booleanArrayOf(true, false, false, false, false, false, false, false, false, true)

        val back = CadChunkCodec.decode(CadChunkCodec.encode(listOf(marked(marks)))).single()

        List(10) { back.isOriginal(it) } shouldBe marks.toList()
        back.hasOriginalMarks shouldBe true
    }

    @Test
    fun `S040 RF-34 un trozo de antes de las marcas se lee con todos los vertices originales`() {
        // count=1, ARC, sin marcas, 2 vertices: el formato anterior a S041
        val old = byteArrayOf(1, CadEntityKind.ARC.ordinal.toByte(), 0, 2) +
            java.nio.ByteBuffer.allocate(32).putDouble(1.0).putDouble(2.0).putDouble(3.0).putDouble(4.0).array()

        val back = CadChunkCodec.decode(old).single()

        back.hasOriginalMarks shouldBe false
        List(2) { back.isOriginal(it) } shouldBe listOf(true, true)
        back.easting.toList() shouldBe listOf(1.0, 2.0)
    }

    @Test
    fun `S040 RF-34 sin marcas o con todo original el bloque es el del formato anterior`() {
        val plain = marked(null)
        val allOriginal = marked(BooleanArray(10) { true })

        CadChunkCodec.encode(listOf(allOriginal)).toList() shouldBe CadChunkCodec.encode(listOf(plain)).toList()
        CadChunkCodec.encodedSize(listOf(allOriginal)) shouldBe CadChunkCodec.encode(listOf(allOriginal)).size
    }

    @Test
    fun `S040 RF-34 el tamano con marcas es el del bloque y suma un bit por vertice`() {
        val withMarks = listOf(marked(BooleanArray(10) { it == 0 }), marked(null))

        CadChunkCodec.encodedSize(withMarks) shouldBe CadChunkCodec.encode(withMarks).size
        CadChunkCodec.entitySize(withMarks[0]) shouldBe CadChunkCodec.entitySize(withMarks[1]) + 2
    }

    @Test
    fun `S040 RF-34 las marcas no cambian la coordenadas ni la igualdad por tipo y vertices`() {
        val marks = BooleanArray(10) { it % 3 == 0 }
        val back = CadChunkCodec.decode(CadChunkCodec.encode(listOf(marked(marks)))).single()

        back shouldBe marked(marks)
        (back == marked(null)) shouldBe false
    }

    @Test
    fun `S040 RF-34 un bloque con marcas cortado da un error`() {
        val bytes = CadChunkCodec.encode(listOf(marked(BooleanArray(10) { it == 0 })))

        shouldThrow<IllegalArgumentException> { CadChunkCodec.decode(bytes.copyOf(bytes.size - 1)) }
    }
}
