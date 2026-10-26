package es.topophone.core.formats.landxml

import es.topophone.core.formats.xml.XmlFormatException
import es.topophone.core.formats.xml.XmlStreamReader
import es.topophone.core.model.project.LinearUnit
import java.io.InputStream

/** Por qué un XML bien formado no es un LandXML que se pueda leer (S042/RF-2, RF-3). */
enum class LandXmlProblem { NOT_LANDXML, UNSUPPORTED_VERSION }

/** El fichero es XML pero no un LandXML de las versiones 1.0 a 1.2: [version] es la que declara, si declara alguna. */
class LandXmlException(val problem: LandXmlProblem, val version: String? = null) :
    Exception(if (problem == LandXmlProblem.UNSUPPORTED_VERSION) "LandXML de la versión $version" else "No es un LandXML")

/**
 * Lo que se sabe de una superficie del fichero sin leer sus triángulos (S042/RF-5, RF-8). [index] es su posición entre todas las
 * superficies del fichero y sirve para pedirla a [LandXmlReader.read]. Las cotas y los recuadros están en la unidad del fichero.
 * [isTin] es falso para una superficie que no es una malla de triángulos (por ejemplo una malla regular), que no se ofrece.
 */
class LandXmlSurfaceInfo(
    val index: Int,
    val name: String,
    val isTin: Boolean,
    val pointCount: Int,
    val faceCount: Int,
    val hiddenFaceCount: Int,
    val minEasting: Double,
    val maxEasting: Double,
    val minNorthing: Double,
    val maxNorthing: Double,
    val minHeight: Double,
    val maxHeight: Double,
) {
    /** Los triángulos que se ven: las caras que el fichero no marca como invisibles. */
    val visibleFaceCount: Int get() = faceCount - hiddenFaceCount

    /** Se ofrece al importar: es una malla de triángulos y tiene alguno que se ve. */
    val importable: Boolean get() = isTin && visibleFaceCount > 0 && pointCount >= 3
}

/** Lo que dice un LandXML sin leer sus triángulos: su versión, su unidad y sus superficies (S042/RF-2, RF-5, RF-7). */
class LandXmlScan(val version: String?, val declaredUnit: LinearUnit?, val surfaces: List<LandXmlSurfaceInfo>) {
    /** Las superficies que se pueden importar. */
    val importable: List<LandXmlSurfaceInfo> get() = surfaces.filter { it.importable }
}

/**
 * Una superficie leída: sus puntos en la unidad del fichero y sus triángulos visibles como tres posiciones de punto cada uno.
 * [hiddenFaceCount] son las caras invisibles (los huecos) y [droppedFaceCount] las que citaban un punto que no existe o repetían
 * un punto (S042/RF-12, RF-13).
 */
class LandXmlSurface(
    val name: String,
    val easting: DoubleArray,
    val northing: DoubleArray,
    val height: DoubleArray,
    val triangles: IntArray,
    val hiddenFaceCount: Int,
    val droppedFaceCount: Int,
) {
    val pointCount: Int get() = easting.size
    val triangleCount: Int get() = triangles.size / 3
}

/**
 * Lee un LandXML por flujo (S042/RF-2, RF-3, RF-5, RF-7, RF-12). [scan] lo recorre una vez para saber qué superficies trae;
 * [read] lo recorre otra vez y entrega una. Cada punto `<P>` es **Norte, Este, cota**, que es lo que define LandXML y lo contrario
 * de un CSV de puntos. Las caras `<F i="1">` son invisibles. Los identificadores de punto pueden no ser consecutivos ni números.
 */
object LandXmlReader {

    /** Las superficies del fichero con sus recuentos. [XmlFormatException] si el XML está mal; [LandXmlException] si no es un LandXML 1.0 a 1.2. */
    fun scan(input: InputStream): LandXmlScan {
        val xml = XmlStreamReader(input)
        val header = Header()
        val surfaces = mutableListOf<LandXmlSurfaceInfo>()
        walk(xml, header) { index, name ->
            surfaces += scanTin(xml, index, name)
            false
        }
        return LandXmlScan(header.version, header.unit, surfaces)
    }

    /** La superficie número [index] del fichero (la [LandXmlSurfaceInfo.index] de [scan]); null si no hay tal superficie o no es una malla. */
    fun read(input: InputStream, index: Int): LandXmlSurface? {
        val xml = XmlStreamReader(input)
        var result: LandXmlSurface? = null
        walk(xml, Header()) { current, name ->
            if (current == index) {
                result = readTin(xml, name)
                true
            } else {
                xml.skipElement()
                false
            }
        }
        return result
    }

    private class Header {
        var version: String? = null
        var unit: LinearUnit? = null
    }

