package es.topophone.core.formats.shp

import es.topophone.core.model.cad.GpkgColumn
import es.topophone.core.model.cad.GpkgValue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Test

/** Los campos y los registros de un `.dbf`, con su tipo, sus borrados y los registros que faltan (S030/RF-18, RF-20, RF-21). */
class DbfReaderTest {

    private val layers = ShpFixtures.expected("esperado-capas.csv")
    private val attributes = ShpFixtures.expected("esperado-atributos.csv")

    private fun encodingOf(label: String) = if (label == "UTF-8") ShpEncoding.UTF_8 else ShpEncoding.WINDOWS_1252

    private fun dbf(path: String, encoding: ShpEncoding): DbfReader =
        DbfReader.open(ShpByteSource.of(ShpFixtures.bytes(path)), encoding).shouldBeInstanceOf<DbfReader>()

    /** El valor como lo escribe lo esperado: números sin ceros de más, fechas ISO y lógicos en inglés. */
    private fun GpkgValue.text(): String = when (this) {
        GpkgValue.Null -> ""
        is GpkgValue.Integer -> value.toString()
        is GpkgValue.Real -> value.toString()
        is GpkgValue.Text -> value
        is GpkgValue.Date -> value
        is GpkgValue.Bool -> value.toString()
        else -> error("No sale de un .dbf: $this")
    }

    private fun same(expected: String, value: GpkgValue): Boolean =
        if (value is GpkgValue.Real) value.value == expected.toDouble() else value.text() == expected

    @Test
    fun `S030 RF-21 las columnas de cada capa son las esperadas con su tipo`() {
        var checked = 0
        for (row in layers) {
            val path = "${row.getValue("escenario")}/${row.getValue("capa")}"
            if (row.getValue("columnas").isEmpty()) continue
            val reader = dbf("$path.dbf", encodingOf(row.getValue("codificacion")))

            reader.columns.joinToString(";") { "${it.name}:${it.type}" } shouldBe row.getValue("columnas")
            checked++
        }
        (checked > 40) shouldBe true
    }

    @Test
    fun `S030 RF-21 los valores de cada registro son los esperados con el tipo del dbf`() {
        val byKey = attributes.groupBy { Triple(it.getValue("escenario"), it.getValue("capa"), it.getValue("registro").toInt()) }
        var checked = 0
        for (row in layers) {
            if (row.getValue("columnas").isEmpty()) continue
            val key = "${row.getValue("escenario")}/${row.getValue("capa")}"
            val reader = dbf("$key.dbf", encodingOf(row.getValue("codificacion")))
            for (index in 1..reader.recordCount) {
                val record = reader.record(index)!!
                val expected = byKey[Triple(row.getValue("escenario"), row.getValue("capa"), index)]
                if (record.deleted || expected == null) continue

                expected.map { it.getValue("columna") } shouldBe reader.columns.map { it.name }
                for ((value, want) in record.values.zip(expected)) {
                    same(want.getValue("valor"), value) shouldBe true
                    checked++
                }
            }
        }
        (checked > 150) shouldBe true
    }

    @Test
    fun `S030 RF-21 los tipos de campo dan texto numero fecha y logico y un campo vacio es nulo`() {
        val reader = dbf("puntos-25830/vertices.dbf", ShpEncoding.UTF_8)

        reader.columns shouldBe listOf(
            GpkgColumn("nombre", "TEXT"),
            GpkgColumn("cota", "REAL"),
            GpkgColumn("codigo", "INTEGER"),
            GpkgColumn("fecha", "DATE"),
            GpkgColumn("activo", "BOOLEAN"),
        )
        reader.record(1)!!.values shouldBe listOf(
            GpkgValue.Text("Esquina norte"),
            GpkgValue.Real(646.377),
            GpkgValue.Integer(1),
            GpkgValue.Date("2026-10-01"),
            GpkgValue.Bool(true),
        )
        reader.record(2)!!.values[4] shouldBe GpkgValue.Bool(false)
        reader.record(2)!!.values[0] shouldBe GpkgValue.Text("Árbol ñandú")
        reader.record(3)!!.values.take(2) shouldBe listOf(GpkgValue.Null, GpkgValue.Real(648.0)) // texto en blanco: nulo
        reader.record(3)!!.values.drop(3) shouldBe listOf(GpkgValue.Null, GpkgValue.Null) // fecha y lógico sin valor
        reader.record(4)!!.values[1] shouldBe GpkgValue.Null // número en blanco
    }

    @Test
    fun `S030 RF-16 un registro marcado como borrado se distingue`() {
        val reader = dbf("borrados-y-nulos/borrados.dbf", ShpEncoding.UTF_8)

        (1..reader.recordCount).map { reader.record(it)!!.deleted } shouldBe listOf(false, true, false, true, false)
    }

