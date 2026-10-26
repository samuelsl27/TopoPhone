package es.topophone.core.formats.dxf

import es.topophone.core.model.project.LinearUnit
import java.io.InputStream
import java.nio.charset.Charset

/** Un par código de grupo / valor del DXF, con el valor tal como está en el fichero, sin el salto de línea (S040/RF-2). */
data class DxfTag(val code: Int, val value: String) {
    /** El valor como número con punto decimal, sin mirar la configuración regional (RNF-T9); null si no lo es. */
    fun double(): Double? = value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }

    /** El valor como entero; null si no lo es. */
    fun int(): Int? = value.trim().toIntOrNull()
}

/** Un DXF que no sigue la gramática de pares: un código que no es un número (S040/RF-2, RF-13). */
class DxfFormatException(message: String, val line: Int) : Exception("$message (línea $line)")

/**
 * Lee los pares código/valor de un DXF de texto por flujo, sin guardar nada del fichero (S040/RF-2). Una línea acaba en CRLF, LF o
 * CR. El valor se decodifica con [charset], que se puede cambiar al leer la cabecera (UTF-8 desde R2007, la página de códigos
 * antes). Los comentarios (código 999) se saltan. Un fichero cortado acaba sin error: [next] da null y [truncated] dice si faltaba
 * un valor.
 */
class DxfTagReader(private val input: InputStream, bufferSize: Int = BUFFER_BYTES) {
    var charset: Charset = Charsets.ISO_8859_1

    /** El número de la línea del código del último par leído, desde 1. */
    var line: Int = 0
        private set

    /** Si el fichero acabó con un código sin su valor. */
    var truncated: Boolean = false
        private set

    private val buffer = ByteArray(bufferSize)
    private var position = 0
    private var limit = 0
    private var lastWasCr = false
    private var lineNumber = 0
    private var pushedBack: DxfTag? = null
    private var pushedBackLine = 0
    private var scratch = ByteArray(256)

    /** El siguiente par, o null al acabar el fichero. [DxfFormatException] si un código no es un número. */
    fun next(): DxfTag? {
        pushedBack?.let {
            pushedBack = null
            line = pushedBackLine
            return it
        }
        while (true) {
            val codeLength = readLine()
            if (codeLength < 0) return null
            val codeLine = lineNumber
            val code = parseCode(codeLength) ?: throw DxfFormatException("El código de grupo no es un número", codeLine)
            val valueLength = readLine()
            if (valueLength < 0) {
                truncated = true
                return null
            }
            if (code == COMMENT) continue
            line = codeLine
            return DxfTag(code, String(scratch, 0, valueLength, charset))
        }
    }

    /** Devuelve [tag], el último par leído, para que el siguiente [next] lo vuelva a dar. */
    fun unread(tag: DxfTag) {
        check(pushedBack == null) { "Solo se puede devolver un par" }
        pushedBack = tag
        pushedBackLine = line
    }

    private fun parseCode(length: Int): Int? {
        var i = 0
        while (i < length && scratch[i] == SPACE) i++
        var end = length
        while (end > i && scratch[end - 1] == SPACE) end--
        if (i == end) return null
        var negative = false
        if (scratch[i] == MINUS) {
            negative = true
            i++
        }
        if (i == end) return null
        var value = 0
        while (i < end) {
            val digit = scratch[i] - ZERO
            if (digit < 0 || digit > 9) return null
            value = value * 10 + digit
            if (value > MAX_CODE) return null
            i++
        }
        return if (negative) -value else value
    }

    /** Lee una línea en [scratch] y da su longitud en bytes, o -1 si no queda nada que leer. */
    private fun readLine(): Int {
        var length = 0
        var any = false
        while (true) {
            if (position == limit && !fill()) return if (any) finish(length) else -1
            val b = buffer[position++]
            any = true
            if (lastWasCr) {
                lastWasCr = false
                if (b == LF) {
                    any = false
                    continue
                }
            }
            if (b == LF) return finish(length)
            if (b == CR) {
                lastWasCr = true
                return finish(length)
            }
            if (length == scratch.size) scratch = scratch.copyOf(scratch.size * 2)
            scratch[length++] = b
        }
    }

