package es.topophone.core.formats.kml

/**
 * Los vértices de un `<coordinates>` en longitud y latitud (grados) tal como están en el KML, sin redondear (S031/RF-7). [alt] solo existe
 * si **todos** los vértices traen altitud: una geometría con la altitud a medias no la lleva (S031/RF-13).
 */
class KmlVertices(val lon: DoubleArray, val lat: DoubleArray, val alt: DoubleArray?) {
    val size: Int get() = lon.size
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
        var withAltitude = 0
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
            if (count > ALTITUDE_INDEX) withAltitude++
            size++
        }
        return KmlVertices(lon.copyOf(size), lat.copyOf(size), if (size > 0 && withAltitude == size) alt.copyOf(size) else null)
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