    /**
     * Recorre el fichero: comprueba la raíz y la versión, toma la unidad y llama a [onSurface] con cada `<Surface>` ya abierto (con su
     * posición y su nombre); [onSurface] debe dejar el lector tras el `END` de la superficie y devuelve true si ya no hace falta seguir leyendo.
     */
    private fun walk(xml: XmlStreamReader, header: Header, onSurface: (Int, String) -> Boolean) {
        var rootSeen = false
        var surfaceIndex = 0
        while (true) {
            when (xml.next()) {
                XmlStreamReader.Event.EOF -> {
                    if (!rootSeen) throw LandXmlException(LandXmlProblem.NOT_LANDXML)
                    return
                }

                XmlStreamReader.Event.START -> {
                    if (!rootSeen) {
                        rootSeen = true
                        if (xml.name != "LandXML") throw LandXmlException(LandXmlProblem.NOT_LANDXML)
                        val version = xml.attribute("version")
                        header.version = version
                        if (version != null && !SUPPORTED_VERSION.matches(version.trim())) {
                            throw LandXmlException(LandXmlProblem.UNSUPPORTED_VERSION, version)
                        }
                    } else {
                        when (xml.name) {
                            "Units" -> header.unit = units(xml)
                            "Surface" -> {
                                val name = xml.attribute("name") ?: ""
                                if (onSurface(surfaceIndex++, name)) return
                            }
                            "Surfaces" -> Unit
                            else -> if (xml.depth == 2) xml.skipElement()
                        }
                    }
                }

                else -> Unit
            }
        }
    }

    /** La unidad del hijo `Metric` o `Imperial` de `Units`, ya leído hasta su `END`; null si no es una de las tres de la app. */
    private fun units(xml: XmlStreamReader): LinearUnit? {
        var unit: LinearUnit? = null
        while (true) {
            when (xml.next()) {
                XmlStreamReader.Event.START -> when (xml.name) {
                    "Metric" -> unit = if (xml.attribute("linearUnit")?.lowercase() in METRES) LinearUnit.METRE else null
                    "Imperial" -> unit = when (xml.attribute("linearUnit")?.lowercase()) {
                        "foot", "feet" -> LinearUnit.INTERNATIONAL_FOOT
                        "ussurveyfoot" -> LinearUnit.US_SURVEY_FOOT
                        else -> null
                    }
                }

                XmlStreamReader.Event.END -> if (xml.name == "Units") return unit
                XmlStreamReader.Event.EOF -> return unit
                else -> Unit
            }
        }
    }

    /** Crecimiento de un array primitivo. */
    private class Doubles(capacity: Int = 1024) {
        var data = DoubleArray(capacity)
        var size = 0

        fun add(value: Double) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }

        fun toArray(): DoubleArray = data.copyOf(size)
    }

    private class Ints(capacity: Int = 1024) {
        var data = IntArray(capacity)
        var size = 0

        fun add(value: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }

        fun toArray(): IntArray = data.copyOf(size)
    }

    private fun scanTin(xml: XmlStreamReader, index: Int, name: String): LandXmlSurfaceInfo {
        var tin = false
        var points = 0
        var faces = 0
        var hidden = 0
        var minE = Double.POSITIVE_INFINITY
        var maxE = Double.NEGATIVE_INFINITY
        var minN = Double.POSITIVE_INFINITY
        var maxN = Double.NEGATIVE_INFINITY
        var minZ = Double.POSITIVE_INFINITY
        var maxZ = Double.NEGATIVE_INFINITY
        val numbers = DoubleArray(3)
        val surfaceDepth = xml.depth
        while (true) {
            when (xml.next()) {
                XmlStreamReader.Event.START -> when (xml.name) {
                    "Definition" -> tin = xml.attribute("surfType").equals("TIN", ignoreCase = true)
                    "P" -> if (tin) {
                        if (xml.next() == XmlStreamReader.Event.TEXT && numbers(xml.text, numbers) == 3) {
                            points++
                            minN = minOf(minN, numbers[0])
                            maxN = maxOf(maxN, numbers[0])
                            minE = minOf(minE, numbers[1])
                            maxE = maxOf(maxE, numbers[1])
                            minZ = minOf(minZ, numbers[2])
                            maxZ = maxOf(maxZ, numbers[2])
                        }
                    }

                    "F" -> if (tin) {
                        faces++
                        if (xml.attribute("i") == "1") hidden++
                    }
                }

                XmlStreamReader.Event.END -> if (xml.name == "Surface" && xml.depth == surfaceDepth - 1) break
                XmlStreamReader.Event.EOF -> break
                else -> Unit
            }
        }
        if (points == 0) {
            minE = 0.0
            maxE = 0.0
            minN = 0.0
            maxN = 0.0
            minZ = 0.0
            maxZ = 0.0
        }
        return LandXmlSurfaceInfo(index, name, tin, points, faces, hidden, minE, maxE, minN, maxN, minZ, maxZ)
    }

    private fun readTin(xml: XmlStreamReader, name: String): LandXmlSurface? {
        val east = Doubles()
        val north = Doubles()
        val height = Doubles()
        val triangles = Ints()
        val ids = IdIndex()
        var tin = false
        var hidden = 0
        var dropped = 0
        val numbers = DoubleArray(3)
        val vertex = IntArray(3)
        val surfaceDepth = xml.depth
        while (true) {
            when (xml.next()) {
                XmlStreamReader.Event.START -> when (xml.name) {
                    "Definition" -> tin = xml.attribute("surfType").equals("TIN", ignoreCase = true)
                    "P" -> if (tin) {
                        val id = xml.attribute("id")
                        if (xml.next() == XmlStreamReader.Event.TEXT && id != null && numbers(xml.text, numbers) == 3) {
                            ids.put(id, east.size)
                            north.add(numbers[0])
                            east.add(numbers[1])
                            height.add(numbers[2])
                        }
                    }

                    "F" -> if (tin) {
                        val invisible = xml.attribute("i") == "1"
                        if (xml.next() == XmlStreamReader.Event.TEXT) {
                            val ok = ids.lookup3(xml.text, vertex)
                            when {
                                invisible -> hidden++
                                ok && vertex[0] != vertex[1] && vertex[1] != vertex[2] && vertex[0] != vertex[2] -> {
                                    triangles.add(vertex[0])
                                    triangles.add(vertex[1])
                                    triangles.add(vertex[2])
                                }

                                else -> dropped++
                            }
                        } else if (invisible) {
                            hidden++
                        } else {
                            dropped++
                        }
                    }
                }

                XmlStreamReader.Event.END -> if (xml.name == "Surface" && xml.depth == surfaceDepth - 1) break
                XmlStreamReader.Event.EOF -> break
                else -> Unit
            }
        }
        if (!tin) return null
        return LandXmlSurface(name, east.toArray(), north.toArray(), height.toArray(), triangles.toArray(), hidden, dropped)
    }

    /** Los números separados por espacios de [text] en [out]; devuelve cuántos son, o -1 si alguno no es un número finito o sobran. */
    private fun numbers(text: CharSequence, out: DoubleArray): Int {
        var count = 0
        var i = 0
        val n = text.length
        while (i < n) {
            while (i < n && text[i].isWhitespace()) i++
            if (i >= n) break
            val start = i
            while (i < n && !text[i].isWhitespace()) i++
            if (count == out.size) return -1
            val value = text.subSequence(start, i).toString().toDoubleOrNull()
            if (value == null || !value.isFinite()) return -1
            out[count++] = value
        }
        return count
    }

    /** Del identificador de punto del fichero a su posición: un mapa abierto de enteros y, si algún identificador es texto, uno de texto. */
    private class IdIndex {
        private var keys = LongArray(INITIAL)
        private var values = IntArray(INITIAL)
        private var used = BooleanArray(INITIAL)
        private var count = 0
        private var text: HashMap<String, Int>? = null

        fun put(id: String, position: Int) {
            val key = id.trim().toLongOrNull()
            if (key == null) {
                (text ?: HashMap<String, Int>().also { text = it })[id.trim()] = position
                return
            }
            if (count * 2 >= keys.size) grow()
            var slot = slot(key)
            while (used[slot] && keys[slot] != key) slot = (slot + 1) and (keys.size - 1)
            if (!used[slot]) count++
            used[slot] = true
            keys[slot] = key
            values[slot] = position
        }

        /** Las tres posiciones de los tres identificadores de [text] en [out]; false si no son tres o alguno no existe. */
        fun lookup3(text: CharSequence, out: IntArray): Boolean {
            var found = 0
            var i = 0
            val n = text.length
            while (i < n) {
                while (i < n && text[i].isWhitespace()) i++
                if (i >= n) break
                val start = i
                var numeric = true
                var value = 0L
                while (i < n && !text[i].isWhitespace()) {
                    val d = text[i] - '0'
                    if (d in 0..9 && i - start < MAX_DIGITS) value = value * 10 + d else numeric = false
                    i++
                }
                if (found == 3) return false
                val position = if (numeric) {
                    find(value)
                } else {
                    this.text?.get(text.subSequence(start, i).toString())
                        ?: text.subSequence(start, i).toString().toLongOrNull()?.let { find(it) } ?: -1
                }
                if (position < 0) return false
                out[found++] = position
            }
            return found == 3
        }

        private fun find(key: Long): Int {
            var slot = slot(key)
            while (used[slot]) {
                if (keys[slot] == key) return values[slot]
                slot = (slot + 1) and (keys.size - 1)
            }
            return -1
        }

        private fun slot(key: Long): Int = ((key * HASH) ushr 32).toInt() and (keys.size - 1)

        private fun grow() {
            val oldKeys = keys
            val oldValues = values
            val oldUsed = used
            keys = LongArray(oldKeys.size * 2)
            values = IntArray(oldKeys.size * 2)
            used = BooleanArray(oldKeys.size * 2)
            count = 0
            for (i in oldKeys.indices) if (oldUsed[i]) put(oldKeys[i].toString(), oldValues[i])
        }

        private companion object {
            const val INITIAL = 1024
            const val HASH = -7046029254386353131L
            const val MAX_DIGITS = 18
        }
    }

    private val SUPPORTED_VERSION = Regex("""1\.[0-2](\.\d+)?""")
    private val METRES = setOf("meter", "metre")
}
