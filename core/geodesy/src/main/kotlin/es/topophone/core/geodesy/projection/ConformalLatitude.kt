package es.topophone.core.geodesy.projection

import es.topophone.core.geodesy.Ellipsoid
import kotlin.math.abs
import kotlin.math.atanh
import kotlin.math.sinh
import kotlin.math.sqrt

/**
 * La latitud conforme de un elipsoide con la formulación de Karney (2011), sin pérdida de precisión cerca de los polos: τ = tan φ (latitud
 * geodésica) y τ′ = tan χ (latitud conforme). Es la base de las proyecciones conformes de S032 (plan D1).
 *
 * Es una copia de las funciones privadas de `TransverseMercator`, que no se tocan para no cambiar ni un bit de UTM (S032/RF-21, plan D2).
 */
internal class ConformalLatitude(ellipsoid: Ellipsoid) {
    private val e = ellipsoid.eccentricity
    private val e2 = ellipsoid.eccentricitySquared

    /** τ′ = tan χ desde τ = tan φ (Karney 2011, ecuación 7). */
    fun tauPrime(tau: Double): Double {
        val sigma = sinh(e * atanh(e * tau / sqrt(1 + tau * tau)))
        return tau * sqrt(1 + sigma * sigma) - sigma * sqrt(1 + tau * tau)
    }

    /** τ = tan φ desde τ′ por Newton (Karney 2011, ecuaciones 19 a 21); converge en dos o tres pasos. */
    fun tau(tauPrime: Double): Double {
        var tau = tauPrime / (1 - e2)
        repeat(MAX_NEWTON_STEPS) {
            val tp = tauPrime(tau)
            val step = (tauPrime - tp) * (1 + (1 - e2) * tau * tau) / ((1 - e2) * sqrt(1 + tp * tp) * sqrt(1 + tau * tau))
            tau += step
            if (abs(step) <= NEWTON_TOLERANCE * maxOf(1.0, abs(tau))) return tau
        }
        return tau
    }

    /**
     * El `t` de EPSG: `tan(π/4 − φ/2) / ((1 − e sinφ)/(1 + e sinφ))^(e/2)`, que es `exp(−ψ)` con ψ la latitud isométrica; se calcula desde
     * τ′ por `sqrt(1 + τ′²) − τ′` (o su inversa, si τ′ es positivo) para no perder cifras cerca del polo norte.
     */
    fun t(tauPrime: Double): Double {
        val root = sqrt(1 + tauPrime * tauPrime)
        return if (tauPrime > 0) 1.0 / (root + tauPrime) else root - tauPrime
    }

    /** τ′ desde el `t` de EPSG: `(1/t − t) / 2`. */
    fun tauPrimeFromT(t: Double): Double = (1.0 / t - t) / 2.0

    private companion object {
        const val MAX_NEWTON_STEPS = 6
        const val NEWTON_TOLERANCE = 1e-14
    }
}
