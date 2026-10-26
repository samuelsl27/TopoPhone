package es.topophone.core.formats.xml

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.Charset
import java.nio.charset.IllegalCharsetNameException
import java.nio.charset.UnsupportedCharsetException

/** Un XML que no está bien formado, o que no es XML: [line] es la línea, desde 1, donde se vio (S042/RF-3). */
class XmlFormatException(message: String, val line: Int) : Exception("$message (línea $line)")

/**
 * Lee un XML por eventos sin guardar nada del fichero ni construir un árbol: un LandXML de 100 MB no cabe en un DOM (S042, plan A1).
 * Hace lo que un LandXML necesita y nada más: elementos con atributos, texto, `CDATA`, las cinco entidades predefinidas y las
 * numéricas; salta la declaración, las instrucciones de proceso, los comentarios y el `DOCTYPE`; ignora los espacios de nombres
 * (los nombres son los locales, sin prefijo). Reconoce un BOM y la codificación declarada (UTF-8 por defecto, también ISO-8859-1,
 * Windows-1252 y UTF-16 con BOM).
 *
 * Uso: llamar a [next] hasta [Event.EOF]. Tras `START`, [name] y [attribute]; tras `TEXT`, [text]; tras `END`, [name]. El texto de
 * solo espacios entre elementos no se entrega. El contenido de [text] es de este lector y cambia con el evento siguiente.
 */
class XmlStreamReader(input: InputStream) {
    enum class Event { START, END, TEXT, EOF }

    /** El nombre local del elemento del último `START` o `END`. */
    var name: String = ""
        private set

    /** El texto del último `TEXT`, con las entidades ya resueltas. */
    val text: CharSequence get() = textBuffer

    /** La profundidad tras el último evento: la de dentro del elemento tras un `START`, la de fuera tras un `END`. */
    var depth: Int = 0
        private set

    /** La línea, desde 1, por la que va la lectura. */
    var line: Int = 1
        private set

    private val reader: Reader
    private val buffer = CharArray(BUFFER_CHARS)
    private var position = 0
    private var limit = 0
    private val textBuffer = StringBuilder()
    private val scratch = StringBuilder()
    private val stack = ArrayList<String>()
    private var attributeNames = arrayOfNulls<String>(INITIAL_ATTRIBUTES)
    private var attributeValues = arrayOfNulls<String>(INITIAL_ATTRIBUTES)
    private var attributeCount = 0
    private var pendingEnd = false
    private var markupPending = false
    private var rootSeen = false
    private var rootClosed = false

    init {
        val stream = BufferedInputStream(input, BUFFER_CHARS)
        reader = InputStreamReader(stream, detectCharset(stream))
    }

    /** El valor del atributo [attributeName] del último `START`, o null si no lo trae. */
    fun attribute(attributeName: String): String? {
        for (i in 0 until attributeCount) if (attributeNames[i] == attributeName) return attributeValues[i]
        return null
    }

    /** Salta el elemento recién abierto con todo lo que contiene, hasta su `END` (inclusive). Se llama justo tras un `START`. */
    fun skipElement() {
        val target = depth - 1
        while (depth > target) {
            if (next() == Event.EOF) return
        }
    }

    /** El siguiente evento. [XmlFormatException] si el XML no está bien formado. */
    fun next(): Event {
        if (pendingEnd) {
            pendingEnd = false
            return closeElement(name)
        }
        textBuffer.setLength(0)
        var blank = true
        var resumeMarkup = markupPending
        markupPending = false
        while (true) {
            val c = if (resumeMarkup) '<'.code.also { resumeMarkup = false } else read()
            when {
                c == -1 -> {
                    if (stack.isNotEmpty()) fail("El XML está cortado: falta cerrar <${stack.last()}>")
                    if (!rootSeen) fail("No es un XML")
                    return Event.EOF
                }

                c == '<'.code -> {
                    if (!blank) {
                        markupPending = true
                        return Event.TEXT
                    }
                    textBuffer.setLength(0)
                    val event = markup()
                    if (event != null) return event
                    // Un comentario, una instrucción o un DOCTYPE: se sigue leyendo; un CDATA deja texto y pone `blank` a falso.
                    if (textBuffer.isNotEmpty()) blank = false
                }

                c == '&'.code -> {
                    if (stack.isEmpty()) fail("No es un XML")
                    appendEntity()
                    blank = false
                }

                else -> {
                    if (c.toChar().isWhitespace()) {
                        if (stack.isNotEmpty()) textBuffer.append(c.toChar())
                    } else {
                        if (stack.isEmpty()) fail(if (rootSeen) "Hay texto después del elemento raíz" else "No es un XML")
                        textBuffer.append(c.toChar())
                        blank = false
                    }
                }
            }
        }
    }

