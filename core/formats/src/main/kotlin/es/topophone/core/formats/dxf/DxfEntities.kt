package es.topophone.core.formats.dxf

import kotlin.math.abs
import kotlin.math.sqrt

/** La capa y el color de un elemento tal como están en el DXF: ACI propio (62) y color verdadero (420) si los trae. */
data class DxfStyle(val layer: String, val aci: Int?, val trueColor: Int?)

/** Un vértice en el sistema del elemento; [z] es null si el DXF no la escribe, y [bulge] es la curvatura hasta el siguiente (S040/RF-14). */
data class DxfVertex(val x: Double, val y: Double, val z: Double?, val bulge: Double = 0.0)

/** Un elemento del DXF ya leído, sin convertir curvas ni desplegar bloques (S040/RF-14). */
sealed interface DxfEntity {
    val style: DxfStyle
}

/** Un `POINT`. */
data class DxfPoint(override val style: DxfStyle, val x: Double, val y: Double, val z: Double?) : DxfEntity

/** Un `LINE`. */
data class DxfLine(override val style: DxfStyle, val start: DxfVertex, val end: DxfVertex) : DxfEntity

/**
 * Una `LWPOLYLINE` o una `POLYLINE` 2D o 3D. Los vértices de una 2D están en el OCS de [normal]; [elevation] es la Z común de una
 * 2D (código 38, o 30 de la cabecera de una `POLYLINE`), o null si no la trae (S040/RF-22).
 */
data class DxfPolyline(
    override val style: DxfStyle,
    val vertices: List<DxfVertex>,
    val closed: Boolean,
    val is3d: Boolean,
    val elevation: Double?,
    val normal: Triple<Double, Double, Double>,
) : DxfEntity {
    /** Los vértices de una 3D traen su Z; los de una 2D, la de su elevación si la hay. */
    val hasZ: Boolean get() = if (is3d) vertices.any { it.z != null } else elevation != null
}

/** Un `ARC` en el OCS de [normal]; los ángulos van en grados, en sentido antihorario. */
data class DxfArc(
    override val style: DxfStyle,
    val centerX: Double,
    val centerY: Double,
    val centerZ: Double,
    val radius: Double,
    val startDegrees: Double,
    val endDegrees: Double,
    val normal: Triple<Double, Double, Double>,
    val hasZ: Boolean,
) : DxfEntity

/** Un `CIRCLE` en el OCS de [normal]. */
data class DxfCircle(
    override val style: DxfStyle,
    val centerX: Double,
    val centerY: Double,
    val centerZ: Double,
    val radius: Double,
    val normal: Triple<Double, Double, Double>,
    val hasZ: Boolean,
) : DxfEntity

/** Lo que sale de leer un elemento (S040/RF-13, RF-16). */
sealed interface DxfItem {
    data class Entity(val entity: DxfEntity) : DxfItem

    /** Un tipo que no se importa (`TEXT`, `HATCH`…): su nombre en el DXF. */
    data class Ignored(val type: String) : DxfItem

    /** Un elemento al que le falta un dato o con un valor que no es un número. */
    data class Broken(val type: String) : DxfItem
}

/** Del sistema de coordenadas del objeto (OCS) al global con el algoritmo del eje arbitrario de AutoCAD (S040/RF-14). */
object DxfOcs {
    private const val SMALL = 1.0 / 64.0

    fun toWcs(x: Double, y: Double, z: Double, normal: Triple<Double, Double, Double>): Triple<Double, Double, Double> {
        if (normal.first == 0.0 && normal.second == 0.0 && normal.third == 1.0) return Triple(x, y, z)
        val size = sqrt(normal.first * normal.first + normal.second * normal.second + normal.third * normal.third)
        val nx = normal.first / size
        val ny = normal.second / size
        val nz = normal.third / size
        // Ax = Wy × N si N está cerca del eje Z, y Wz × N si no; después se normaliza.
        val near = abs(nx) < SMALL && abs(ny) < SMALL
        val rawX = if (near) nz else -ny
        val rawY = if (near) 0.0 else nx
        val rawZ = if (near) -nx else 0.0
        val length = sqrt(rawX * rawX + rawY * rawY + rawZ * rawZ)
        val axx = rawX / length
        val axy = rawY / length
        val axz = rawZ / length
        // Ay = N × Ax
        val ayx = ny * axz - nz * axy
        val ayy = nz * axx - nx * axz
        val ayz = nx * axy - ny * axx
        // El + 0.0 final evita un -0.0.
        return Triple(
            axx * x + ayx * y + nx * z + 0.0,
            axy * x + ayy * y + ny * z + 0.0,
            axz * x + ayz * y + nz * z + 0.0,
        )
    }
}

