package es.topophone.core.survey

import es.topophone.core.model.alignment.ChainageInput
import es.topophone.core.model.format.NumberInput
import es.topophone.core.model.project.LinearUnit

/** Por qué no se aplica un valor escrito en el replanteo por PK (S054/RF-1, RF-9). */
enum class AxisInputError { NOT_A_NUMBER, OUT_OF_AXIS, INTERVAL_NOT_POSITIVE }

/** El valor escrito, o el motivo por el que no se aplica (S054/RF-9); lo aplicado sigue siendo el valor anterior. */
sealed interface AxisCheck<out T> {
    data class Valid<T>(val value: T) : AxisCheck<T>

    data class Invalid(val error: AxisInputError) : AxisCheck<Nothing>
}

/**
 * Los valores escritos del replanteo por PK (S054/RF-1, RF-5, RF-9), en la unidad del proyecto; el resultado va en metros. Los
 * campos opcionales vacíos son válidos: el PK final y el intervalo dan `null`; el desplazamiento y el espesor dan 0.
 */
object AxisStakeInputs {
    /** Un PK (`1+240,5` o `1240,5`) que cabe en [geometry] (RF-9). */
    fun chainage(text: String, unit: LinearUnit, geometry: AlignmentGeometry): AxisCheck<Double> {
        val value = ChainageInput.parse(text, unit)?.takeIf { it.isFinite() } ?: return AxisCheck.Invalid(AxisInputError.NOT_A_NUMBER)
        return if (geometry.contains(value)) AxisCheck.Valid(value) else AxisCheck.Invalid(AxisInputError.OUT_OF_AXIS)
    }

    /** El PK final: vacío es válido y no hay (RF-1). */
    fun optionalChainage(text: String, unit: LinearUnit, geometry: AlignmentGeometry): AxisCheck<Double?> =
        if (text.isBlank()) AxisCheck.Valid(null) else chainage(text, unit, geometry)

    /** El desplazamiento: negativo a la izquierda, positivo a la derecha; vacío es 0 (RF-1). */
    fun offset(text: String, unit: LinearUnit): AxisCheck<Double> = number(text, unit) ?: AxisCheck.Invalid(AxisInputError.NOT_A_NUMBER)

    /** El espesor, positivo o negativo; vacío es 0 (RF-1). */
    fun thickness(text: String, unit: LinearUnit): AxisCheck<Double> = number(text, unit) ?: AxisCheck.Invalid(AxisInputError.NOT_A_NUMBER)

    /** El intervalo de PK: vacío es válido y no hay serie (RF-2); si se escribe, mayor que 0. */
    fun interval(text: String, unit: LinearUnit): AxisCheck<Double?> {
        if (text.isBlank()) return AxisCheck.Valid(null)
        val value = NumberInput.parse(text)?.takeIf { it.isFinite() } ?: return AxisCheck.Invalid(AxisInputError.NOT_A_NUMBER)
        return if (value > 0.0) AxisCheck.Valid(unit.toMetres(value)) else AxisCheck.Invalid(AxisInputError.INTERVAL_NOT_POSITIVE)
    }

    private fun number(text: String, unit: LinearUnit): AxisCheck<Double>? {
        if (text.isBlank()) return AxisCheck.Valid(0.0)
        val value = NumberInput.parse(text)?.takeIf { it.isFinite() } ?: return null
        return AxisCheck.Valid(unit.toMetres(value))
    }
}
