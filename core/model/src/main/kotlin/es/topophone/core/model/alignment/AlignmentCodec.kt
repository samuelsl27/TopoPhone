package es.topophone.core.model.alignment

import java.io.ByteArrayOutputStream
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/**
 * Los bloques binarios de un eje (S049, plan: modelo de datos): la planta y la rasante van cada una en un bloque de la fila del eje.
 * Un byte de versión del formato, el número de elementos (entero de longitud variable) y los `Double` de cada uno sin redondear.
 */
object AlignmentCodec {
    private const val FORMAT = 1
    private const val NO_CURVE = 0
    private const val PARABOLA = 1
    private const val CIRCULAR = 2

    fun encodeGeometry(elements: List<AlignmentElement>): ByteArray {
        val buffer = ByteBuffer.allocate(1 + MAX_VARINT + elements.size * ELEMENT_BYTES)
        buffer.put(FORMAT.toByte())
        putVarint(buffer, elements.size)
        for (e in elements) {
            buffer.put(e.kind.ordinal.toByte())
            doubleArrayOf(e.startChainage, e.length, e.startEasting, e.startNorthing, e.startAzimuth, e.startCurvature, e.endCurvature)
                .forEach { buffer.putDouble(it) }
        }
        return buffer.array().copyOf(buffer.position())
    }

    /** La planta de un bloque; [IllegalArgumentException] si está cortado, le sobra algo o no es de este formato. */
    fun decodeGeometry(bytes: ByteArray): List<AlignmentElement> = read(bytes) { buffer ->
        val count = readVarint(buffer)
        require(count >= 0 && count.toLong() * ELEMENT_BYTES <= buffer.remaining()) { "Tramos imposibles" }
        List(count) {
            val kind = ElementKind.entries.getOrNull(buffer.get().toInt()) ?: throw IllegalArgumentException("Clase de tramo desconocida")
            val v = DoubleArray(DOUBLES_PER_ELEMENT) { buffer.getDouble() }
            AlignmentElement(kind, v[0], v[1], v[2], v[3], v[4], v[5], v[6])
        }
    }

    fun encodeProfile(profile: VerticalProfile): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(FORMAT)
        val head = ByteBuffer.allocate(MAX_VARINT)
        putVarint(head, profile.vertices.size)
        out.write(head.array(), 0, head.position())
        for (v in profile.vertices) {
            val buffer = ByteBuffer.allocate(VERTEX_BYTES)
            buffer.putDouble(v.chainage).putDouble(v.elevation)
            when (val curve = v.curve) {
                VerticalCurve.None -> buffer.put(NO_CURVE.toByte())
                is VerticalCurve.Parabola -> buffer.put(PARABOLA.toByte()).putDouble(curve.length)
                is VerticalCurve.Circular -> buffer.put(CIRCULAR.toByte()).putDouble(curve.radius).putDouble(curve.length)
            }
            out.write(buffer.array(), 0, buffer.position())
        }
        return out.toByteArray()
    }

    fun decodeProfile(bytes: ByteArray): VerticalProfile = read(bytes) { buffer ->
        val count = readVarint(buffer)
        require(count >= 0 && count.toLong() * MIN_VERTEX_BYTES <= buffer.remaining()) { "Vértices imposibles" }
        VerticalProfile(
            List(count) {
                val chainage = buffer.getDouble()
                val elevation = buffer.getDouble()
                val curve = when (buffer.get().toInt()) {
                    NO_CURVE -> VerticalCurve.None
                    PARABOLA -> VerticalCurve.Parabola(buffer.getDouble())
                    CIRCULAR -> VerticalCurve.Circular(buffer.getDouble(), buffer.getDouble())
                    else -> throw IllegalArgumentException("Acuerdo vertical desconocido")
                }
                VerticalVertex(chainage, elevation, curve)
            },
        )
    }

    private inline fun <T> read(bytes: ByteArray, parse: (ByteBuffer) -> T): T {
        val buffer = ByteBuffer.wrap(bytes)
        try {
            require(buffer.get().toInt() == FORMAT) { "Formato de eje desconocido" }
            val result = parse(buffer)
            require(!buffer.hasRemaining()) { "Sobran bytes al final del bloque" }
            return result
        } catch (e: BufferUnderflowException) {
            throw IllegalArgumentException("El bloque está cortado", e)
        }
    }

    private const val DOUBLES_PER_ELEMENT = 7
    private const val ELEMENT_BYTES = 1 + DOUBLES_PER_ELEMENT * Double.SIZE_BYTES
    private const val MAX_VARINT = 5
    private const val MIN_VERTEX_BYTES = 2 * Double.SIZE_BYTES + 1
    private const val VERTEX_BYTES = 3 * Double.SIZE_BYTES + 1 + Double.SIZE_BYTES

    private fun putVarint(buffer: ByteBuffer, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            buffer.put(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
        buffer.put(v.toByte())
    }

    private fun readVarint(buffer: ByteBuffer): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = buffer.get().toInt()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            require(shift < 35) { "Entero demasiado largo" }
        }
    }
}