/**
 * Lee los elementos de una sección ENTITIES (o de un bloque) uno a uno, por flujo (S040/RF-13, RF-14, RF-16). Se para en el
 * `ENDSEC`, el `ENDBLK` o el `EOF` sin entregarlos. Un elemento al que el fichero le acaba a mitad se descarta y se marca
 * [truncated].
 */
class DxfEntityStream(private val tags: DxfTagReader) {
    var truncated: Boolean = false
        private set

    /** Con qué acabó la sección: `ENDSEC`, `ENDBLK` o `EOF`; null mientras no ha acabado o si el fichero se cortó. */
    var endedWith: String? = null
        private set

    private var finished = false

    fun next(): DxfItem? {
        if (finished) return null
        var head = tags.next()
        while (head != null && head.code != 0) head = tags.next() // basura entre elementos
        if (head == null) return finish(null)
        val type = head.value.trim()
        if (type == "ENDSEC" || type == "ENDBLK" || type == "EOF") return finish(type)
        val group = readGroup() ?: return finish(null)
        return when (type) {
            "POINT" -> parsePoint(group)
            "LINE" -> parseLine(group)
            "LWPOLYLINE" -> parseLwPolyline(group)
            "POLYLINE" -> parsePolyline(group) ?: return finish(null)
            "ARC" -> parseArc(group)
            "CIRCLE" -> parseCircle(group)
            else -> DxfItem.Ignored(type)
        }
    }

    private fun finish(with: String?): DxfItem? {
        finished = true
        endedWith = with
        if (with == null) truncated = true
        return null
    }

    /** Los pares de un elemento hasta el siguiente código 0, que se devuelve; null si el fichero acaba antes. */
    private fun readGroup(): List<DxfTag>? {
        val out = ArrayList<DxfTag>()
        while (true) {
            val tag = tags.next() ?: return null
            if (tag.code == 0) {
                tags.unread(tag)
                return out
            }
            out += tag
        }
    }

    private fun style(group: List<DxfTag>): DxfStyle {
        var layer = "0"
        var aci: Int? = null
        var trueColor: Int? = null
        for (tag in group) {
            when (tag.code) {
                8 -> layer = tag.value
                62 -> aci = tag.int()
                420 -> trueColor = tag.int()?.and(0xFFFFFF)
            }
        }
        return DxfStyle(layer, aci, trueColor)
    }

    /** El primer valor numérico del código [code]; null si falta; [Invalid] si no es un número. */
    private fun number(group: List<DxfTag>, code: Int): Double? {
        val tag = group.firstOrNull { it.code == code } ?: return null
        return tag.double() ?: throw Invalid
    }

    private fun required(group: List<DxfTag>, code: Int): Double = number(group, code) ?: throw Invalid

    private fun normal(group: List<DxfTag>): Triple<Double, Double, Double> {
        val n = Triple(number(group, 210) ?: 0.0, number(group, 220) ?: 0.0, number(group, 230) ?: 1.0)
        val length = sqrt(n.first * n.first + n.second * n.second + n.third * n.third)
        return if (length == 0.0) Triple(0.0, 0.0, 1.0) else Triple(n.first / length, n.second / length, n.third / length)
    }

    private inline fun guard(type: String, block: () -> DxfItem): DxfItem = try {
        block()
    } catch (_: Invalid) {
        DxfItem.Broken(type)
    }

    private fun parsePoint(group: List<DxfTag>) = guard("POINT") {
        DxfItem.Entity(DxfPoint(style(group), required(group, 10), required(group, 20), number(group, 30)))
    }

