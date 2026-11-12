package es.topophone.core.formats.shp.export

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.Locale
import org.junit.jupiter.api.Test

/** El `.dbf` que escribe la exportación: tipos, vacíos, recorte a 254 bytes, números sin configuración regional y cabecera (S055/RF-6, RF-7, RF-10, RF-11). */
class DbfWriterTest {
    private val date = LocalDate.of(2026, 11, 12)

    private fun write(columns: List<DbfColumn>, rows: List<Array<Any?>>): Pair<ByteArray, DbfWriteResult> {
        val out = ByteArrayOutputStream()
        val result = DbfWriter.write(columns, rows, out, date)
        return out.toByteArray() to result
    }

    private fun text(name: String = "T") = DbfColumn(name, DbfType.TEXT)
    private fun integer(name: String = "I") = DbfColumn(name, DbfType.INTEGER)
    private fun decimal(name: String = "D") = DbfColumn(name, DbfType.DECIMAL)

    private fun u16(bytes: ByteArray, at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, at: Int) = u16(bytes, at) or (u16(bytes, at + 2) shl 16)

    /** El campo [index] del registro [record] (desde 0) de un `.dbf` de una sola columna de ancho [width]. */
    private fun field(bytes: ByteArray, headerLength: Int, recordLength: Int, record: Int, offset: Int, width: Int): String =
        String(bytes, headerLength + record * recordLength + offset, width, Charsets.UTF_8)

    @Test
    fun `S055 RF-6 los dbf de puntos lineas y poligonos coinciden byte a byte con la referencia de tools`() {
        for (layer in ShpExportFixtures.layers) {
            val entry = ShpExportFixtures.entry(layer)

            val (bytes, _) = write(entry.columns, entry.rows)

            bytes.toList() shouldBe ShpExportFixtures.bytes("ejemplo-$layer.dbf").toList()
        }
    }

    @Test
    fun `S055 RF-10 solo el texto de mas de 254 bytes del ejemplo se cuenta como recortado`() {
        val counts = ShpExportFixtures.layers.associateWith { layer ->
            val entry = ShpExportFixtures.entry(layer)
            write(entry.columns, entry.rows).second.truncatedTexts
        }

        counts shouldBe mapOf("puntos" to 1, "lineas" to 0, "poligonos" to 0)
    }

    @Test
    fun `S055 RF-11 el cpg declara UTF-8 como el de la referencia`() {
        DbfWriter.CPG.toList() shouldBe ShpExportFixtures.bytes("ejemplo-puntos.cpg").toList()
        String(DbfWriter.CPG, Charsets.US_ASCII) shouldBe "UTF-8"
    }

    @Test
    fun `S055 RF-6 la cabecera lleva la version, la fecha, los registros, el largo de la cabecera y del registro y los terminadores`() {
        val rows = listOf(arrayOf<Any?>("xy", 5L, 1.5), arrayOf<Any?>("z", 123L, null))
        val (bytes, result) = write(listOf(text("A"), integer("B"), decimal("C")), rows)

        bytes[0].toInt() shouldBe 0x03
        listOf(bytes[1].toInt(), bytes[2].toInt(), bytes[3].toInt()) shouldBe listOf(126, 11, 12)
        u32(bytes, 4) shouldBe 2
        u16(bytes, 8) shouldBe 32 + 3 * 32 + 1
        u16(bytes, 10) shouldBe 1 + 2 + 3 + 20
        bytes[29].toInt() shouldBe 0 // motor de idioma: el .cpg declara la codificación
        bytes[32 + 3 * 32].toInt() shouldBe 0x0D
        bytes.last().toInt() shouldBe 0x1A
        bytes.size shouldBe 32 + 3 * 32 + 1 + 2 * (1 + 2 + 3 + 20) + 1
        result shouldBe DbfWriteResult(2, 0)
    }

    @Test
    fun `S055 RF-6 cada columna lleva su nombre, su tipo, su ancho y sus decimales`() {
        val (bytes, _) = write(listOf(text("NOMBRE"), integer("SATS"), decimal("COTA")), listOf(arrayOf<Any?>("P1", 24L, 1.0)))

        fun descriptor(i: Int) = 32 + 32 * i
        String(bytes, descriptor(0), 6, Charsets.US_ASCII) shouldBe "NOMBRE"
        bytes[descriptor(0) + 6].toInt() shouldBe 0 // el nombre se rellena con ceros hasta 11 bytes
        listOf('C', 'N', 'N').map { it.code } shouldBe (0..2).map { bytes[descriptor(it) + 11].toInt() }
        (0..2).map { bytes[descriptor(it) + 16].toInt() } shouldBe listOf(2, 2, 20)
        (0..2).map { bytes[descriptor(it) + 17].toInt() } shouldBe listOf(0, 0, 9)
    }

