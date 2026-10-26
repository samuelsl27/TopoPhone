package es.topophone.core.formats.dxf

import es.topophone.core.model.cad.CadColor
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadIgnored
import es.topophone.core.model.cad.CadIgnoredReason
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Adonde van los elementos del DXF ya desplegados, en las coordenadas del dibujo, y lo que no entra (S040/RF-14, RF-16). */
interface DxfSink {
    /** Las capas del DXF, antes del primer elemento: sirven para dar a cada capa su color y su estado. */
    fun tables(tables: DxfTables) {}

    /** Un elemento en la capa [layer] (el nombre en el DXF); su color es null si es el de su capa. */
    fun element(layer: String, entity: CadEntity)

    /** Algo que no entra: un tipo no soportado, un elemento roto, un ciclo de bloques… */
    fun ignored(ignored: CadIgnored)
}

/** Un bloque definido: sus elementos sin desplegar y su punto base. [overflow] si se pasó de los vértices permitidos. */
class DxfBlock(val name: String, val baseX: Double, val baseY: Double, val baseZ: Double, val items: List<DxfItem>, val overflow: Boolean)

/** La sección BLOCKS (S040/RF-14). */
object DxfBlocks {
    /** Los vértices que se guardan de un bloque: el resto se descarta y se cuenta como ignorado. */
    const val MAX_BLOCK_VERTICES = 100_000

    /** Lee la sección BLOCKS, ya abierta, hasta su `ENDSEC`. Los nombres van en mayúsculas, como los compara AutoCAD. */
    fun parse(tags: DxfTagReader, maxVertices: Int = MAX_BLOCK_VERTICES): Map<String, DxfBlock> {
        val blocks = HashMap<String, DxfBlock>()
        while (true) {
            val tag = tags.next() ?: break
            if (tag.code != 0) continue
            when (tag.value.trim()) {
                "ENDSEC" -> break
                "BLOCK" -> {
                    val header = ArrayList<DxfTag>()
                    while (true) {
                        val next = tags.next() ?: return blocks
                        if (next.code == 0) {
                            tags.unread(next)
                            break
                        }
                        header += next
                    }
                    val name = header.firstOrNull { it.code == 2 }?.value?.trim() ?: continue
                    fun base(code: Int) = header.firstOrNull { it.code == code }?.double() ?: 0.0
                    val stream = DxfEntityStream(tags)
                    val items = ArrayList<DxfItem>()
                    var vertices = 0
                    var overflow = false
                    while (true) {
                        val item = stream.next() ?: break
                        if (overflow) continue
                        vertices += vertexCount(item)
                        if (vertices > maxVertices) overflow = true else items += item
                    }
                    blocks[name.uppercase()] = DxfBlock(name, base(10), base(20), base(30), items, overflow)
                    if (stream.endedWith != "ENDBLK") break
                }
            }
        }
        return blocks
    }

    private fun vertexCount(item: DxfItem): Int = when (val entity = (item as? DxfItem.Entity)?.entity) {
        null -> 0
        is DxfPolyline -> entity.vertices.size
        is DxfSpline -> max(entity.controlPoints.size, entity.fitPoints.size)
        is DxfLine -> 2
        else -> 1
    }
}

/** Una transformación afín 3×3 más traslación (S040/RF-14). */
class DxfTransform(private val m: DoubleArray, private val t: DoubleArray) {
    fun x(x: Double, y: Double, z: Double) = m[0] * x + m[1] * y + m[2] * z + t[0]

    fun y(x: Double, y: Double, z: Double) = m[3] * x + m[4] * y + m[5] * z + t[1]

    fun z(x: Double, y: Double, z: Double) = m[6] * x + m[7] * y + m[8] * z + t[2]

    /** Primero esta y luego [outer]. */
    fun then(outer: DxfTransform): DxfTransform {
        val nm = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) nm[i * 3 + j] = (0..2).sumOf { k -> outer.m[i * 3 + k] * m[k * 3 + j] }
        val nt = DoubleArray(3) { i -> (0..2).sumOf { k -> outer.m[i * 3 + k] * t[k] } + outer.t[i] }
        return DxfTransform(nm, nt)
    }

    /** Cuánto agranda como mucho: la mayor longitud de las imágenes de los ejes. */
    val scale: Double
        get() = (0..2).maxOf { j -> sqrt(m[j] * m[j] + m[3 + j] * m[3 + j] + m[6 + j] * m[6 + j]) }

    companion object {
        val IDENTITY = DxfTransform(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), DoubleArray(3))
    }
}

/**
 * Convierte los elementos del DXF en elementos del dibujo, con los bloques desplegados, las curvas como polilíneas y el color y
 * la capa resueltos (S040/RF-14, RF-15, RF-24). [toleranceUnits] es 1 cm en las unidades del dibujo.
 */