    private fun parseLine(group: List<DxfTag>) = guard("LINE") {
        DxfItem.Entity(
            DxfLine(
                style(group),
                DxfVertex(required(group, 10), required(group, 20), number(group, 30)),
                DxfVertex(required(group, 11), required(group, 21), number(group, 31)),
            ),
        )
    }

    private fun parseArc(group: List<DxfTag>) = guard("ARC") {
        val z = number(group, 30)
        DxfItem.Entity(
            DxfArc(
                style(group), required(group, 10), required(group, 20), z ?: 0.0, required(group, 40),
                required(group, 50), required(group, 51), normal(group), hasZ = z != null,
            ),
        )
    }

    private fun parseCircle(group: List<DxfTag>) = guard("CIRCLE") {
        val z = number(group, 30)
        DxfItem.Entity(
            DxfCircle(
                style(group),
                required(group, 10),
                required(group, 20),
                z ?: 0.0,
                required(group, 40),
                normal(group),
                hasZ = z != null,
            ),
        )
    }

    private fun parseLwPolyline(group: List<DxfTag>) = guard("LWPOLYLINE") {
        val flags = group.firstOrNull { it.code == 70 }?.int() ?: 0
        val vertices = ArrayList<DxfVertex>()
        var x: Double? = null
        for (tag in group) {
            when (tag.code) {
                10 -> x = tag.double() ?: throw Invalid
                20 -> {
                    val px = x ?: throw Invalid
                    vertices += DxfVertex(px, tag.double() ?: throw Invalid, null)
                    x = null
                }
                42 -> if (vertices.isNotEmpty()) {
                    val last = vertices.removeAt(vertices.lastIndex)
                    vertices += last.copy(bulge = tag.double() ?: throw Invalid)
                }
            }
        }
        if (vertices.isEmpty()) throw Invalid
        DxfItem.Entity(
            DxfPolyline(
                style(group),
                vertices,
                closed = flags and 1 != 0,
                is3d = false,
                elevation = number(group, 38),
                normal = normal(group),
            ),
        )
    }

    /** Una `POLYLINE` con sus `VERTEX` y su `SEQEND`; null si el fichero se corta dentro de ella. */
    private fun parsePolyline(header: List<DxfTag>): DxfItem? {
        val flags = header.firstOrNull { it.code == 70 }?.int() ?: 0
        val vertices = ArrayList<DxfVertex>()
        var broken = false
        val ignored = flags and (MESH or POLYFACE) != 0
        while (true) {
            val head = tags.next() ?: return null
            if (head.code != 0) continue
            val type = head.value.trim()
            if (type == "VERTEX") {
                val group = readGroup() ?: return null
                val vertexFlags = group.firstOrNull { it.code == 70 }?.int() ?: 0
                if (vertexFlags and SPLINE_FRAME != 0 || ignored) continue
                try {
                    vertices += DxfVertex(required(group, 10), required(group, 20), number(group, 30), number(group, 42) ?: 0.0)
                } catch (_: Invalid) {
                    broken = true
                }
            } else {
                if (type == "SEQEND") {
                    readGroup() ?: return null
                } else {
                    tags.unread(head)
                }
                break
            }
        }
        if (ignored) return DxfItem.Ignored("POLYLINE")
        val is3d = flags and POLYLINE_3D != 0
        val elevation = header.firstOrNull { it.code == 30 }?.double()
        return try {
            if (broken || vertices.isEmpty()) throw Invalid
            val z = if (is3d) null else elevation
            val fixed = if (is3d) vertices else vertices.map { it.copy(z = z?.takeIf { e -> e != 0.0 }) }
            DxfItem.Entity(
                DxfPolyline(
                    style(header),
                    fixed,
                    closed = flags and 1 != 0,
                    is3d = is3d,
                    elevation = if (is3d) null else elevation?.takeIf { it != 0.0 },
                    normal = normal(header),
                ),
            )
        } catch (_: Invalid) {
            DxfItem.Broken("POLYLINE")
        }
    }

    /** Un dato que falta o que no es un número; sin traza de pila, porque se lanza por elemento roto. */
    private object Invalid : RuntimeException(null, null, false, false)

    private companion object {
        const val POLYLINE_3D = 8
        const val MESH = 16
        const val POLYFACE = 64
        const val SPLINE_FRAME = 16
    }
}
