package es.topophone.core.model.format

import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.LinearUnit

/** Qué es lo que se escribe y de qué rango: un azimut o un ángulo de 0 a 360° (400 gon, sin llegar), una deflexión de 0 a 180° (200 gon). */
enum class DirectionKind(val maxDeg: Double, val maxGon: Double, val includesMax: Boolean) {
    AZIMUTH(360.0, 400.0, false),
    ANGLE(360.0, 400.0, false),
    DEFLECTION(180.0, 200.0, true),
}

/**
 * Azimuts, ángulos y deflexiones de COGO escritos en la unidad angular del proyecto (S038/RF-4): grados decimales, grados, minutos y
 * segundos, o gon, con coma o punto decimal. No admite signo, letras de hemisferio ni lo que se salga del rango de [DirectionKind].
 */
object DirectionInput {
    /** El valor de [text] en radianes; null si no es un número o se sale del rango. */
    fun parse(text: String, unit: AngularUnit, kind: DirectionKind): Double? {
        val value = AngleInput.magnitude(text, unit) ?: return null
        val max = if (unit == AngularUnit.GON) kind.maxGon else kind.maxDeg
        if (value > max || (value == max && !kind.includesMax)) return null
        return if (unit == AngularUnit.GON) value * Math.PI / GON_PER_HALF_TURN else Math.toRadians(value)
    }

    private const val GON_PER_HALF_TURN = 200.0
}

/** Distancias y desniveles de COGO escritos en la unidad lineal del proyecto (S038/RF-4, RF-11). */
object DistanceInput {
    /** La distancia de [text] en metros; null si no es un número o es negativa. */
    fun parse(text: String, unit: LinearUnit): Double? {
        val value = NumberInput.parse(text) ?: return null
        return if (value < 0.0 || !value.isFinite()) null else unit.toMetres(value + 0.0)
    }

    /** Lo mismo con signo, para un desnivel. */
    fun parseSigned(text: String, unit: LinearUnit): Double? = NumberInput.parse(text)?.takeIf { it.isFinite() }?.let(unit::toMetres)
}
