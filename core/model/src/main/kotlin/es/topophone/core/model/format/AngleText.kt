package es.topophone.core.model.format

import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.LinearUnit
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor

/**
 * Un ángulo en SI como el texto que el usuario habría escrito en la unidad del proyecto (S038/RF-27): lo que se lee de nuevo con
 * [DirectionInput]. Con punto decimal y sin exponente; en DMS, «grados minutos segundos». No es para mostrar (eso es de `AngleFormat`).
 */
object AngleText {
    private const val GON_PER_HALF_TURN = 200.0
    private const val SECOND_DECIMALS = 6
    private const val MINUTES = 60
    private const val SECONDS = 60

    fun of(radians: Double, unit: AngularUnit): String = when (unit) {
        AngularUnit.DECIMAL_DEGREES -> plain(Math.toDegrees(radians))
        AngularUnit.GON -> plain(radians * GON_PER_HALF_TURN / Math.PI)
        AngularUnit.DMS -> dms(abs(Math.toDegrees(radians)))
    }

    private fun dms(degrees: Double): String {
        var d = floor(degrees)
        var m = floor((degrees - d) * MINUTES)
        var s = BigDecimal((degrees - d - m / MINUTES) * MINUTES * SECONDS).setScale(SECOND_DECIMALS, RoundingMode.HALF_UP)
        if (s.signum() < 0) s = BigDecimal.ZERO
        if (s >= BigDecimal(SECONDS)) {
            s -= BigDecimal(SECONDS)
            m += 1
        }
        if (m >= MINUTES) {
            m -= MINUTES
            d += 1
        }
        val seconds = if (s.signum() == 0) "0" else s.stripTrailingZeros().toPlainString()
        return "${d.toLong()} ${m.toLong()} $seconds"
    }

    internal fun plain(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}

/** Una longitud en metros como el texto que el usuario habría escrito en la unidad lineal del proyecto (S038/RF-27). */
object LengthText {
    fun of(valueM: Double, unit: LinearUnit): String = AngleText.plain(unit.fromMetres(valueM))
}
