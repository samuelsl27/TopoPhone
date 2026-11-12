package es.topophone.core.formats.kml

/**
 * Los vértices de un `<coordinates>` en longitud y latitud (grados) tal como están en el KML, sin redondear (S031/RF-7). [alt] solo existe
 * si **todos** los vértices traen altitud: una geometría con la altitud a medias no la lleva (S031/RF-13).
 */
class KmlVertices(val lon: DoubleArray, val lat: DoubleArray, private val altitudes: DoubleArray) {
    val size: Int get() = lon.size

    /** La altitud de cada vértice, o `null` si alguno no la trae (en [altitudes] falta como `NaN`). */
    val alt: DoubleArray? = if (altitudes.isNotEmpty() && altitudes.none { it.isNaN() }) altitudes else null

    /** Solo el primer vértice, con su altitud si la trae: lo que usa un `Point` cuando escribe más de una tupla (S031/RF-8). */
    fun first(): KmlVertices = KmlVertices(doubleArrayOf(lon[0]), doubleArrayOf(lat[0]), doubleArrayOf(altitudes[0]))
}

/**
 * El texto de un `<coordinates>`: tuplas `lon,lat[,alt]` separadas por espacios, tabuladores o saltos de línea (S031/RF-7). Es estricto con
 * los números: solo valen decimales corrientes, con signo, punto y exponente, sin `NaN`, `Infinity`, sufijos ni hexadecimales, y una tupla
 * que no se entiende deja ilegible toda la lista (S031/RF-9: geometría vacía). Un cuarto valor, que algunos programas escriben, se ignora.
 */
object KmlCoordinates {
    private const val INITIAL_CAPACITY = 16
    private const val MIN_VALUES = 2
    private const val ALTITUDE_INDEX = 2

    /** Los vértices de [text], vacíos si no hay ninguno, o `null` si alguna tupla es ilegible. */
    fun parse(text: CharSequence): KmlVertices? {
        var lon = DoubleArray(INITIAL_CAPACITY)
        var lat = DoubleArray(INITIAL_CAPACITY)
        var alt = DoubleArray(INITIAL_CAPACITY)
        var size = 0
        var i = 0
        val n = text.length
        while (i < n) {
            while (i < n && text[i].isWhitespace()) i++
            if (i >= n) break
            val start = i
            while (i < n && !text[i].isWhitespace()) i++
            if (size == lon.size) {
                lon = lon.copyOf(size * 2)
                lat = lat.copyOf(size * 2)
                alt = alt.copyOf(size * 2)
            }
            var count = 0
            var from = start
            while (true) {
                var end = from
                while (end < i && text[end] != ',') end++
                if (!isPlainNumber(text, from, end)) return null
                val value = text.substring(from, end).toDouble()
                when (count) {
                    0 -> lon[size] = value
                    1 -> lat[size] = value
                    ALTITUDE_INDEX -> alt[size] = value
                }
                count++
                if (end >= i) break
                from = end + 1
            }
            if (count < MIN_VALUES) return null
            if (count <= ALTITUDE_INDEX) alt[size] = Double.NaN
            size++
        }
        return KmlVertices(lon.copyOf(size), lat.copyOf(size), alt.copyOf(size))
    }

    /** `[+-]? (dígitos [. dígitos*] | . dígitos+) ([eE] [+-]? dígitos+)?` en `text[from, to)`. */
    private fun isPlainNumber(text: CharSequence, from: Int, to: Int): Boolean {
        var i = from
        if (i < to && (text[i] == '+' || text[i] == '-')) i++
        var digits = 0
        while (i < to && text[i] in '0'..'9') {
            i++
            digits++
        }
        if (i < to && text[i] == '.') {
            i++
            while (i < to && text[i] in '0'..'9') {
                i++
                digits++
            }
        }
        if (digits == 0) return false
        if (i < to && (text[i] == 'e' || text[i] == 'E')) {
            i++
            if (i < to && (text[i] == '+' || text[i] == '-')) i++
            var exponent = 0
            while (i < to && text[i] in '0'..'9') {
                i++
                exponent++
            }
            if (exponent == 0) return false
        }
        return i == to
    }
}
