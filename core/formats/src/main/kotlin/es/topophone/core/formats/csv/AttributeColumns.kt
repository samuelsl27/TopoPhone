package es.topophone.core.formats.csv

import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeKey
import es.topophone.core.model.codes.PointAttribute
import es.topophone.core.model.point.Point
import java.util.Locale

/**
 * Una columna de un atributo de los códigos de los puntos exportados (S024/RF-21): una por cada pareja de nombre (sin mayúsculas ni
 * acentos) y tipo. [header] es el nombre del primero que la usa; si el mismo nombre sale con otro tipo, la cabecera lleva el tipo.
 */
data class AttributeColumn(val header: String, val key: String, val type: AttributeType) {
    /** El valor del [point] en esta columna, o null si su código no tiene este atributo. */
    fun valueOf(point: Point): PointAttribute? = point.code?.attributes?.firstOrNull { CodeKey.of(it.name) == key && it.type == type }
}

object AttributeColumns {
    /** Las columnas de los atributos de [points], en el orden en que aparecen por primera vez. */
    fun of(points: Iterable<Point>): List<AttributeColumn> {
        val columns = LinkedHashMap<Pair<String, AttributeType>, AttributeColumn>()
        val firstType = HashMap<String, AttributeType>()
        for (point in points) {
            for (attribute in point.code?.attributes.orEmpty()) {
                val key = CodeKey.of(attribute.name)
                columns.getOrPut(key to attribute.type) {
                    val first = firstType.getOrPut(key) { attribute.type }
                    AttributeColumn(header(attribute, first), key, attribute.type)
                }
            }
        }
        return columns.values.toList()
    }

    private fun header(attribute: PointAttribute, firstType: AttributeType): String =
        if (firstType == attribute.type) attribute.name else "${attribute.name} (${attribute.type.name.lowercase(Locale.ROOT)})"
}