    @Test
    fun `S055 RF-6 el texto va a la izquierda con espacios, y el entero y el decimal a la derecha`() {
        val rows = listOf(arrayOf<Any?>("ab", 7L, 1.5), arrayOf<Any?>("abcd", -12345L, -0.25))
        val (bytes, _) = write(listOf(text("T"), integer("I"), decimal("D")), rows)
        val header = 32 + 3 * 32 + 1
        val record = 1 + 4 + 6 + 20

        field(bytes, header, record, 0, 1, 4) shouldBe "ab  "
        field(bytes, header, record, 0, 5, 6) shouldBe "     7"
        field(bytes, header, record, 0, 11, 20) shouldBe "1.500000000".padStart(20)
        field(bytes, header, record, 1, 1, 4) shouldBe "abcd"
        field(bytes, header, record, 1, 5, 6) shouldBe "-12345"
        field(bytes, header, record, 1, 11, 20) shouldBe "-0.250000000".padStart(20)
    }

    @Test
    fun `S055 RF-6 un valor vacio es el campo entero de espacios en cualquier tipo`() {
        val (bytes, _) = write(listOf(text(), integer(), decimal()), listOf(arrayOf<Any?>("a", 1L, 2.0), arrayOf<Any?>(null, null, null)))
        val header = 32 + 3 * 32 + 1
        val record = 1 + 1 + 1 + 20

        field(bytes, header, record, 1, 1, record - 1) shouldBe " ".repeat(record - 1)
    }

    @Test
    fun `S055 RF-6 una columna de texto sin valores tiene ancho 1`() {
        val (bytes, _) = write(listOf(text()), listOf(arrayOf<Any?>(null)))

        u16(bytes, 10) shouldBe 2
    }

    @Test
    fun `S055 RF-6 el ancho del texto es el del mayor valor en bytes UTF-8 y no en caracteres`() {
        val (bytes, _) = write(listOf(text()), listOf(arrayOf<Any?>("Árbol ñandú €")))

        // 13 caracteres, 18 bytes: Á, ñ y ú ocupan 2 bytes y € ocupa 3
        u16(bytes, 10) shouldBe 1 + 18
        String(bytes, 32 + 32 + 1 + 1, u16(bytes, 10) - 1, Charsets.UTF_8) shouldBe "Árbol ñandú €"
    }

    @Test
    fun `S055 RF-10 un texto de 255 bytes se recorta a 254 y se cuenta`() {
        val (bytes, result) = write(listOf(text()), listOf(arrayOf<Any?>("a".repeat(255))))

        u16(bytes, 10) shouldBe 255
        result.truncatedTexts shouldBe 1
    }

    @Test
    fun `S055 RF-10 un texto de exactamente 254 bytes no se recorta`() {
        val (bytes, result) = write(listOf(text()), listOf(arrayOf<Any?>("a".repeat(254))))

        u16(bytes, 10) shouldBe 255
        result.truncatedTexts shouldBe 0
    }

    @Test
    fun `S055 RF-10 el recorte no parte un caracter de dos, tres ni cuatro bytes`() {
        for ((tail, kept) in listOf("é" to 253, "€" to 252, "😀" to 252)) {
            val text = "a".repeat(kept) + tail
            val (bytes, result) = write(listOf(text()), listOf(arrayOf<Any?>(text)))

            result.truncatedTexts shouldBe 1
            val width = u16(bytes, 10) - 1
            width shouldBe kept
            String(bytes, 32 + 32 + 1 + 1, width, Charsets.UTF_8) shouldBe "a".repeat(kept)
        }
    }

    @Test
    fun `S055 RF-10 los caracteres que si caben antes del limite se conservan enteros`() {
        val text = "é".repeat(127) + "é" // 256 bytes: caben 127 caracteres (254 bytes)
        val (bytes, result) = write(listOf(text()), listOf(arrayOf<Any?>(text)))

        result.truncatedTexts shouldBe 1
        u16(bytes, 10) - 1 shouldBe 254
        String(bytes, 32 + 32 + 1 + 1, 254, Charsets.UTF_8) shouldBe "é".repeat(127)
    }

    @Test
    fun `S055 RF-6 los decimales llevan 9 decimales del valor exacto del double`() {
        DbfWriter.decimalText(440000.123456789) shouldBe "440000.123456789"
        DbfWriter.decimalText(0.5) shouldBe "0.500000000"
        DbfWriter.decimalText(-3.7038) shouldBe "-3.703800000"
        DbfWriter.decimalText(0.0) shouldBe "0.000000000"
        // 0.1 + 0.2 = 0.30000000000000004: manda el valor exacto del double, no su forma corta
        DbfWriter.decimalText(0.1 + 0.2) shouldBe "0.300000000"
    }

