package es.topophone.data.kml

import es.topophone.core.formats.kml.KmlGeometryEncoder
import es.topophone.core.formats.kml.KmlGeometryGroup
import es.topophone.core.formats.kml.KmlPart
import es.topophone.core.formats.kml.KmlPlacemark
import es.topophone.core.model.cad.GpkgIgnoredReason
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Una capa del almacén: los objetos de un tipo (puntos o líneas) de las marcas de una ruta de carpetas (S031/RF-5), con las columnas de
 * atributos que han ido apareciendo y dónde está cada objeto en el fichero. Se completa mientras se lee el KML y, al terminar, solo se lee.
 */
class KmlStoreLayer internal constructor(val path: String, val points: Boolean) {
    /** Los objetos de la capa, con los vacíos que se ignoran: su último `fid` (desde 1). */
    var count: Int = 0
        internal set

    /** Los objetos de la capa con geometría válida. */
    var valid: Int = 0
        internal set

    /** Nombre de cada dato extendido tal como viene en el KML → su columna (única, sin `name` ni `description`). */
    internal val columns = LinkedHashMap<String, String>()
    internal var positions = LongArray(INITIAL_POSITIONS)

    /** Las columnas de atributos en su orden: `name`, `description` y los datos extendidos por orden de aparición (S031/RF-11). */
    val columnNames: List<String> get() = listOf(NAME, DESCRIPTION) + columns.values

    internal fun add(position: Long) {
        if (count == positions.size) positions = positions.copyOf(count * 2)
        positions[count++] = position
    }

    internal fun positionOf(fid: Int): Long = positions[fid - 1]

    /** El índice de columna de [raw] en los registros: 0 `name`, 1 `description` y desde 2 los datos extendidos. */
    internal fun columnIndex(raw: String): Int {
        var column = columns[raw]
        if (column == null) {
            val taken = HashSet<String>(columns.values)
            taken += NAME
            taken += DESCRIPTION
            var candidate = raw
            var n = 2
            while (candidate in taken) candidate = "${raw}_${n++}"
            columns[raw] = candidate
            column = candidate
        }
        return FIXED_COLUMNS + columns.keys.indexOf(raw)
    }

    companion object {
        const val NAME = "name"
        const val DESCRIPTION = "description"
        const val FIXED_COLUMNS = 2
        private const val INITIAL_POSITIONS = 16
    }
}

/** Un objeto leído del almacén: su geometría (null si es una geometría vacía ignorada), su color y sus valores por columna. */
class KmlStoreRecord(val fid: Int, val ignoredEmpty: Boolean, val color: Int?, val blob: ByteArray?, val values: Map<Int, String>)

/**
 * Lo que dejó una lectura de un KML (S031/plan D2): un fichero temporal con un registro por objeto, para poder dar objetos por clave sin
 * releer el XML. En memoria solo queda por capa el recuento, las columnas y las posiciones de sus registros. [close] borra la carpeta.
 *
 * Registro: `int longitud | byte indicadores | [int color] | [int bytes del blob | blob] | int valores | (int columna, int bytes, texto)*`.
 * Los indicadores son 1 geometría vacía (sin blob ni valores), 2 con color propio y 4 con Z.
 */
class KmlStore internal constructor(
    private val directory: File,
    private val file: File,
    val layers: List<KmlStoreLayer>,
    val ignoredElements: Map<GpkgIgnoredReason, Int>,
    val hasElevations: Boolean,
    val drawingName: String,
) : Closeable {
    private var reader: RandomAccessFile? = null

    /** Los objetos con geometría válida de todas las capas. */
    val validObjects: Int get() = layers.sumOf { it.valid }

    @Synchronized
    private fun access(): RandomAccessFile = reader ?: RandomAccessFile(file, "r").also { reader = it }

    /** El objeto [fid] de [layer] con todo su contenido. */
    @Synchronized
    fun read(layer: KmlStoreLayer, fid: Int): KmlStoreRecord {
        val raf = access()
        raf.seek(layer.positionOf(fid))
        val length = raf.readInt()
        val bytes = ByteArray(length)
        raf.readFully(bytes)
        return parse(fid, ByteBuffer.wrap(bytes), headOnly = false)
    }

    /** Como [read] pero solo con los primeros 40 bytes del blob (su cabecera con la envolvente) y sin valores. */
    @Synchronized
    fun readHead(layer: KmlStoreLayer, fid: Int): KmlStoreRecord {
        val raf = access()
        raf.seek(layer.positionOf(fid))
        val buffer = ByteArray(HEAD_READ_BYTES)
        val read = raf.read(buffer)
        val length = ByteBuffer.wrap(buffer, 0, 4).int
        return parse(fid, ByteBuffer.wrap(buffer, 4, minOf(length, read - 4)), headOnly = true)
    }

    private fun parse(fid: Int, record: ByteBuffer, headOnly: Boolean): KmlStoreRecord {
        val flags = record.get().toInt()
        if (flags and EMPTY != 0) return KmlStoreRecord(fid, true, null, null, emptyMap())
        val color = if (flags and COLOR != 0) record.int else null
        val blobLength = record.int
        val blob = ByteArray(if (headOnly) minOf(blobLength, HEAD_BLOB_BYTES, record.remaining()) else blobLength)
        record.get(blob)
        if (headOnly) return KmlStoreRecord(fid, false, color, blob, emptyMap())
        val count = record.int
        val values = LinkedHashMap<Int, String>(count * 2)
        repeat(count) {
            val column = record.int
            val bytes = ByteArray(record.int)
            record.get(bytes)
            values[column] = String(bytes, Charsets.UTF_8)
        }
        return KmlStoreRecord(fid, false, color, blob, values)
    }

    @Synchronized
    override fun close() {
        reader?.close()
        reader = null
        directory.deleteRecursively()
    }

    internal companion object {
        const val EMPTY = 1
        const val COLOR = 2
        const val Z = 4
        const val HEAD_BLOB_BYTES = 40
        const val HEAD_READ_BYTES = 4 + 1 + 4 + 4 + HEAD_BLOB_BYTES
    }
}

