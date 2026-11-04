package es.topophone.core.model.codes

/** Por qué no vale el valor de un atributo en el recolector (S024/RF-9). */
enum class AttributeError { NOT_INTEGER, NOT_NUMBER, NOT_IN_OPTIONS }

/** El resultado de validar el formulario: un motivo (o null) y un valor canónico por atributo. */
data class AttributeFormResult(val errors: List<AttributeError?>, val values: List<PointAttribute>) {
    val isValid: Boolean get() = errors.all { it == null }
}

/** El formulario de atributos del código elegido (S024/RF-8, RF-9). */
object AttributeForm {
    /** El texto inicial de cada campo: el valor por defecto del atributo, con el signo [decimal] de la interfaz en los números. */
    fun initial(attributes: List<CodeAttribute>, decimal: Char): List<String> = attributes.map { attribute ->
        val default = attribute.defaultValue.orEmpty()
        if (attribute.type == AttributeType.NUMBER && decimal == ',') default.replace('.', ',') else default
    }

    /** Los valores guardados de un punto como se escriben en pantalla: el número con el signo [decimal] de la interfaz (S024/RF-12). */
    fun texts(values: List<PointAttribute>, decimal: Char): List<String> =
        values.map { if (it.type == AttributeType.NUMBER && decimal == ',') it.value.replace('.', ',') else it.value }

    /**
     * Como [validate] para los valores que ya lleva un punto (S024/RF-12): se guardaron por valor y el código puede haber cambiado o
     * haber desaparecido, así que una Lista se acepta con cualquier texto.
     */
    fun validateValues(values: List<PointAttribute>, raws: List<String>, decimal: Char): AttributeFormResult {
        val definitions = values.map { CodeAttribute(it.name, if (it.type == AttributeType.LIST) AttributeType.TEXT else it.type) }
        val result = validate(definitions, raws, decimal)
        return result.copy(values = result.values.mapIndexed { i, v -> v.copy(type = values[i].type) })
    }

    /**
     * Valida un texto por atributo ([raws], en el orden de [attributes]). Un campo vacío es válido y se guarda vacío;
     * los números no se redondean (S024/RF-9, RF-10).
     */
    fun validate(attributes: List<CodeAttribute>, raws: List<String>, decimal: Char): AttributeFormResult {
        val errors = ArrayList<AttributeError?>(attributes.size)
        val values = ArrayList<PointAttribute>(attributes.size)
        attributes.forEachIndexed { index, attribute ->
            val raw = raws.getOrElse(index) { "" }
            if (raw.isBlank()) {
                errors += null
                values += PointAttribute(attribute.name, attribute.type, "")
                return@forEachIndexed
            }
            val canonical = AttributeValues.canonical(attribute.type, raw, decimal)
            val error = when {
                canonical == null && attribute.type == AttributeType.INTEGER -> AttributeError.NOT_INTEGER
                canonical == null -> AttributeError.NOT_NUMBER
                attribute.type == AttributeType.LIST && canonical !in attribute.options -> AttributeError.NOT_IN_OPTIONS
                else -> null
            }
            errors += error
            values += PointAttribute(attribute.name, attribute.type, canonical.orEmpty())
        }
        return AttributeFormResult(errors, values)
    }
}