    @Test
    fun `S055 RF-6 una mitad exacta se redondea al par como la referencia de tools`() {
        DbfWriter.decimalText(Math.scalb(1.0, -10)) shouldBe "0.000976562" // 0.0009765625: el 2 es par
        DbfWriter.decimalText(3 * Math.scalb(1.0, -10)) shouldBe "0.002929688" // 0.0029296875: el 7 es impar, sube
    }

    @Test
    fun `S055 RF-6 un decimal no lleva cero negativo`() {
        DbfWriter.decimalText(-1e-12) shouldBe "0.000000000"
        DbfWriter.decimalText(-0.0) shouldBe "0.000000000"
    }

    @Test
    fun `S055 RF-6 un decimal que no cabe en 20 caracteres pierde decimales hasta caber`() {
        DbfWriter.decimalText(123456789012.5) shouldBe "123456789012.5000000"
        DbfWriter.decimalText(-9999999999.5) shouldBe "-9999999999.50000000"
        DbfWriter.decimalText(1e12)!!.length shouldBe 20
    }

    @Test
    fun `S055 RF-6 un decimal que no es finito o no cabe ni sin decimales se escribe vacio`() {
        DbfWriter.decimalText(Double.NaN) shouldBe null
        DbfWriter.decimalText(Double.POSITIVE_INFINITY) shouldBe null
        DbfWriter.decimalText(1e25) shouldBe null
        val (bytes, _) = write(listOf(decimal()), listOf(arrayOf<Any?>(Double.NaN)))
        field(bytes, 32 + 32 + 1, 21, 0, 1, 20) shouldBe " ".repeat(20)
    }

    @Test
    fun `S055 RNF-T9 los numeros llevan punto decimal aunque el movil este en espanol`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("es-ES"))
            val (bytes, _) = write(listOf(decimal(), integer()), listOf(arrayOf<Any?>(1234.5, 1234567L)))
            val text = String(bytes, Charsets.UTF_8)

            text.contains("1234.500000000") shouldBe true
            text.contains("1234,5") shouldBe false
            text.contains("1.234.567") shouldBe false
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `S055 RF-6 el ancho de un entero es el del mayor valor, con signo`() {
        val (bytes, _) = write(listOf(integer()), listOf(arrayOf<Any?>(5L), arrayOf<Any?>(-123456L), arrayOf<Any?>(Long.MIN_VALUE)))

        u16(bytes, 10) shouldBe 1 + 20
        field(bytes, 32 + 32 + 1, 21, 2, 1, 20) shouldBe Long.MIN_VALUE.toString()
    }

    @Test
    fun `S055 RF-6 un entero de tipo Int tambien se escribe`() {
        val (bytes, _) = write(listOf(integer()), listOf(arrayOf<Any?>(42)))

        field(bytes, 32 + 32 + 1, 3, 0, 1, 2) shouldBe "42"
    }

    @Test
    fun `S055 RF-6 sin filas el dbf lleva solo la cabecera y el final de fichero`() {
        val (bytes, result) = write(listOf(text(), decimal()), emptyList())

        bytes.size shouldBe 32 + 2 * 32 + 1 + 1
        u32(bytes, 4) shouldBe 0
        result shouldBe DbfWriteResult(0, 0)
    }

    @Test
    fun `S055 RF-6 un valor que no es del tipo de su columna es un error`() {
        shouldThrow<IllegalArgumentException> { write(listOf(text()), listOf(arrayOf<Any?>(5L))) }
        shouldThrow<IllegalArgumentException> { write(listOf(integer()), listOf(arrayOf<Any?>("5"))) }
        shouldThrow<IllegalArgumentException> { write(listOf(integer()), listOf(arrayOf<Any?>(5.5))) }
        shouldThrow<IllegalArgumentException> { write(listOf(decimal()), listOf(arrayOf<Any?>("5"))) }
        shouldThrow<IllegalArgumentException> { write(listOf(text(), text("U")), listOf(arrayOf<Any?>("solo uno"))) }
    }

    @Test
    fun `S055 RF-8 el nombre de una columna es de 1 a 10 caracteres ASCII`() {
        DbfColumn("ABCDEFGHIJ", DbfType.TEXT).name.length shouldBe 10
        shouldThrow<IllegalArgumentException> { DbfColumn("ABCDEFGHIJK", DbfType.TEXT) }
        shouldThrow<IllegalArgumentException> { DbfColumn("", DbfType.TEXT) }
        shouldThrow<IllegalArgumentException> { DbfColumn("COTA€", DbfType.TEXT) }
        shouldThrow<IllegalArgumentException> { DbfColumn("A B", DbfType.TEXT) }
    }
}
