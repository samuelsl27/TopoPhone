package es.topophone.core.formats.codes

import es.topophone.core.formats.csv.CsvExport
import es.topophone.core.formats.csv.ExportFileName
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeLibrary
import es.topophone.core.model.codes.CodePalette
import es.topophone.core.model.codes.CodeSearch
import java.time.Instant
import java.time.ZoneId

/**
 * El formato del CSV de una biblioteca (S023/RF-13, RF-17): cabeceras en inglés (no dependen del idioma de la app), los tipos de
 * geometría y de atributo por su nombre y las opciones de una Lista separadas por `|` (D8).
 */
object CodeCsvFormat {
    const val CODE = "Code"
    const val DESCRIPTION = "Description"
    const val TYPE = "Type"
    const val COLOR = "Color"

    fun attributeHeader(n: Int, field: String) = "Attribute_${n}_$field"

    const val NAME_FIELD = "Name"
    const val TYPE_FIELD = "Type"
    const val DEFAULT_FIELD = "Default"
    const val OPTIONS_FIELD = "Options"

    val GEOMETRIES = mapOf(CodeGeometry.POINT to "Point", CodeGeometry.LINE to "Line", CodeGeometry.POLYGON to "Polygon")
    val ATTRIBUTE_TYPES = mapOf(
        AttributeType.TEXT to "Text",
        AttributeType.INTEGER to "Integer",
        AttributeType.NUMBER to "Number",
        AttributeType.LIST to "List",
    )

    private const val SEPARATOR = '|'
    private const val ESCAPE = '\\'

    /** Las opciones en una sola celda: `|` entre ellas, y delante de una `|` o una `\` de una opción, una `\` (D8). */
    fun encodeOptions(options: List<String>): String =
        options.joinToString(SEPARATOR.toString()) { option -> option.replace("\\", "\\\\").replace("|", "\\|") }

    /** Lo contrario de [encodeOptions]; una celda vacía no tiene opciones. */
    fun decodeOptions(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val options = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == ESCAPE && i + 1 < text.length -> current.append(text[++i])
                c == SEPARATOR -> {
                    options += current.toString()
                    current.setLength(0)
                }
                else -> current.append(c)
            }
            i++
        }
        options += current.toString()
        return options
    }
}

/** Escribe una biblioteca como CSV: UTF-8, coma, CRLF, punto decimal y las comillas de S012/RF-15 (S023/RF-17, RF-18). */
object CodeLibraryCsvWriter {
    private const val SEPARATOR = ','

    fun write(library: CodeLibrary, out: Appendable) {
        val codes = CodeSearch.index(library.codes).filter("")
        val attributeColumns = codes.maxOfOrNull { it.attributes.size } ?: 0
        val header = mutableListOf(CodeCsvFormat.CODE, CodeCsvFormat.DESCRIPTION, CodeCsvFormat.TYPE, CodeCsvFormat.COLOR)
        for (n in 1..attributeColumns) {
            listOf(CodeCsvFormat.NAME_FIELD, CodeCsvFormat.TYPE_FIELD, CodeCsvFormat.DEFAULT_FIELD, CodeCsvFormat.OPTIONS_FIELD)
                .forEach { header += CodeCsvFormat.attributeHeader(n, it) }
        }
        line(out, header)
        for (code in codes) {
            val row = mutableListOf(
                code.code,
                code.description,
                CodeCsvFormat.GEOMETRIES.getValue(code.geometry),
                CodePalette.format(code.color),
            )
            for (n in 0 until attributeColumns) {
                val attribute = code.attributes.getOrNull(n)
                if (attribute == null) {
                    row += listOf("", "", "", "")
                } else {
                    row += attribute.name
                    row += CodeCsvFormat.ATTRIBUTE_TYPES.getValue(attribute.type)
                    row += attribute.defaultValue.orEmpty()
                    row += CodeCsvFormat.encodeOptions(attribute.options)
                }
            }
            line(out, row)
        }
    }

    /** El nombre propuesto: el de la biblioteca, `codes` y la fecha y hora local (S023/RF-17). */
    fun fileName(libraryName: String, at: Instant, zone: ZoneId): String = ExportFileName.of(libraryName, "codes", at, zone)

    private fun line(out: Appendable, cells: List<String>) {
        out.append(cells.joinToString(SEPARATOR.toString()) { CsvExport.quoted(it, SEPARATOR) }).append(CsvExport.LINE_END)
    }
}
