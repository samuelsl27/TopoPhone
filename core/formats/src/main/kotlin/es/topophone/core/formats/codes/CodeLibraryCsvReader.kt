package es.topophone.core.formats.codes

import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.AttributeValues
import es.topophone.core.model.codes.CodeAttribute
import es.topophone.core.model.codes.CodeError
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodePalette
import es.topophone.core.model.codes.CodeValidator
import es.topophone.core.model.codes.SurveyCode
import es.topophone.core.model.point.DelimitedRow
import es.topophone.core.model.point.ImportDecimal
import java.util.UUID

/** Por qué no se importa una fila (S023/RF-15). Los de atributo llevan su número en la cabecera (`Attribute_N_*`). */
sealed interface RowProblem {
    /** El código no vale (vacío, demasiado largo o con salto de línea). */
    data class Code(val error: CodeError) : RowProblem

    data class UnknownType(val text: String) : RowProblem

    data class UnknownColor(val text: String) : RowProblem

    data class UnknownAttributeType(val number: Int, val text: String) : RowProblem

    /** El atributo [number] de la cabecera no vale; [error] lleva su posición entre los atributos de la fila. */
    data class Attribute(val number: Int, val error: CodeError) : RowProblem
}

/** Un código leído y la línea del fichero de la que sale. */
data class ParsedCode(val line: Int, val code: SurveyCode)

data class InvalidRow(val line: Int, val problem: RowProblem)

data class CodeCsvParse(val codes: List<ParsedCode>, val invalid: List<InvalidRow>)

sealed interface CodeCsvResult {
    /** Falta la columna `Code`: no se importa nada (S023/RF-13). */
    data object MissingCode : CodeCsvResult

    data class Parsed(val parse: CodeCsvParse) : CodeCsvResult
}

/**
 * Lee las filas de un CSV de biblioteca (S023/RF-12, RF-13, RF-15, D13). La primera fila es la cabecera, sin distinguir mayúsculas ni
 * espacios; las columnas desconocidas se ignoran y los atributos `Attribute_N_*` pueden saltarse números. Sin `Type` el código es un
 * Punto y sin `Color` toma el de su geometría. Los repetidos no se miran aquí (los trata `CodeMerge`).
 */
object CodeLibraryCsvReader {
    private val ATTRIBUTE_HEADER = Regex("""attribute_(\d+)_(name|type|default|options)""")

    private sealed interface Converted {
        class Ok(val code: SurveyCode) : Converted

        class Bad(val problem: RowProblem) : Converted
    }

    private class AttributeColumns(val number: Int, val name: Int?, val type: Int?, val default: Int?, val options: Int?)

    fun parse(rows: Sequence<DelimitedRow>, decimal: ImportDecimal, newId: () -> String = { UUID.randomUUID().toString() }): CodeCsvResult {
        val iterator = rows.iterator()
        if (!iterator.hasNext()) return CodeCsvResult.MissingCode
        val header = iterator.next().cells.map { it.trim().lowercase() }
        val codeColumn = header.indexOf("code").takeIf { it >= 0 } ?: return CodeCsvResult.MissingCode
        val descriptionColumn = header.indexOf("description").takeIf { it >= 0 }
        val typeColumn = header.indexOf("type").takeIf { it >= 0 }
        val colorColumn = header.indexOf("color").takeIf { it >= 0 }
        val attributes = attributeColumns(header)

        val codes = mutableListOf<ParsedCode>()
        val invalid = mutableListOf<InvalidRow>()
        for (row in iterator) {
            fun cell(column: Int?): String = column?.let { row.cells.getOrNull(it) }.orEmpty().trim()
            val problem = convert(row, ::cell, codeColumn, descriptionColumn, typeColumn, colorColumn, attributes, decimal, newId)
            when (problem) {
                is Converted.Ok -> codes += ParsedCode(row.line, problem.code)
                is Converted.Bad -> invalid += InvalidRow(row.line, problem.problem)
            }
        }
        return CodeCsvResult.Parsed(CodeCsvParse(codes, invalid))
    }

    private fun attributeColumns(header: List<String>): List<AttributeColumns> {
        val found = sortedMapOf<Int, MutableMap<String, Int>>()
        header.forEachIndexed { index, name ->
            val match = ATTRIBUTE_HEADER.matchEntire(name.replace(" ", "")) ?: return@forEachIndexed
            found.getOrPut(match.groupValues[1].toInt()) { mutableMapOf() }.putIfAbsent(match.groupValues[2], index)
        }
        return found.map { (number, columns) ->
            AttributeColumns(number, columns["name"], columns["type"], columns["default"], columns["options"])
        }
    }

    @Suppress("LongParameterList", "ReturnCount")
    private fun convert(
        row: DelimitedRow,
        cell: (Int?) -> String,
        codeColumn: Int,
        descriptionColumn: Int?,
        typeColumn: Int?,
        colorColumn: Int?,
        attributeColumns: List<AttributeColumns>,
        decimal: ImportDecimal,
        newId: () -> String,
    ): Converted {
        val code = cell(codeColumn)
        CodeValidator.validateFormat(code)?.let { return Converted.Bad(RowProblem.Code(it)) }
        val typeText = cell(typeColumn)
        val geometry = if (typeText.isEmpty()) {
            CodeGeometry.POINT
        } else {
            CodeCsvFormat.GEOMETRIES.entries.firstOrNull { it.value.equals(typeText, ignoreCase = true) }?.key
                ?: return Converted.Bad(RowProblem.UnknownType(typeText))
        }
        val colorText = cell(colorColumn)
        val color = if (colorText.isEmpty()) {
            CodePalette.defaultFor(geometry)
        } else {
            CodePalette.parse(colorText) ?: return Converted.Bad(RowProblem.UnknownColor(colorText))
        }

        val attributes = mutableListOf<CodeAttribute>()
        val numbers = mutableListOf<Int>()
        for (columns in attributeColumns) {
            val name = cell(columns.name)
            val typeCell = cell(columns.type)
            val defaultCell = cell(columns.default)
            val optionsCell = cell(columns.options)
            if (listOf(name, typeCell, defaultCell, optionsCell).all { it.isEmpty() }) continue
            val options = CodeCsvFormat.decodeOptions(optionsCell)
            val type = when {
                typeCell.isEmpty() -> if (options.isEmpty()) AttributeType.TEXT else AttributeType.LIST
                else -> CodeCsvFormat.ATTRIBUTE_TYPES.entries.firstOrNull { it.value.equals(typeCell, ignoreCase = true) }?.key
                    ?: return Converted.Bad(RowProblem.UnknownAttributeType(columns.number, typeCell))
            }
            val default = if (defaultCell.isEmpty()) {
                null
            } else {
                // Un valor que no vale se deja tal cual: el validador lo rechaza con el motivo y la posición.
                AttributeValues.canonical(type, defaultCell, decimal.char) ?: defaultCell
            }
            attributes += CodeAttribute(name, type, default, if (type == AttributeType.LIST) options else emptyList())
            numbers += columns.number
        }
        CodeValidator.validateAttributes(attributes)?.let { error ->
            val index = when (error) {
                is CodeError.AttributeName -> error.index
                is CodeError.ListOptions -> error.index
                is CodeError.DefaultValue -> error.index
                else -> 0
            }
            return Converted.Bad(RowProblem.Attribute(numbers[index.coerceIn(numbers.indices)], error))
        }
        val description = cell(descriptionColumn)
        return Converted.Ok(SurveyCode(newId(), code, description, geometry, color, attributes))
    }
}
