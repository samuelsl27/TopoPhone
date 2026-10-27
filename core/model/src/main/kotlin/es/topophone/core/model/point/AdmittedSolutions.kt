package es.topophone.core.model.point

/** Tipo de solución de la GGA (S047): de mejor a peor, FIX, FLOAT y SINGLE. Una solución DGPS cuenta como SINGLE (S047/RF-18). */
enum class SolutionType { FIX, FLOAT, SINGLE }

/**
 * Los tipos de solución con los que la app deja medir y replantear (S047/RF-1): un conjunto de [SolutionType] que nunca está vacío
 * (RF-3). Por defecto solo FIX (RF-2).
 */
class AdmittedSolutions private constructor(val types: Set<SolutionType>) {
    /** Solo se admite FIX. */
    val onlyFix: Boolean get() = types == setOf(SolutionType.FIX)

    /** El mejor de los tipos admitidos. */
    val best: SolutionType get() = types.min()

    operator fun contains(type: SolutionType?): Boolean = type != null && type in types

    /** Con [type] admitido, sin pedir confirmación: lo que se aplica cuando el usuario ya la ha dado (RF-4). */
    fun with(type: SolutionType): AdmittedSolutions = AdmittedSolutions(types + type)

    /**
     * Lo que pasa al pulsar [type] en la elección (RF-3, RF-4): quitarlo, salvo que sea el último; añadirlo, pidiendo antes
     * confirmación si es FLOAT o SINGLE.
     */
    fun toggle(type: SolutionType): Toggle = when {
        type in types && types.size == 1 -> Toggle.LastOne
        type in types -> Toggle.Changed(AdmittedSolutions(types - type))
        type == SolutionType.FIX -> Toggle.Changed(with(type))
        else -> Toggle.NeedsConfirmation(type)
    }

    /** Los tipos separados por comas, de mejor a peor, para guardarlos (RF-5, RF-15). */
    fun encode(): String = types.sorted().joinToString(",") { it.name }

    sealed interface Toggle {
        data class Changed(val admitted: AdmittedSolutions) : Toggle

        /** Marcar este tipo exige que el usuario lo confirme (RF-4). */
        data class NeedsConfirmation(val type: SolutionType) : Toggle

        /** Es el último tipo admitido y no se puede desmarcar (RF-3). */
        data object LastOne : Toggle
    }

    override fun equals(other: Any?): Boolean = other is AdmittedSolutions && other.types == types

    override fun hashCode(): Int = types.hashCode()

    override fun toString(): String = "AdmittedSolutions(${encode()})"

    companion object {
        /** El valor por defecto (RF-2). */
        val FIX_ONLY = AdmittedSolutions(setOf(SolutionType.FIX))

        val ALL = AdmittedSolutions(SolutionType.entries.toSet())

        /** Un conjunto sin ningún tipo no existe: queda en solo FIX (RF-3). */
        fun of(types: Set<SolutionType>): AdmittedSolutions = if (types.isEmpty()) FIX_ONLY else AdmittedSolutions(types.toSet())

        /** Lo que dejó [encode]; ignora los nombres desconocidos. Null si no hay nada guardado (null); sin ningún tipo válido, solo FIX. */
        fun decode(text: String?): AdmittedSolutions? = text?.let { value ->
            val names = value.split(',').map(String::trim)
            of(SolutionType.entries.filter { it.name in names }.toSet())
        }
    }
}
