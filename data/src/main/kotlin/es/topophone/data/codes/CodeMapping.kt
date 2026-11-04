package es.topophone.data.codes

import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeAttribute
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeKey
import es.topophone.core.model.codes.CodeLibrary
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.SurveyCode

/** Une las filas de una biblioteca en su modelo (S023, plan D2): los códigos por su clave y los atributos y opciones por su posición. */
internal fun assembleLibrary(
    library: CodeLibraryEntity,
    codes: List<LibraryCodeEntity>,
    attributes: List<CodeAttributeEntity>,
    options: List<AttributeOptionEntity>,
): CodeLibrary {
    val optionsByAttribute = options.groupBy { it.attributeId }
    val attributesByCode = attributes.groupBy { it.codeId }
    return CodeLibrary(
        id = library.id,
        name = library.name,
        isDefault = false,
        codes = codes.sortedBy { it.codeKey }.map { code ->
            SurveyCode(
                id = code.id,
                code = code.code,
                description = code.description,
                geometry = CodeGeometry.valueOf(code.geometry),
                color = code.color,
                symbol = CodeSymbol.entries.firstOrNull { it.name == code.symbol } ?: CodeSymbol.CIRCLE,
                attributes = attributesByCode[code.id].orEmpty().sortedBy { it.ordinal }.map { attribute ->
                    CodeAttribute(
                        name = attribute.name,
                        type = AttributeType.valueOf(attribute.type),
                        defaultValue = attribute.defaultValue,
                        options = optionsByAttribute[attribute.id].orEmpty().sortedBy { it.ordinal }.map { it.value },
                    )
                },
            )
        },
    )
}

/** Las filas de [code] dentro de [libraryId], con ids nuevos para sus atributos (S023, D2). */
internal fun SurveyCode.toRows(libraryId: String, newId: () -> String): CodeRows {
    val codeRow = LibraryCodeEntity(id, libraryId, code.trim(), CodeKey.of(code), description, geometry.name, color, symbol.name)
    val attributeRows = mutableListOf<CodeAttributeEntity>()
    val optionRows = mutableListOf<AttributeOptionEntity>()
    attributes.forEachIndexed { index, attribute ->
        val attributeId = newId()
        attributeRows += CodeAttributeEntity(attributeId, id, index, attribute.name.trim(), attribute.type.name, attribute.defaultValue)
        if (attribute.type == AttributeType.LIST) {
            attribute.options.forEachIndexed { position, value -> optionRows += AttributeOptionEntity(attributeId, position, value) }
        }
    }
    return CodeRows(codeRow, attributeRows, optionRows)
}

internal class CodeRows(val code: LibraryCodeEntity, val attributes: List<CodeAttributeEntity>, val options: List<AttributeOptionEntity>)

/** Una biblioteca del usuario o la copia de un proyecto con todos sus códigos, atributos y opciones; null si no existe (S023, D2). */
internal suspend fun CodeLibraryDao.loadLibrary(id: String): CodeLibrary? {
    val library = library(id) ?: return null
    return assembleLibrary(library, codes(id), attributes(id), options(id))
}

/** Inserta [library] con [codes], cada uno con un id nuevo, dentro de la transacción que llama. */
internal suspend fun CodeLibraryDao.insertTree(library: CodeLibraryEntity, codes: List<SurveyCode>, newId: () -> String) {
    insertLibrary(library)
    val rows = codes.map { it.copy(id = newId()).toRows(library.id, newId) }
    insertCodes(rows.map { it.code })
    insertAttributes(rows.flatMap { it.attributes })
    insertOptions(rows.flatMap { it.options })
}
