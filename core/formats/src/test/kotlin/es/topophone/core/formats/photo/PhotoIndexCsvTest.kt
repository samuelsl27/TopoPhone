package es.topophone.core.formats.photo

import es.topophone.core.model.point.PhotoOrigin
import io.kotest.matchers.shouldBe
import java.io.StringWriter
import org.junit.jupiter.api.Test

class PhotoIndexCsvTest {

    private fun text(rows: List<PhotoIndexRow>) = StringWriter().also { PhotoIndexCsv.write(rows, it) }.toString()

    private fun row(
        photo: String = "P1_1.jpg",
        point: String = "P1",
        number: Int = 1,
        takenAtMs: Long = 1_794_471_330_123,
        origin: PhotoOrigin = PhotoOrigin.CAMERA,
        size: Long = 1_800,
    ) = PhotoIndexRow(photo, point, number, takenAtMs, origin, size)

    /** Las filas del ejemplo de `tools`, con los nombres que da [PhotoArchiveNames] y no los del índice de referencia. */
    private fun exampleRows(): List<PhotoIndexRow> {
        val points = PhotoZipFixtures.points()
        val names = PhotoArchiveNames.of(points.map { PhotoNameRef(it.name, it.photos.size) })
        return points.zip(names).flatMap { (point, given) ->
            point.photos.mapIndexed { i, photo ->
                PhotoIndexRow(
                    given[i],
                    point.name,
                    i + 1,
                    photo.takenAtMs,
                    if (photo.origin == "camera") PhotoOrigin.CAMERA else PhotoOrigin.GALLERY,
                    photo.size.toLong(),
                )
            }
        }
    }

    @Test
    fun `S056 RF-6 el indice del ejemplo es el de tools byte a byte`() {
        text(exampleRows()).toByteArray(Charsets.UTF_8) shouldBe PhotoZipFixtures.index()
    }

    @Test
    fun `S056 RF-6 la primera fila es la cabecera y despues una fila por foto`() {
        val lines = text(listOf(row(), row(photo = "P1_2.jpg", number = 2))).split("\r\n")

        lines shouldBe listOf(
            "Photo,Point,Number,Date,Source,Size",
            "P1_1.jpg,P1,1,2026-11-12T08:15:30Z,camera,1800",
            "P1_2.jpg,P1,2,2026-11-12T08:15:30Z,camera,1800",
            "",
        )
    }

    @Test
    fun `S056 RF-6 sin ninguna foto el indice es solo la cabecera`() {
        text(emptyList()) shouldBe "Photo,Point,Number,Date,Source,Size\r\n"
    }

    @Test
    fun `S056 RF-6 el origen es camera o gallery`() {
        val both = listOf(row(origin = PhotoOrigin.CAMERA), row(origin = PhotoOrigin.GALLERY))
        val rows = text(both).lines().drop(1).filter { it.isNotEmpty() }

        rows.map { it.split(",")[4] } shouldBe listOf("camera", "gallery")
    }

    @Test
    fun `S056 RF-6 el tamano es el de los bytes que se escribieron`() {
        text(listOf(row(size = 4_194_304))).split("\r\n")[1].split(",")[5] shouldBe "4194304"
    }

    @Test
    fun `S056 RF-7 un nombre con coma o comillas va entre comillas dobles con las comillas duplicadas`() {
        val line = text(listOf(row(point = "Esquina \"norte\", lado 2"))).split("\r\n")[1]

        line shouldBe "P1_1.jpg,\"Esquina \"\"norte\"\", lado 2\",1,2026-11-12T08:15:30Z,camera,1800"
    }

    @Test
    fun `S056 RF-7 un nombre con un salto de linea va entre comillas`() {
        text(listOf(row(point = "A\nB"))).contains("P1_1.jpg,\"A\nB\",1,") shouldBe true
    }

    @Test
    fun `S056 RF-7 los nombres sin coma, comillas ni saltos no llevan comillas`() {
        text(listOf(row(point = "Árbol ñ/9 (3)"))).contains("\"") shouldBe false
    }

    @Test
    fun `S056 RF-7 las lineas acaban en CRLF y no hay BOM`() {
        val raw = text(listOf(row(), row())).toByteArray(Charsets.UTF_8)

        raw.take(5) shouldBe "Photo".toByteArray().toList() // sin EF BB BF delante
        val whole = String(raw, Charsets.UTF_8)
        whole.count { it == '\n' } shouldBe whole.split("\r\n").size - 1 // todo salto de línea es un CRLF
    }

    @Test
    fun `S056 RF-7 la fecha es ISO 8601 en UTC con la Z y al segundo`() {
        text(listOf(row(takenAtMs = 0))).split("\r\n")[1].split(",")[3] shouldBe "1970-01-01T00:00:00Z"
        text(listOf(row(takenAtMs = 1_794_471_330_999))).split("\r\n")[1].split(",")[3] shouldBe "2026-11-12T08:15:30Z"
    }

    @Test
    fun `S056 RF-7 los numeros no dependen de la configuracion regional`() {
        val before = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
        try {
            text(listOf(row(number = 10, size = 1_234_567))).split("\r\n")[1].split(",").takeLast(5).let {
                listOf(it[1], it[4]) shouldBe listOf("10", "1234567")
            }
        } finally {
            java.util.Locale.setDefault(before)
        }
    }
}
