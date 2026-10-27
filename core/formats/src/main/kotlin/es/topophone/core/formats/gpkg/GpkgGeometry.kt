package es.topophone.core.formats.gpkg

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** El recuadro de una geometría en el sistema de su capa. */
data class GpkgEnvelope(val minX: Double, val maxX: Double, val minY: Double, val maxY: Double)

/**
 * La cabecera de un blob de geometría de GeoPackage: el SRS, si la marca como vacía, su envolvente XY si la trae y dónde empieza el
 * WKB. Con los primeros 40 bytes del blob ya se sabe todo salvo el WKB (S048/RF-13).
 */
data class GpkgBlobHeader(val srsId: Int, val empty: Boolean, val envelope: GpkgEnvelope?, val wkbOffset: Int)

/** Qué parte de un objeto es un elemento del dibujo (S048/RF-17). */
enum class GpkgPartKind { POINT, LINE }

/**
 * Un elemento que sale de un objeto: un punto, una línea, o el exterior ([ring] 0) o un hueco ([ring] 1…) de un polígono. [z] es null
 * si el objeto no trae altura; M se descarta (S048/RF-19).
 */
class GpkgPart(val kind: GpkgPartKind, val x: DoubleArray, val y: DoubleArray, val z: DoubleArray?, val ring: Int? = null) {
    val isPolygonRing: Boolean get() = ring != null
    val vertexCount: Int get() = x.size
}

/** Lo que hay en el blob de geometría de un objeto (S048/RF-17, RF-18). */
sealed interface GpkgGeometry {
    /** Los elementos de un objeto con geometría, en el orden del fichero. */
    class Parts(val srsId: Int, val parts: List<GpkgPart>) : GpkgGeometry

    /** Geometría vacía o sin vértices (RF-18). */
    data object Empty : GpkgGeometry

    /** Arcos, curvas compuestas y superficies curvas: no se importan (RF-18). */
    data object Curve : GpkgGeometry

    /** Un blob o un WKB que no se puede leer. */
    data object Invalid : GpkgGeometry
}

/**
 * El blob de geometría de GeoPackage: `GP`, versión 0, indicadores (orden de bytes, envolvente, vacía), SRS, envolvente y WKB. El
 * WKB se lee en códigos ISO (`1001`, `2001`, `3001`) y en EWKB (marcas en los bits altos), en las dos endianidades, con Z, M o ZM,
 * y en puntos, líneas, polígonos, sus múltiples y colecciones (S048/RF-17, RF-18, RF-19).
 */
object GpkgGeometryReader {
    private const val MAGIC_0 = 'G'.code.toByte()
    private const val MAGIC_1 = 'P'.code.toByte()
    private const val FIXED_HEADER = 8
    private const val EMPTY_FLAG = 16
    private const val EXTENDED_FLAG = 32
    private const val EWKB_Z = 0x80000000L
    private const val EWKB_M = 0x40000000L
    private const val EWKB_SRID = 0x20000000L
    private const val MAX_DEPTH = 8

    private const val POINT = 1
    private const val LINESTRING = 2
    private const val POLYGON = 3
    private const val MULTIPOINT = 4
    private const val MULTILINESTRING = 5
    private const val MULTIPOLYGON = 6
    private const val COLLECTION = 7
    private val CURVES = 8..14

    /** La cabecera del blob, o null si no es un blob de GeoPackage válido. Admite un blob cortado tras la envolvente XY. */
    fun header(blob: ByteArray): GpkgBlobHeader? {
        if (blob.size < FIXED_HEADER || blob[0] != MAGIC_0 || blob[1] != MAGIC_1 || blob[2].toInt() != 0) return null
        val flags = blob[3].toInt() and 0xFF
        if (flags and EXTENDED_FLAG != 0) return null
        val order = if (flags and 1 != 0) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val indicator = (flags shr 1) and 7
        val envelopeBytes = when (indicator) {
            0 -> 0
            1 -> 32
            2, 3 -> 48
            4 -> 64
            else -> return null
        }
        val buffer = ByteBuffer.wrap(blob).order(order)
        val srs = buffer.getInt(4)
        val envelope = if (indicator != 0 && blob.size >= FIXED_HEADER + 32) {
            val box = GpkgEnvelope(buffer.getDouble(8), buffer.getDouble(16), buffer.getDouble(24), buffer.getDouble(32))
            box.takeIf { listOf(it.minX, it.maxX, it.minY, it.maxY).all(Double::isFinite) && it.minX <= it.maxX && it.minY <= it.maxY }
        } else {
            null
        }
        return GpkgBlobHeader(srs, flags and EMPTY_FLAG != 0, envelope, FIXED_HEADER + envelopeBytes)
    }

    /** Los elementos del objeto, o por qué no se importa. */
    fun read(blob: ByteArray): GpkgGeometry {
        val header = header(blob) ?: return GpkgGeometry.Invalid
        if (header.empty) return GpkgGeometry.Empty
        if (blob.size <= header.wkbOffset) return GpkgGeometry.Invalid
        val parts = ArrayList<GpkgPart>()
        return try {
            val buffer = ByteBuffer.wrap(blob, header.wkbOffset, blob.size - header.wkbOffset)
            if (readGeometry(buffer, parts, depth = 0)) {
                GpkgGeometry.Curve
            } else if (parts.isEmpty()) {
                GpkgGeometry.Empty
            } else {
                GpkgGeometry.Parts(header.srsId, parts)
            }
        } catch (e: InvalidWkb) {
            GpkgGeometry.Invalid
        } catch (e: BufferUnderflowException) {
            GpkgGeometry.Invalid
        }
    }

