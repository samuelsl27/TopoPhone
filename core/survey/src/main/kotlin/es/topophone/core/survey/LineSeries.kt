package es.topophone.core.survey

import es.topophone.core.model.format.NumberInput
import es.topophone.core.model.point.LineInputError
import kotlin.math.abs
import kotlin.math.ceil

/** El resultado de comprobar un valor escrito en el replanteo de una línea: el valor, o el motivo por el que no se aplica (S041/RF-21). */
sealed interface LineCheck<out T> {
    data class Valid<T>(val value: T) : LineCheck<T>

    data class Invalid(val error: LineInputError) : LineCheck<Nothing>
}

/** Un punto de la serie de intervalos: su número desde 0, su PK, el punto de diseño y su cota (S041/RF-18, RF-20, RF-23). */
data class SeriesPoint(val index: Int, val chainageM: Double, val point: PlanePoint, val heightM: Double?)

/**
 * Los valores escritos del replanteo de una línea: el desplazamiento, el intervalo y los PK de A y de B (S041/RF-16, RF-21). Todo
 * en metros de cuadrícula; la conversión a las unidades del proyecto se hace fuera.
 */
object LineInputs {
    /** Un número escrito con punto o con coma decimal, o null si no lo es o no es finito (RF-21). */
    fun parse(text: String): Double? = NumberInput.parse(text)?.takeIf { it.isFinite() }

    /** El desplazamiento lateral: cualquier número, positivo a la derecha y negativo a la izquierda (RF-11). */
    fun offset(text: String): LineCheck<Double> = parse(text)?.let { LineCheck.Valid(it) } ?: LineCheck.Invalid(LineInputError.NOT_A_NUMBER)

    /** El intervalo de la serie: un número mayor que 0 (RF-21). */
    fun interval(text: String): LineCheck<Double> {
        val value = parse(text) ?: return LineCheck.Invalid(LineInputError.NOT_A_NUMBER)
        return if (value > 0.0) LineCheck.Valid(value) else LineCheck.Invalid(LineInputError.INTERVAL_NOT_POSITIVE)
    }

    /** Los PK de A y de B: números dentro de la línea y distintos (RF-16, RF-21). */
    fun range(line: StakeLine, aText: String, bText: String): LineCheck<LineRange> {
        val a = parse(aText)
        val b = parse(bText)
        return range(line, a, b)
    }

    fun range(line: StakeLine, a: Double?, b: Double?): LineCheck<LineRange> {
        if (a == null || b == null) return LineCheck.Invalid(LineInputError.NOT_A_NUMBER)
        if (a < 0.0 || b < 0.0 || a > line.lengthM || b > line.lengthM) return LineCheck.Invalid(LineInputError.OUT_OF_LINE)
        if (a == b) return LineCheck.Invalid(LineInputError.SAME_PK)
        return LineCheck.Valid(LineRange(a, b))
    }

    /** "Mi posición": el PK del punto más cercano a la punta, que pasa a ser A (RF-16). */
    fun here(location: LineLocation): Double = location.chainageM
}

/** "Invertir": A pasa a ser B y B pasa a ser A (RF-16). */
fun LineRange.inverted() = LineRange(endM, startM)

/**
 * La serie de puntos a intervalos de [range] (S041/RF-18, RF-19, RF-20): A, A más un intervalo, más dos intervalos y así hasta B,
 * que siempre se incluye aunque no caiga en un múltiplo; con un intervalo igual o mayor que el tramo, solo A y B. Va de A hacia
 * B, también si A es mayor que B. Cada punto está sobre la línea o sobre la paralela a [offsetM]. Los puntos se calculan al pedirlos:
 * un intervalo diminuto no construye una lista enorme.
 */
class LineSeries(private val line: StakeLine, val range: LineRange, val intervalM: Double, val offsetM: Double) {
    private val span = abs(range.endM - range.startM)
    private val direction = if (range.endM >= range.startM) 1.0 else -1.0

    /** Cuántos puntos tiene la serie, B incluido. */
    val size: Int = if (intervalM >= span) {
        2
    } else {
        (ceil((span - EPSILON) / intervalM) + 1.0).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
    }

    /** El PK del punto [index]: el último es siempre B. */
    fun chainageOf(index: Int): Double {
        require(index in 0 until size)
        return if (index == size - 1) range.endM else range.startM + direction * index * intervalM
    }

    operator fun get(index: Int): SeriesPoint {
        val pk = chainageOf(index)
        return SeriesPoint(index, pk, line.pointAt(pk, offsetM), line.heightAt(pk))
    }

    /** Todos los puntos: solo para series pequeñas y pruebas. */
    fun toList(): List<SeriesPoint> = List(size) { get(it) }

    companion object {
        /** Un punto a menos de 1 nm de B no es un punto más: es B. */
        private const val EPSILON = 1e-9
    }
}