    private fun finish(length: Int): Int {
        lineNumber++
        return length
    }

    private fun fill(): Boolean {
        val read = input.read(buffer, 0, buffer.size)
        if (read <= 0) return false
        position = 0
        limit = read
        return true
    }

    private companion object {
        const val BUFFER_BYTES = 1 shl 20
        const val MAX_CODE = 1_000_000
        const val COMMENT = 999
        const val LF = '\n'.code.toByte()
        const val CR = '\r'.code.toByte()
        const val SPACE = ' '.code.toByte()
        const val MINUS = '-'.code.toByte()
        const val ZERO = '0'.code.toByte()
    }
}

/** Lo que interesa de la sección HEADER (S040/RF-2, RF-7). `$EXTMIN` y `$EXTMAX` no se usan: suelen estar desfasados. */
data class DxfHeader(val version: String? = null, val insUnits: Int? = null, val codePage: String? = null) {
    /** De R2007 (`AC1021`) en adelante el texto es UTF-8; antes, el de la página de códigos. */
    val usesUtf8: Boolean get() = version != null && version >= UTF8_FROM

    /** La unidad que declara el dibujo si es una de las que ofrece la app (metros, pies o pies topográficos de EE. UU.). */
    val declaredUnit: LinearUnit?
        get() = when (insUnits) {
            INSUNITS_FEET -> LinearUnit.INTERNATIONAL_FOOT
            INSUNITS_METRES -> LinearUnit.METRE
            INSUNITS_US_SURVEY_FEET -> LinearUnit.US_SURVEY_FOOT
            else -> null
        }

    private companion object {
        const val UTF8_FROM = "AC1021"
        const val INSUNITS_FEET = 2
        const val INSUNITS_METRES = 6
        const val INSUNITS_US_SURVEY_FEET = 21
    }
}

/** La página de códigos de un DXF anterior a R2007 (`$DWGCODEPAGE`) como juego de caracteres de Java (S040/RF-2). */
object DxfCodePage {
    private val WINDOWS_1252 = Charset.forName("windows-1252")

    /** `ANSI_1250`… `ANSI_1258` son Windows-125x; cualquier otra, y la ausencia, Windows-1252. */
    fun charset(name: String?): Charset {
        val digits = name?.trim()?.uppercase()?.removePrefix("ANSI_")?.takeIf { name.trim().uppercase().startsWith("ANSI_") }
        val windows = digits?.toIntOrNull()?.takeIf { it in 1250..1258 }
        return windows?.let { Charset.forName("windows-$it") } ?: WINDOWS_1252
    }
}

/** Lee la sección HEADER ya abierta y fija la codificación del lector para lo que viene (S040/RF-2, RF-7). */
object DxfHeaderParser {
    /**
     * Consume los pares hasta el `ENDSEC` de la cabecera y devuelve lo que interesa de ella. Con `$ACADVER` de R2007 en adelante
     * el lector pasa a UTF-8; si no, a la página de códigos de `$DWGCODEPAGE`.
     */
    fun parse(tags: DxfTagReader): DxfHeader {
        var version: String? = null
        var units: Int? = null
        var codePage: String? = null
        var current: String? = null
        while (true) {
            val tag = tags.next() ?: break
            if (tag.code == 0) {
                if (tag.value.trim() != "ENDSEC") tags.unread(tag)
                break
            }
            when {
                tag.code == 9 -> current = tag.value.trim()
                current == "\$ACADVER" && tag.code == 1 && version == null -> version = tag.value.trim()
                current == "\$INSUNITS" && tag.code == 70 && units == null -> units = tag.int()
                current == "\$DWGCODEPAGE" && tag.code == 3 && codePage == null -> codePage = tag.value.trim()
            }
        }
        val header = DxfHeader(version, units, codePage)
        tags.charset = if (header.usesUtf8) Charsets.UTF_8 else DxfCodePage.charset(codePage)
        return header
    }
}
