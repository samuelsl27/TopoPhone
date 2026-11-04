package es.topophone.data.line

import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointAttribute
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.line.HistoryVertex
import es.topophone.core.model.line.LineChange
import es.topophone.core.model.line.LineHistoryEntry
import es.topophone.core.model.line.LineSnapshot
import es.topophone.core.model.line.LineVertex
import es.topophone.core.model.line.ProjectLine

private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E = enumValues<E>().firstOrNull { it.name == name } ?: default

internal fun lineCode(
    code: String?,
    description: String?,
    color: Int?,
    symbol: String?,
    attributes: List<LineAttributeEntity>,
): PointCode? {
    if (code == null) return null
    val values = attributes.sortedBy { it.ordinal }.map { PointAttribute(it.name, enumOr(it.type, AttributeType.TEXT), it.value) }
    return PointCode(code, description.orEmpty(), color ?: 0, enumOr(symbol, CodeSymbol.CIRCLE), values)
}

internal fun ProjectLineEntity.toLine(vertices: List<LineVertexEntity>, attributes: List<LineAttributeEntity>): ProjectLine = ProjectLine(
    id = id,
    projectId = projectId,
    name = name,
    code = lineCode(code, codeDescription, codeColor, codeSymbol, attributes),
    geometry = enumOr(geometry, CodeGeometry.LINE),
    closed = closed,
    hidden = hidden,
    active = active,
    vertices = vertices.sortedBy { it.ordinal }.map { LineVertex(it.pointId) },
    createdAtMs = createdAt,
    deletedAtMs = deletedAt,
)

internal fun ProjectLine.toEntity(trashKey: String = ""): ProjectLineEntity = ProjectLineEntity(
    id = id,
    projectId = projectId,
    name = name,
    geometry = geometry.name,
    closed = closed,
    hidden = hidden,
    active = active,
    code = code?.code,
    codeDescription = code?.description,
    codeColor = code?.color,
    codeSymbol = code?.symbol?.name,
    createdAt = createdAtMs,
    deletedAt = deletedAtMs,
    trashKey = trashKey,
)

internal fun ProjectLine.vertexEntities(): List<LineVertexEntity> = vertices.mapIndexed { i, v -> LineVertexEntity(id, i, v.pointId) }

internal fun ProjectLine.attributeEntities(): List<LineAttributeEntity> =
    code?.attributes.orEmpty().mapIndexed { i, a -> LineAttributeEntity(id, i, a.name, a.type.name, a.value) }

internal fun LineHistoryEntity.toEntry(
    attributes: List<LineHistoryAttributeEntity>,
    vertices: List<LineHistoryVertexEntity>,
): LineHistoryEntry = LineHistoryEntry(
    changedAtMs = changedAt,
    change = enumOr(change, LineChange.VERTICES),
    previous = LineSnapshot(
        name = name,
        code = if (code == null) {
            null
        } else {
            PointCode(
                code,
                codeDescription.orEmpty(),
                codeColor ?: 0,
                enumOr(codeSymbol, CodeSymbol.CIRCLE),
                attributes.sortedBy { it.ordinal }.map { PointAttribute(it.name, enumOr(it.type, AttributeType.TEXT), it.value) },
            )
        },
        closed = closed,
        vertices = vertices.sortedBy { it.ordinal }.map { HistoryVertex(it.pointId, it.pointName) },
    ),
)