/** Escribe el almacén a medida que el parser entrega marcas (S031/plan D2) y lo cierra con [finish]. */
internal class KmlStoreWriter(private val directory: File, private val stem: String) : Closeable {
    private val file = File(directory, STORE)
    private val out = BufferedOutputStream(file.outputStream(), BUFFER_BYTES)
    private val layers = ArrayList<KmlStoreLayer>()
    private val byKey = HashMap<Pair<String, Boolean>, KmlStoreLayer>()
    private var position = 0L
    private var elevations = false
    private val record = ByteArrayOutputStream()
    private val data = DataOutputStream(record)

    /** Los objetos válidos escritos hasta ahora. */
    val validObjects: Int get() = layers.sumOf { it.valid }

    fun add(placemark: KmlPlacemark) {
        val path = placemark.path.joinToString(SEPARATOR).ifEmpty { stem }
        add(path, true, placemark, placemark.points, placemark.iconColor)
        add(path, false, placemark, placemark.lines, placemark.lineColor)
    }

    private fun add(path: String, points: Boolean, placemark: KmlPlacemark, group: KmlGeometryGroup, color: Int?) {
        if (group.isNothing) return
        val layer = byKey.getOrPut(path to points) { KmlStoreLayer(path, points).also { layers += it } }
        if (group.parts.isNotEmpty()) write(layer, placemark, group.parts, color)
        repeat(group.empty) { writeEmpty(layer, placemark) }
    }

    private fun write(layer: KmlStoreLayer, placemark: KmlPlacemark, parts: List<KmlPart>, color: Int?) {
        val withZ = parts.all { it.hasZ }
        if (withZ) elevations = true
        record.reset()
        var flags = if (color != null) KmlStore.COLOR else 0
        if (withZ) flags = flags or KmlStore.Z
        data.writeInt(0) // la longitud, que se rellena al final
        data.writeByte(flags)
        if (color != null) data.writeInt(color)
        val blob = KmlGeometryEncoder.encode(parts)
        data.writeInt(blob.size)
        data.write(blob)
        val values = LinkedHashMap<Int, String>()
        if (placemark.name.isNotEmpty()) values[0] = placemark.name
        if (placemark.description.isNotEmpty()) values[1] = placemark.description
        for ((raw, value) in placemark.data) {
            val column = layer.columnIndex(raw)
            if (value.isNotEmpty()) values[column] = value
        }
        data.writeInt(values.size)
        for ((column, value) in values) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            data.writeInt(column)
            data.writeInt(bytes.size)
            data.write(bytes)
        }
        layer.valid++
        flush(layer)
    }

    private fun writeEmpty(layer: KmlStoreLayer, placemark: KmlPlacemark) {
        for (raw in placemark.data.keys) layer.columnIndex(raw) // las columnas del objeto, aunque no se importe
        record.reset()
        data.writeInt(0)
        data.writeByte(KmlStore.EMPTY)
        flush(layer)
    }

    private fun flush(layer: KmlStoreLayer) {
        val bytes = record.toByteArray()
        ByteBuffer.wrap(bytes).putInt(bytes.size - Int.SIZE_BYTES)
        layer.add(position)
        out.write(bytes)
        position += bytes.size
    }

    /** Cierra el fichero y entrega el almacén con [ignored] y el nombre del dibujo [drawingName]. */
    fun finish(ignored: Map<GpkgIgnoredReason, Int>, drawingName: String): KmlStore {
        out.close()
        return KmlStore(directory, file, layers.toList(), ignored.toMap(), elevations, drawingName)
    }

    override fun close() {
        out.close()
    }

    private companion object {
        const val STORE = "objetos.bin"
        const val SEPARATOR = " / "
        const val BUFFER_BYTES = 64 * 1024
    }
}
