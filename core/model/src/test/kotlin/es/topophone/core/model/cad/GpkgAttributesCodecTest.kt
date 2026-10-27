package es.topophone.core.model.cad

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El bloque de atributos de un trozo de un GeoPackage importado (S048/RF-20). */
class GpkgAttributesCodecTest {

    private val columns = listOf("nombre", "cota", "codigo", "fecha", "activo", "foto")

    private val first = listOf(
        GpkgValue.Text("Esquina norte"),
        GpkgValue.Real(646.377),
        GpkgValue.Integer(1),
        GpkgValue.Date("2026-10-01"),
        GpkgValue.Bool(true),
        GpkgValue.Null,
    )

    private val second = listOf(
        GpkgValue.Text("Árbol ñandú — 東京"),
        GpkgValue.Real(Double.MIN_VALUE),
        GpkgValue.Integer(Long.MIN_VALUE),
        GpkgValue.Null,
        GpkgValue.Bool(false),
        GpkgValue.Omitted(70_000),
    )

    private fun block(vararg rows: List<GpkgValue>): ByteArray =
        GpkgAttributesCodec.encodeBlock(columns, rows.map { GpkgAttributesCodec.encodeRow(it) })

    @Test
    fun `S048 RF-20 cada valor de cada tipo vuelve igual`() {
        val back = GpkgAttributesCodec.decode(block(first, second))

        back.columns shouldBe columns
        back.rowCount shouldBe 2
        back.row(0).values.toList() shouldBe first
        back.row(1).values.toList() shouldBe second
    }

    @Test
    fun `S048 RF-20 la fila es un mapa por nombre de columna en su orden`() {
        val row = GpkgAttributesCodec.decode(block(first)).row(0)

        row["nombre"] shouldBe GpkgValue.Text("Esquina norte")
        row["activo"] shouldBe GpkgValue.Bool(true)
        row.keys.toList() shouldBe columns
    }

    @Test
    fun `S048 RF-20 los reales y los enteros vuelven bit a bit`() {
        val values = listOf(GpkgValue.Real(440290.434567891), GpkgValue.Real(-0.0), GpkgValue.Integer(Long.MAX_VALUE))
        val bytes = GpkgAttributesCodec.encodeBlock(listOf("a", "b", "c"), listOf(GpkgAttributesCodec.encodeRow(values)))

        val back = GpkgAttributesCodec.decode(bytes).row(0).values.toList()

        (back[0] as GpkgValue.Real).value.toRawBits() shouldBe 440290.434567891.toRawBits()
        (back[1] as GpkgValue.Real).value.toRawBits() shouldBe (-0.0).toRawBits()
        back[2] shouldBe GpkgValue.Integer(Long.MAX_VALUE)
    }

    @Test
    fun `S048 RF-20 un blob pequeno se conserva y uno omitido solo guarda su tamano`() {
        val bytes = GpkgAttributesCodec.encodeBlock(
            listOf("foto", "grande"),
            listOf(GpkgAttributesCodec.encodeRow(listOf(GpkgValue.Blob(byteArrayOf(1, 2, 3)), GpkgValue.Omitted(1_000_000)))),
        )

        val row = GpkgAttributesCodec.decode(bytes).row(0)

        row["foto"] shouldBe GpkgValue.Blob(byteArrayOf(1, 2, 3))
        row["grande"] shouldBe GpkgValue.Omitted(1_000_000)
        (bytes.size < 40) shouldBe true
    }

    @Test
    fun `S048 RF-20 un bloque sin elementos o sin columnas es valido`() {
        GpkgAttributesCodec.decode(GpkgAttributesCodec.encodeBlock(columns, emptyList())).rowCount shouldBe 0
        val noColumns = GpkgAttributesCodec.decode(GpkgAttributesCodec.encodeBlock(emptyList(), listOf(ByteArray(0), ByteArray(0))))
        noColumns.rowCount shouldBe 2
        noColumns.row(1) shouldBe emptyMap()
    }

    @Test
    fun `S048 RF-20 el tamano calculado es el del bloque`() {
        val rows = listOf(first, second).map { GpkgAttributesCodec.encodeRow(it) }

        GpkgAttributesCodec.blockSize(columns, rows) shouldBe GpkgAttributesCodec.encodeBlock(columns, rows).size
        GpkgAttributesCodec.blockSize(emptyList(), emptyList()) shouldBe GpkgAttributesCodec.encodeBlock(emptyList(), emptyList()).size
    }

    @Test
    fun `S048 RF-20 un bloque cortado, con sobrantes o con un tipo desconocido da un error`() {
        val bytes = block(first, second)

        shouldThrow<IllegalArgumentException> { GpkgAttributesCodec.decode(bytes.copyOf(bytes.size - 3)) }
        shouldThrow<IllegalArgumentException> { GpkgAttributesCodec.decode(bytes + byteArrayOf(0)) }
        shouldThrow<IllegalArgumentException> { GpkgAttributesCodec.decode(byteArrayOf(1, 1, 'a'.code.toByte(), 1, 99)) }
        shouldThrow<IllegalArgumentException> { GpkgAttributesCodec.decode(byteArrayOf(0x7F, 0x7F)) }
    }
}
