package es.topophone.core.model.codes

import es.topophone.core.model.point.ImportDecimal
import es.topophone.core.model.point.ImportNumber
import es.topophone.core.model.project.NameError
import es.topophone.core.model.project.ProjectNames
import java.math.BigDecimal

/** Por qué no se guarda un código (S023/RF-6, RF-7, RF-9). Los de atributo llevan su posición. */
sealed interface CodeError {
    data object Empty : CodeError

    data object TooLong : CodeError

    data object NewLine : CodeError

    /** [code] es el código ya existente con el que choca. */
    data class Duplicate(val code: String) : CodeError

    data object TooManyAttributes : CodeError

    data class AttributeName(val index: Int) : CodeError

    data class ListOptions(val index: Int) : CodeError

    data class DefaultValue(val index: Int) : CodeError
}

/** La clave con la que se comparan códigos y nombres: sin mayúsculas, acentos ni espacios en los extremos (S023/RF-6). */
object CodeKey {
    fun of(text: String): String = ProjectNames.key(text)
}

/** Nombres de biblioteca (S023/RF-2, RF-14): mismas reglas que los de proyecto. */
object LibraryNames {
    fun validate(name: String, existing: Collection<String>): NameError? = ProjectNames.validate(name, existing)

    /** [base] si está libre; si no, «base (2)», «base (3)»… el primero libre. */
    fun uniqueName(base: String, existing: Collection<String>): String {
        val taken = existing.mapTo(HashSet(), CodeKey::of)
        val name = base.trim()
        if (CodeKey.of(name) !in taken) return name
        return generateSequence(2) { it + 1 }.map { "$name ($it)" }.first { CodeKey.of(it) !in taken }
    }
}

/** Los valores de los atributos: validar y dejar en texto canónico (S023/RF-9, D7). */
object AttributeValues {
    /**
     * El texto canónico de [raw] para [type], o null si no vale: Texto y Lista recortados; Entero con `toLong`; Número con el signo
     * [decimal] y sin notación científica, escrito en plano con punto decimal y sin redondear.
     */
    fun canonical(type: AttributeType, raw: String, decimal: Char): String? = when (type) {
        AttributeType.TEXT, AttributeType.LIST -> raw.trim()
        AttributeType.INTEGER -> raw.trim().toLongOrNull()?.toString()
        AttributeType.NUMBER -> {
            val sign = if (decimal == ',') ImportDecimal.COMMA else ImportDecimal.POINT
            ImportNumber.parse(raw, sign)?.takeIf { it.isFinite() }?.let { BigDecimal(it.toString()).stripTrailingZeros().toPlainString() }
        }
    }
}

object CodeValidator {
    const val MAX_LENGTH = 32
    const val MAX_ATTRIBUTES = 100
    const val MAX_OPTIONS = 50

    /** El formato del código solo (RF-7), sin mirar los demás códigos. */
    fun validateFormat(code: String): CodeError? {
        val text = code.trim()
        return when {
            text.isEmpty() -> CodeError.Empty
            text.length > MAX_LENGTH -> CodeError.TooLong
            text.any { it == '\n' || it == '\r' } -> CodeError.NewLine
            else -> null
        }
    }

    /** Un código y sus atributos frente a los [others] de su biblioteca (sin el que se edita), o el primer motivo (RF-6, RF-7, RF-9). */
    fun validate(code: String, attributes: List<CodeAttribute>, others: Collection<SurveyCode>): CodeError? {
        validateFormat(code)?.let { return it }
        val key = CodeKey.of(code)
        others.firstOrNull { CodeKey.of(it.code) == key }?.let { return CodeError.Duplicate(it.code) }
        return validateAttributes(attributes)
    }

    fun validateAttributes(attributes: List<CodeAttribute>): CodeError? {
        if (attributes.size > MAX_ATTRIBUTES) return CodeError.TooManyAttributes
        val names = HashSet<String>()
        for ((index, attribute) in attributes.withIndex()) {
            val key = CodeKey.of(attribute.name)
            if (key.isEmpty() || !names.add(key)) return CodeError.AttributeName(index)
            if (attribute.type == AttributeType.LIST && !validOptions(attribute.options)) return CodeError.ListOptions(index)
            val default = attribute.defaultValue ?: continue
            val valid = when (attribute.type) {
                AttributeType.TEXT -> true
                AttributeType.LIST -> default in attribute.options
                else -> AttributeValues.canonical(attribute.type, default, '.') != null
            }
            if (!valid) return CodeError.DefaultValue(index)
        }
        return null
    }

    private fun validOptions(options: List<String>): Boolean =
        options.size in 1..MAX_OPTIONS && options.none { it.isBlank() } && options.map(CodeKey::of).toSet().size == options.size
}
