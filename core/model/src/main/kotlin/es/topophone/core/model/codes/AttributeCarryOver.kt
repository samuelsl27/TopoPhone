package es.topophone.core.model.codes

/** Los valores de un punto tras cambiar de código y los valores no vacíos que se pierden (S024/RF-14, RF-15). */
data class CarryOverResult(val values: List<PointAttribute>, val discarded: List<PointAttribute>)

object AttributeCarryOver {
    /**
     * Conserva el valor de cada atributo del código nuevo ([target]) que tenga el mismo nombre (sin mayúsculas ni acentos) y
     * el mismo tipo que uno de [old]; en los demás pone el valor por defecto. Un valor de Lista que no esté entre las opciones
     * nuevas no se conserva. [CarryOverResult.discarded] son los valores no vacíos de [old] que no pasan.
     */
    fun apply(old: List<PointAttribute>, target: List<CodeAttribute>): CarryOverResult {
        val byKey = old.associateBy { CodeKey.of(it.name) to it.type }
        val kept = HashSet<PointAttribute>()
        val values = target.map { attribute ->
            val previous = byKey[CodeKey.of(attribute.name) to attribute.type]
            val fits = previous != null &&
                (attribute.type != AttributeType.LIST || previous.value.isEmpty() || previous.value in attribute.options)
            if (previous != null && fits) {
                kept += previous
                PointAttribute(attribute.name, attribute.type, previous.value)
            } else {
                PointAttribute(attribute.name, attribute.type, attribute.defaultValue.orEmpty())
            }
        }
        return CarryOverResult(values, old.filter { it.value.isNotEmpty() && it !in kept })
    }
}
