package es.topophone.receiver.experimental

/** Las partes del receptor que se leen y se validan por separado (S044/RF-15, plan D6). */
enum class ReachPartId { IDENTITY, DEVICE, SETTINGS, BATTERY, LOGS }

/**
 * El resultado de leer una parte: o se entiende, o es [Incompatible] con un motivo técnico que nunca lleva valores del receptor
 * (solo nombres de campos), así que se puede anotar en el log sin riesgo (RNF-T11).
 */
sealed interface ReachPart<out T> {
    data class Ok<T>(val value: T) : ReachPart<T>

    data class Incompatible(val part: ReachPartId, val reason: String) : ReachPart<Nothing>
}
