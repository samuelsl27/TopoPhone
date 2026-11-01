package es.topophone.core.model.alignment.section

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Los bloques binarios de las secciones (S050, plan: modelo de datos): los puntos de una sección y la sección tipo van cada uno en un
 * bloque de su fila. Un byte de clase de bloque, uno de versión, los números de elementos y los `Double` sin redondear (RF-8).
 */
object SectionCodec {
    private const val FORMAT = 1
    private const val POINTS = 1
    private const val TEMPLATE = 2
    private const val MIN_POINT_BYTES = 2 + 2 * Double.SIZE_BYTES
    private const val MIN_SEGMENT_BYTES = 2 + 2 * Double.SIZE_BYTES + 1

    fun encodePoints(points: List<SectionPoint>): ByteArray = write(POINTS) { out ->
        out.writeInt(points.size)
        points.forEach { p ->
            out.writeUTF(p.code)
            out.writeDouble(p.offsetM)
            out.writeDouble(p.heightM)
        }
    }

    fun decodePoints(bytes: ByteArray): List<SectionPoint> = read(bytes, POINTS) { input ->
        val count = input.readInt()
        require(count >= 0 && count.toLong() * MIN_POINT_BYTES <= input.available()) { "Puntos imposibles" }
        List(count) { SectionPoint(input.readUTF(), input.readDouble(), input.readDouble()) }
    }

    fun encodeTemplate(template: SectionTemplate): ByteArray = write(TEMPLATE) { out ->
        out.writeDouble(template.fromChainage)
        out.writeDouble(template.toChainage)
        out.writeUTF(template.gradeCode)
        for (side in listOf(template.left, template.right)) {
            out.writeInt(side.size)
            side.forEach { s ->
                out.writeUTF(s.code)
                out.writeDouble(s.widthM)
                out.writeDouble(s.slope)
                out.writeBoolean(s.followsSuperelevation)
            }
        }
    }

    fun decodeTemplate(bytes: ByteArray): SectionTemplate = read(bytes, TEMPLATE) { input ->
        val from = input.readDouble()
        val to = input.readDouble()
        val grade = input.readUTF()
        val sides = List(2) {
            val count = input.readInt()
            require(count >= 0 && count.toLong() * MIN_SEGMENT_BYTES <= input.available()) { "Tramos imposibles" }
            List(count) { TemplateSegment(input.readUTF(), input.readDouble(), input.readDouble(), input.readBoolean()) }
        }
        SectionTemplate(from, to, grade, sides[0], sides[1])
    }

    private fun write(kind: Int, body: (DataOutputStream) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(kind)
            out.writeByte(FORMAT)
            body(out)
        }
        return bytes.toByteArray()
    }

    private fun <T> read(bytes: ByteArray, kind: Int, parse: (DataInputStream) -> T): T {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        try {
            require(input.readUnsignedByte() == kind) { "Clase de bloque de sección desconocida" }
            require(input.readUnsignedByte() == FORMAT) { "Formato de sección desconocido" }
            val result = parse(input)
            require(input.available() == 0) { "Sobran bytes al final del bloque" }
            return result
        } catch (e: IOException) {
            throw IllegalArgumentException("El bloque está cortado", e)
        }
    }
}