    private class InvalidWkb(message: String) : Exception(message)

    private class Dims(val hasZ: Boolean, val hasM: Boolean) {
        val count: Int get() = 2 + (if (hasZ) 1 else 0) + (if (hasM) 1 else 0)
    }

    /** Lee una geometría del WKB; devuelve true si es una curva, y entonces no hay que seguir. */
    private fun readGeometry(buffer: ByteBuffer, parts: MutableList<GpkgPart>, depth: Int): Boolean {
        if (depth > MAX_DEPTH) throw InvalidWkb("Colecciones demasiado anidadas")
        buffer.order(
            when (buffer.get().toInt()) {
                0 -> ByteOrder.BIG_ENDIAN
                1 -> ByteOrder.LITTLE_ENDIAN
                else -> throw InvalidWkb("Orden de bytes desconocido")
            },
        )
        val code = buffer.getInt().toLong() and 0xFFFFFFFFL
        val ewkb = code and (EWKB_Z or EWKB_M or EWKB_SRID) != 0L
        val type: Int
        val dims: Dims
        if (ewkb) {
            type = (code and 0x0FFFFFFFL).toInt()
            dims = Dims(code and EWKB_Z != 0L, code and EWKB_M != 0L)
            if (code and EWKB_SRID != 0L) buffer.getInt()
        } else {
            if (code >= 4000) throw InvalidWkb("Tipo de geometría desconocido")
            type = (code % 1000).toInt()
            dims = Dims(code / 1000 == 1L || code / 1000 == 3L, code / 1000 == 2L || code / 1000 == 3L)
        }
        if (type in CURVES) return true
        when (type) {
            POINT -> readPoint(buffer, dims, parts)
            LINESTRING -> readLine(buffer, dims, parts)
            POLYGON -> readPolygon(buffer, dims, parts)
            MULTIPOINT, MULTILINESTRING, MULTIPOLYGON, COLLECTION -> {
                val count = buffer.getInt()
                if (count < 0 || count > buffer.remaining()) throw InvalidWkb("Número de partes imposible")
                repeat(count) { if (readGeometry(buffer, parts, depth + 1)) return true }
            }
            else -> throw InvalidWkb("Tipo de geometría no soportado: $type")
        }
        return false
    }

    private fun readPoint(buffer: ByteBuffer, dims: Dims, parts: MutableList<GpkgPart>) {
        val values = DoubleArray(dims.count) { buffer.getDouble() }
        if (values[0].isNaN() || values[1].isNaN()) return // el punto vacío del WKB
        if (values.any { it.isInfinite() }) throw InvalidWkb("Coordenada infinita")
        val z = if (dims.hasZ) doubleArrayOf(values[2]).takeIf { it[0].isFinite() } else null
        parts += GpkgPart(GpkgPartKind.POINT, doubleArrayOf(values[0]), doubleArrayOf(values[1]), z)
    }

    private fun readLine(buffer: ByteBuffer, dims: Dims, parts: MutableList<GpkgPart>) {
        readVertices(buffer, dims)?.let { parts += GpkgPart(GpkgPartKind.LINE, it.x, it.y, it.z) }
    }

    private fun readPolygon(buffer: ByteBuffer, dims: Dims, parts: MutableList<GpkgPart>) {
        val rings = buffer.getInt()
        if (rings < 0 || rings > buffer.remaining()) throw InvalidWkb("Número de anillos imposible")
        var index = 0
        var exteriorEmpty = false
        repeat(rings) { i ->
            val ring = readVertices(buffer, dims)
            if (i == 0 && ring == null) exteriorEmpty = true
            if (ring != null && !exteriorEmpty) parts += GpkgPart(GpkgPartKind.LINE, ring.x, ring.y, ring.z, index++)
        }
    }

    private class Vertices(val x: DoubleArray, val y: DoubleArray, val z: DoubleArray?)

    /** Los vértices de una línea o un anillo, o null si no tiene ninguno. */
    private fun readVertices(buffer: ByteBuffer, dims: Dims): Vertices? {
        val n = buffer.getInt()
        if (n < 0 || n.toLong() * dims.count * Double.SIZE_BYTES > buffer.remaining()) throw InvalidWkb("Número de vértices imposible")
        if (n == 0) return null
        val x = DoubleArray(n)
        val y = DoubleArray(n)
        val z = if (dims.hasZ) DoubleArray(n) else null
        for (i in 0 until n) {
            x[i] = buffer.getDouble()
            y[i] = buffer.getDouble()
            if (z != null) z[i] = buffer.getDouble()
            if (dims.hasM) buffer.getDouble()
            if (!x[i].isFinite() || !y[i].isFinite()) throw InvalidWkb("Coordenada no finita")
        }
        return Vertices(x, y, z?.takeIf { heights -> heights.all(Double::isFinite) })
    }
}