    @Test
    fun `S030 RF-20 un dbf con menos registros que el shp da los que tiene y uno con mas los suyos`() {
        for ((name, count) in listOf("dbf_corto" to 3, "dbf_largo" to 7)) {
            val reader = dbf("incompletos/$name.dbf", ShpEncoding.UTF_8)

            reader.recordCount shouldBe count
            reader.record(count)!!.index shouldBe count
            reader.record(count + 1) shouldBe null
        }
        dbf("puntos-25830/vertices.dbf", ShpEncoding.UTF_8).record(0) shouldBe null
        dbf("puntos-25830/vertices.dbf", ShpEncoding.UTF_8).record(-3) shouldBe null
    }

    @Test
    fun `S030 RF-20 un dbf cortado a mitad de un registro no cuenta ese registro`() {
        val bytes = ShpFixtures.bytes("puntos-25830/vertices.dbf")
        val recordLength = 1 + 30 + 12 + 5 + 8 + 1
        val cut = bytes.copyOf(bytes.size - 1 - recordLength - 10) // quita el 1A, el último registro y diez bytes del anterior

        val reader = DbfReader.open(ShpByteSource.of(cut), ShpEncoding.UTF_8)!!

        reader.recordCount shouldBe 3
    }

    @Test
    fun `S030 RF-21 los campos de tipos que no se muestran no son columnas y no descolocan los demas`() {
        val reader = DbfReader.open(ShpByteSource.of(handMade()), ShpEncoding.UTF_8)!!

        reader.columns shouldBe listOf(GpkgColumn("nombre", "TEXT"), GpkgColumn("num", "INTEGER"), GpkgColumn("ok", "BOOLEAN"))
        reader.record(1)!!.values shouldBe listOf(GpkgValue.Text("Uno"), GpkgValue.Integer(42), GpkgValue.Bool(true))
        reader.record(2)!!.values shouldBe listOf(GpkgValue.Text("Dos"), GpkgValue.Null, GpkgValue.Null) // «****» y «?»
    }

    @Test
    fun `S030 RF-18 los nombres y los textos se leen con la codificacion elegida y se puede cambiar`() {
        val utf8 = dbf("codificaciones/w1252_sin_cpg.dbf", ShpEncoding.UTF_8)
        val fixed = utf8.withEncoding(ShpEncoding.WINDOWS_1252)

        (utf8.record(1)!!.values[0] as GpkgValue.Text).value.contains('�') shouldBe true
        fixed.record(1)!!.values[0] shouldBe GpkgValue.Text("Árbol ñandú")
        fixed.encoding shouldBe ShpEncoding.WINDOWS_1252
    }

    @Test
    fun `S030 RF-21 los valores que lee QGIS del dbf son los que lee el lector`() {
        val expected = ShpFixtures.expected("qgis/esperado-qgis-atributos.csv").groupBy {
            Triple(it.getValue("escenario"), it.getValue("capa"), it.getValue("registro").toInt())
        }
        val encodings = ShpFixtures.expected("qgis/esperado-qgis-capas.csv").associate {
            (it.getValue("escenario") to it.getValue("capa")) to
                if (it.getValue("cpg") == "UTF-8") ShpEncoding.UTF_8 else ShpEncoding.WINDOWS_1252
        }
        var checked = 0
        for ((key, rows) in expected) {
            val (scenario, layer, number) = key
            val record = dbf("qgis/$scenario/$layer.dbf", encodings.getValue(scenario to layer)).record(number)!!

            for ((value, row) in record.values.zip(rows)) {
                val want = row.getValue("valor")
                if (value is GpkgValue.Real) value.value shouldBe (want.toDouble() plusOrMinus 1e-9) else value.text() shouldBe want
                checked++
            }
        }
        (checked > 25) shouldBe true
    }

    /** Un `.dbf` de tres campos más uno memo, escrito a mano, con dos registros: uno completo y otro con «****» y «?». */
    private fun handMade(): ByteArray {
        val fields = listOf(
            Triple("nombre", 'C', 5 to 0),
            Triple("notas", 'M', 10 to 0),
            Triple("num", 'N', 4 to 0),
            Triple(
                "ok",
                'L',
                1 to 0,
            ),
        )
        val recordLength = 1 + fields.sumOf { it.third.first }
        val out = ByteArrayOutputStream()
        val header = ByteArray(32)
        header[0] = 3
        header[4] = 2
        header[8] = (33 + 32 * fields.size).toByte()
        header[10] = recordLength.toByte()
        out.write(header)
        for ((name, kind, size) in fields) {
            out.write(name.toByteArray().copyOf(11))
            out.write(kind.code)
            out.write(ByteArray(4))
            out.write(size.first)
            out.write(size.second)
            out.write(ByteArray(14))
        }
        out.write(0x0D)
        out.write(" Uno  0123456789  42T".toByteArray())
        out.write(" Dos  abcdefghij****?".toByteArray())
        out.write(0x1A)
        return out.toByteArray()
    }
}