    /** Lo que sigue a un `<`. Devuelve el evento si lo es, o null si era algo que se salta o un `CDATA`. */
    private fun markup(): Event? {
        val c = read()
        return when (c) {
            -1 -> fail("El XML está cortado dentro de una etiqueta")

            '?'.code -> {
                skipUntil("?>")
                null
            }

            '!'.code -> {
                bang()
                null
            }

            '/'.code -> endTag()

            else -> startTag(c)
        }
    }

    private fun bang() {
        val first = read()
        if (first == '-'.code) {
            if (read() != '-'.code) fail("Comentario mal formado")
            skipUntil("-->")
        } else if (first == '['.code) {
            for (expected in "CDATA[") if (read() != expected.code) fail("Sección CDATA mal formada")
            if (stack.isEmpty()) fail("No es un XML")
            readCdata()
        } else {
            skipDeclaration()
        }
    }

    /** Un `<!DOCTYPE …>`, con su subconjunto interno entre corchetes y las comillas, hasta el `>` que lo cierra. */
    private fun skipDeclaration() {
        var brackets = 0
        var quote = -1
        while (true) {
            val c = read()
            when {
                c == -1 -> fail("El XML está cortado dentro de una declaración")
                quote != -1 -> if (c == quote) quote = -1
                c == '"'.code || c == '\''.code -> quote = c
                c == '['.code -> brackets++
                c == ']'.code -> brackets--
                c == '>'.code && brackets <= 0 -> return
            }
        }
    }

    private fun readCdata() {
        var closing = 0
        while (true) {
            val c = read()
            if (c == -1) fail("El XML está cortado dentro de un CDATA")
            when {
                c == ']'.code -> closing++
                c == '>'.code && closing >= 2 -> {
                    repeat(closing - 2) { textBuffer.append(']') }
                    return
                }

                else -> {
                    repeat(closing) { textBuffer.append(']') }
                    closing = 0
                    textBuffer.append(c.toChar())
                }
            }
        }
    }

    private fun skipUntil(terminator: String) {
        var matched = 0
        while (matched < terminator.length) {
            val c = read()
            if (c == -1) fail("El XML está cortado, falta '$terminator'")
            matched = when {
                c == terminator[matched].code -> matched + 1
                c == terminator[0].code -> 1
                else -> 0
            }
        }
    }

    private fun endTag(): Event {
        scratch.setLength(0)
        var c = read()
        while (c != -1 && c != '>'.code && !c.toChar().isWhitespace()) {
            scratch.append(c.toChar())
            c = read()
        }
        while (c != -1 && c != '>'.code) {
            if (!c.toChar().isWhitespace()) fail("Etiqueta de cierre mal formada")
            c = read()
        }
        if (c == -1) fail("El XML está cortado dentro de una etiqueta")
        val closed = local(scratch)
        if (stack.isEmpty() || stack.last() != closed) {
            fail("La etiqueta </$closed> no cierra ${if (stack.isEmpty()) "ninguna" else "<${stack.last()}>"}")
        }
        return closeElement(closed)
    }

    private fun closeElement(closed: String): Event {
        name = closed
        stack.removeAt(stack.lastIndex)
        depth = stack.size
        if (depth == 0) rootClosed = true
        return Event.END
    }

    private fun startTag(first: Int): Event {
        if (rootClosed) fail("Hay más de un elemento raíz")
        scratch.setLength(0)
        var c = first
        while (c != -1 && c != '>'.code && c != '/'.code && !c.toChar().isWhitespace()) {
            scratch.append(c.toChar())
            c = read()
        }
        if (scratch.isEmpty()) fail("Etiqueta sin nombre")
        val opened = local(scratch)
        attributeCount = 0
        var selfClosing = false
        while (true) {
            while (c != -1 && c.toChar().isWhitespace()) c = read()
            when (c) {
                -1 -> fail("El XML está cortado dentro de la etiqueta <$opened>")

                '>'.code -> break

                '/'.code -> {
                    if (read() != '>'.code) fail("Etiqueta <$opened/ mal formada")
                    selfClosing = true
                    break
                }

                else -> c = readAttribute(c, opened)
            }
        }
        rootSeen = true
        name = opened
        stack.add(opened)
        depth = stack.size
        pendingEnd = selfClosing
        return Event.START
    }