class DxfExpander(
    private val tables: DxfTables,
    private val blocks: Map<String, DxfBlock>,
    private val toleranceUnits: Double,
    private val sink: DxfSink,
) {
    private class Context(val xf: DxfTransform, val layer: String?, val color: CadColor?, val stack: List<String>)

    private val root = Context(DxfTransform.IDENTITY, null, null, emptyList())

    /** Un elemento recién leído: se despliega, o se cuenta como ignorado o roto. */
    fun accept(item: DxfItem) {
        when (item) {
            is DxfItem.Entity -> emit(item.entity, root)
            is DxfItem.Ignored -> sink.ignored(CadIgnored(CadIgnoredReason.UNSUPPORTED_TYPE, item.type))
            is DxfItem.Broken -> sink.ignored(CadIgnored(CadIgnoredReason.INVALID_VALUE, item.type))
        }
    }

    private fun emit(entity: DxfEntity, ctx: Context) {
        val layer = if (ctx.layer != null && entity.style.layer == "0") ctx.layer else entity.style.layer
        val layerColor = tables.colorOf(layer)
        val color = DxfColors.entity(entity.style.aci, entity.style.trueColor, layerColor, ctx.color)
        val tolerance = toleranceUnits / max(ctx.xf.scale, MIN_SCALE)
        val out = Out(layer, color, layerColor, ctx.xf)
        when (entity) {
            is DxfPoint -> out.single(CadEntityKind.POINT, entity.x, entity.y, entity.z)
            is DxfLine -> out.line(entity)
            is DxfPolyline -> polyline(entity, tolerance, out)
            is DxfArc -> {
                val c = CadCurves.arc(entity.centerX, entity.centerY, entity.radius, entity.startDegrees, entity.endDegrees, tolerance)
                out.ocs(CadEntityKind.ARC, c.x, c.y, entity.centerZ, entity.normal, entity.hasZ, c.original)
            }
            is DxfCircle -> {
                val c = CadCurves.circle(entity.centerX, entity.centerY, entity.radius, tolerance)
                out.ocs(CadEntityKind.CIRCLE, c.x, c.y, entity.centerZ, entity.normal, entity.hasZ, c.original)
            }
            is DxfSpline -> {
                val c = CadCurves.spline(entity, tolerance)
                out.global(CadEntityKind.SPLINE, c.x, c.y, c.z, c.original)
            }
            is DxfInsert -> insert(entity, ctx, layer, color, out)
        }
    }

    private fun polyline(e: DxfPolyline, tolerance: Double, out: Out) {
        val xs = DoubleBuf()
        val ys = DoubleBuf()
        val originals = BitBuf() // S040/RF-34: los vértices de los tramos con bulge no son originales
        val v = e.vertices
        val z = e.elevation ?: 0.0
        val count = v.size
        for (i in 0 until count) {
            xs.add(v[i].x)
            ys.add(v[i].y)
            originals.add(true)
            val nextIndex = when {
                i < count - 1 -> i + 1
                e.closed -> 0
                else -> -1
            }
            if (nextIndex >= 0 && v[i].bulge != 0.0 && !e.is3d) {
                val c = CadCurves.bulge(v[i].x, v[i].y, v[nextIndex].x, v[nextIndex].y, v[i].bulge, tolerance)
                for (k in c.x.indices) {
                    xs.add(c.x[k])
                    ys.add(c.y[k])
                    originals.add(false)
                }
            }
        }
        if (e.closed) {
            xs.add(v[0].x)
            ys.add(v[0].y)
            originals.add(true)
        }
        if (e.is3d) {
            val zs = DoubleBuf()
            for (i in 0 until count) zs.add(v[i].z ?: 0.0)
            if (e.closed) zs.add(v[0].z ?: 0.0)
            out.global(CadEntityKind.POLYLINE, xs.toArray(), ys.toArray(), zs.toArray(), originals.toArray())
        } else {
            out.ocs(CadEntityKind.POLYLINE, xs.toArray(), ys.toArray(), z, e.normal, e.hasZ, originals.toArray())
        }
    }

    private fun insert(e: DxfInsert, ctx: Context, layer: String, color: CadColor, out: Out) {
        val key = e.blockName.uppercase()
        if (key in ctx.stack) {
            sink.ignored(CadIgnored(CadIgnoredReason.BLOCK_CYCLE))
            return
        }
        if (ctx.stack.size >= MAX_DEPTH) {
            sink.ignored(CadIgnored(CadIgnoredReason.BLOCK_TOO_LARGE))
            return
        }
        val block = blocks[key]
        val angle = Math.toRadians(e.rotationDegrees)
        val c = cos(angle)
        val s = sin(angle)
        val (sx, sy, sz) = e.scale
        val localM = doubleArrayOf(c * sx, -s * sy, 0.0, s * sx, c * sy, 0.0, 0.0, 0.0, sz)
        val ocsX = DxfOcs.toWcs(1.0, 0.0, 0.0, e.normal)
        val ocsY = DxfOcs.toWcs(0.0, 1.0, 0.0, e.normal)
        val ocsZ = DxfOcs.toWcs(0.0, 0.0, 1.0, e.normal)
        val ocsM =
            doubleArrayOf(ocsX.first, ocsY.first, ocsZ.first, ocsX.second, ocsY.second, ocsZ.second, ocsX.third, ocsY.third, ocsZ.third)
        val ocs = DxfTransform(ocsM, DoubleArray(3))
        val local = DxfTransform(localM, DoubleArray(3))
        val ocsLocal = local.then(ocs)
        for (row in 0 until e.rows) {
            for (col in 0 until e.columns) {
                val ox = col * e.columnSpacing
                val oy = row * e.rowSpacing
                val px = e.x + c * ox - s * oy
                val py = e.y + s * ox + c * oy
                val pz = e.z ?: 0.0
                // El punto de inserción de cada copia, en la capa del INSERT.
                out.single(CadEntityKind.BLOCK_INSERT, ocs.x(px, py, pz), ocs.y(px, py, pz), if (e.z == null) null else ocs.z(px, py, pz))
                if (block == null) {
                    sink.ignored(CadIgnored(CadIgnoredReason.UNDEFINED_BLOCK))
                    continue
                }
                if (block.overflow) sink.ignored(CadIgnored(CadIgnoredReason.BLOCK_TOO_LARGE))
                // p' = ocs · (R·S·(p − base) + ins + R·offset)
                val t0 = ocs.x(px, py, pz) - ocsLocal.x(block.baseX, block.baseY, block.baseZ)
                val t1 = ocs.y(px, py, pz) - ocsLocal.y(block.baseX, block.baseY, block.baseZ)
                val t2 = ocs.z(px, py, pz) - ocsLocal.z(block.baseX, block.baseY, block.baseZ)
                val moved = ocsLocal.then(DxfTransform(IDENTITY_M, doubleArrayOf(t0, t1, t2))).then(ctx.xf)
                val inner = Context(moved, layer, color, ctx.stack + key)
                for (item in block.items) {
                    if (item is DxfItem.Entity) emit(item.entity, inner) else accept(item)
                }
            }
        }
    }

    /** Escribe un elemento ya con su transformación, su capa y su color. */
    private inner class Out(val layer: String, val color: CadColor, val layerColor: CadColor, val xf: DxfTransform) {
        private fun finish(kind: CadEntityKind, xs: DoubleArray, ys: DoubleArray, zs: DoubleArray?, original: BooleanArray? = null) {
            val ex = DoubleArray(xs.size)
            val ey = DoubleArray(xs.size)
            val ez = if (zs != null) DoubleArray(xs.size) else null
            for (i in xs.indices) {
                val z = zs?.get(i) ?: 0.0
                ex[i] = xf.x(xs[i], ys[i], z)
                ey[i] = xf.y(xs[i], ys[i], z)
                if (ez != null) ez[i] = xf.z(xs[i], ys[i], z)
            }
            sink.element(layer, CadEntity(kind, ex, ey, ez, if (color == layerColor) null else color, original))
        }

        fun single(kind: CadEntityKind, x: Double, y: Double, z: Double?) =
            finish(kind, doubleArrayOf(x), doubleArrayOf(y), z?.let { doubleArrayOf(it) })

        fun line(e: DxfLine) {
            val hasZ = e.start.z != null || e.end.z != null
            finish(
                CadEntityKind.LINE,
                doubleArrayOf(e.start.x, e.end.x),
                doubleArrayOf(e.start.y, e.end.y),
                if (hasZ) doubleArrayOf(e.start.z ?: 0.0, e.end.z ?: 0.0) else null,
            )
        }

        /** Puntos ya en el sistema global; [zs] es null si no traen Z. */
        fun global(kind: CadEntityKind, xs: DoubleArray, ys: DoubleArray, zs: DoubleArray?, original: BooleanArray? = null) =
            finish(kind, xs, ys, zs, original)

        /** Puntos en el OCS de [normal] con la Z común [z]; [hasZ] dice si el elemento trae Z. */
        fun ocs(
            kind: CadEntityKind,
            xs: DoubleArray,
            ys: DoubleArray,
            z: Double,
            normal: Triple<Double, Double, Double>,
            hasZ: Boolean,
            original: BooleanArray? = null,
        ) {
            val wx = DoubleArray(xs.size)
            val wy = DoubleArray(xs.size)
            val wz = DoubleArray(xs.size)
            for (i in xs.indices) {
                val w = DxfOcs.toWcs(xs[i], ys[i], z, normal)
                wx[i] = w.first
                wy[i] = w.second
                wz[i] = w.third
            }
            finish(kind, wx, wy, if (hasZ) wz else null, original)
        }
    }

    /** Un vector de `Boolean` que crece. */
    private class BitBuf {
        private var data = BooleanArray(16)
        private var size = 0

        fun add(v: Boolean) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }

        fun toArray(): BooleanArray = data.copyOf(size)
    }

    /** Un vector de `Double` que crece, sin empaquetar cada número. */
    private class DoubleBuf {
        private var data = DoubleArray(16)
        private var size = 0

        fun add(v: Double) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }

        fun toArray(): DoubleArray = data.copyOf(size)
    }

    private companion object {
        const val MAX_DEPTH = 16
        const val MIN_SCALE = 1e-9
        val IDENTITY_M = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    }
}