    /** Un atributo que empieza en [first]; devuelve el carácter que sigue a su valor. */
    private fun readAttribute(first: Int, element: String): Int {
        scratch.setLength(0)
        var c = first
        while (c != -1 && c != '='.code && !c.toChar().isWhitespace() && c != '>'.code && c != '/'.code) {
            scratch.append(c.toChar())
            c = read()
        }
        val key = local(scratch)
        while (c != -1 && c.toChar().isWhitespace()) c = read()
        if (c != '='.code) fail("El atributo $key de <$element> no tiene valor")
        c = read()
        while (c != -1 && c.toChar().isWhitespace()) c = read()
        if (c != '"'.code && c != '\''.code) fail("El valor del atributo $key de <$element> no está entre comillas")
        val quote = c
        val value = StringBuilder()
        while (true) {
            c = read()
            when (c) {
                -1 -> fail("El XML está cortado dentro del atributo $key")
                quote -> break
                '&'.code -> value.append(entity())
                '<'.code -> fail("El valor del atributo $key de <$element> contiene '<'")
                else -> value.append(c.toChar())
            }
        }
        if (attributeCount == attributeNames.size) {
            attributeNames = attributeNames.copyOf(attributeCount * 2)
            attributeValues = attributeValues.copyOf(attributeCount * 2)
        }
        attributeNames[attributeCount] = key
        attributeValues[attributeCount] = value.toString()
        attributeCount++
        return read()
    }

    private fun appendEntity() {
        textBuffer.append(entity())
    }

    /** Una entidad, ya leído el `&`: las cinco predefinidas y las numéricas decimales y hexadecimales. */
    private fun entity(): String {
        scratch.setLength(0)
        while (true) {
            val c = read()
            if (c == -1) fail("El XML está cortado dentro de una entidad")
            if (c == ';'.code) break
            scratch.append(c.toChar())
            if (scratch.length > MAX_ENTITY) fail("Entidad demasiado larga")
        }
        val body = scratch.toString()
        return when {
            body == "lt" -> "<"
            body == "gt" -> ">"
            body == "amp" -> "&"
            body == "quot" -> "\""
            body == "apos" -> "'"
            body.startsWith("#x") || body.startsWith("#X") -> codePoint(body.substring(2), 16, body)
            body.startsWith("#") -> codePoint(body.substring(1), 10, body)
            else -> fail("Entidad desconocida &$body;")
        }
    }

    private fun codePoint(digits: String, radix: Int, whole: String): String {
        val value = digits.toIntOrNull(radix)
        if (value == null || !Character.isValidCodePoint(value)) fail("Entidad numérica imposible &$whole;")
        return String(Character.toChars(value))
    }

    private fun local(qualified: CharSequence): String {
        val colon = qualified.lastIndexOf(':')
        return if (colon < 0) qualified.toString() else qualified.substring(colon + 1)
    }

    private fun read(): Int {
        if (position == limit) {
            limit = reader.read(buffer, 0, buffer.size)
            position = 0
            if (limit <= 0) {
                limit = 0
                return -1
            }
        }
        val c = buffer[position++]
        if (c == '\n') line++
        return c.code
    }

    private fun fail(message: String): Nothing = throw XmlFormatException(message, line)

    private companion object {
        const val BUFFER_CHARS = 64 * 1024
        const val INITIAL_ATTRIBUTES = 8
        const val MAX_ENTITY = 16
        const val SNIFF_BYTES = 256

        /** La codificación por el BOM o por `encoding="…"` de la declaración; UTF-8 si no hay ninguna. */
        fun detectCharset(stream: BufferedInputStream): Charset {
            stream.mark(SNIFF_BYTES)
            val head = ByteArray(SNIFF_BYTES)
            var n = 0
            while (n < head.size) {
                val r = stream.read(head, n, head.size - n)
                if (r <= 0) break
                n += r
            }
            stream.reset()
            if (n >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) {
                stream.skip(3)
                return Charsets.UTF_8
            }
            if (n >= 2 &&
                ((head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()) || (head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()))
            ) {
                return Charsets.UTF_16
            }
            val declaration = String(head, 0, n, Charsets.ISO_8859_1)
            val match = Regex("""^\s*<\?xml[^>]*\sencoding\s*=\s*["']([^"']+)["']""").find(declaration) ?: return Charsets.UTF_8
            return try {
                Charset.forName(match.groupValues[1])
            } catch (e: IllegalCharsetNameException) {
                throw XmlFormatException("Codificación no admitida: ${match.groupValues[1]}", 1)
            } catch (e: UnsupportedCharsetException) {
                throw XmlFormatException("Codificación no admitida: ${match.groupValues[1]}", 1)
            }
        }
    }
}
