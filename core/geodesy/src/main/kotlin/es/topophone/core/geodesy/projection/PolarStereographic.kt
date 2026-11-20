package es.topophone.core.geodesy.projection

import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.PointFactors
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Estereográfica polar A (EPSG 9810, factor de escala en el polo), B (9829, paralelo estándar) y C (9830, paralelo estándar con los falsos
 * en el origen falso) sobre cualquier elipsoide (S032/RF-13; plan A5; GN 7-2 §3.3.2). Los parámetros llegan ya en metros.
 *
 * Con `s = +1` (polo norte) o `−1` (sur): `ρ = a·F·t(s·φ)`, `E = FE + ρ·sinΔ`, `N = FN − s·ρ·cosΔ`, con `t = exp(−ψ)` y F según la variante.
 */
internal object PolarStereographic {

    fun create(ellipsoid: Ellipsoid, parameters: ProjectionParameters): MapProjection = when (parameters) {
        is PolarStereographicA -> Projection(
            ellipsoid,
            pole = if (parameters.latitudeOfOriginDeg > 0) 1 else -1,
            parameters.longitudeOfOriginDeg,
            Scale.AtPole(parameters.scaleFactor),
            parameters.falseEasting,
            parameters.falseNorthing,
        )
        is PolarStereographicB -> Projection(
            ellipsoid,
            pole = if (parameters.latitudeOfStandardParallelDeg > 0) 1 else -1,
            parameters.longitudeOfOriginDeg,
            Scale.AtParallel(parameters.latitudeOfStandardParallelDeg),
            parameters.falseEasting,
            parameters.falseNorthing,
        )
        is PolarStereographicC -> Projection(
            ellipsoid,
            pole = if (parameters.latitudeOfStandardParallelDeg > 0) 1 else -1,
            parameters.longitudeOfOriginDeg,
            Scale.AtParallel(parameters.latitudeOfStandardParallelDeg),
            parameters.eastingAtFalseOrigin,
            parameters.northingAtFalseOrigin,
            falseOriginAtParallel = true,
        )
        else -> error("${parameters.method} no es una Estereográfica polar")
    }

    /** Cómo fija cada variante la escala: con el factor del polo (A) o con el paralelo estándar (B y C). */
    private sealed interface Scale {
        data class AtPole(val factor: Double) : Scale

        data class AtParallel(val latitudeDeg: Double) : Scale
    }

    private class Projection(
        ellipsoid: Ellipsoid,
        private val pole: Int,
        private val longitudeOfOriginDeg: Double,
        scale: Scale,
        falseEastingM: Double,
        falseNorthingM: Double,
        falseOriginAtParallel: Boolean = false,
    ) : MapProjection {

        private val conformal = ConformalLatitude(ellipsoid)
        private val a = ellipsoid.semiMajorAxisM
        private val e2 = ellipsoid.eccentricitySquared

        /** `a·F`: ρ = rhoPerT · t. */
        private val rhoPerT: Double

        private val falseEastingM: Double
        private val falseNorthingM: Double

        init {
            val e = ellipsoid.eccentricity
            // √((1 + e)^(1 + e) · (1 − e)^(1 − e)) = √(1 − e²)·((1 + e)/(1 − e))^(e/2)
            val c = sqrt((1 + e).pow(1 + e) * (1 - e).pow(1 - e))
            val standardParallelRho: Double
            when (scale) {
                is Scale.AtPole -> {
                    rhoPerT = 2 * a * scale.factor / c
                    standardParallelRho = 0.0
                }
                is Scale.AtParallel -> {
                    if (abs(scale.latitudeDeg) == 90.0) {
                        // Con el paralelo estándar en el propio polo, m/t tiende a 2/c: es la A con factor 1.
                        rhoPerT = 2 * a / c
                        standardParallelRho = 0.0
                    } else {
                        val phi = Math.toRadians(scale.latitudeDeg)
                        val sinPhi = sin(phi)
                        val m = cos(phi) / sqrt(1 - e2 * sinPhi * sinPhi)
                        rhoPerT = a * m / tOf(scale.latitudeDeg)
                        standardParallelRho = a * m
                    }
                }
            }
            this.falseEastingM = falseEastingM
            // En la C el Norte dado es el del punto donde el paralelo estándar corta el meridiano de origen, que está a ρ del polo.
            this.falseNorthingM = if (falseOriginAtParallel) falseNorthingM + pole * standardParallelRho else falseNorthingM
        }

        override fun forward(latitudeDeg: Double, longitudeDeg: Double): ProjectedPoint? {
            if (!latitudeDeg.isFinite() || !longitudeDeg.isFinite() || abs(latitudeDeg) > 90.0) return null
            // El polo opuesto queda en el infinito: sin imagen (RF-19).
            if (latitudeDeg == -pole * 90.0) return null
            val rho = rhoPerT * tOf(latitudeDeg)
            val delta = Math.toRadians(wrap(longitudeDeg - longitudeOfOriginDeg))
            val easting = falseEastingM + rho * sin(delta)
            val northing = falseNorthingM - pole * rho * cos(delta)
            return if (easting.isFinite() && northing.isFinite()) ProjectedPoint(easting, northing) else null
        }

        override fun inverse(eastingM: Double, northingM: Double): GeoPoint? {
            if (!eastingM.isFinite() || !northingM.isFinite()) return null
            val dx = eastingM - falseEastingM
            val dy = northingM - falseNorthingM
            val rho = hypot(dx, dy)
            // ρ = 0 es el polo: tiene imagen, pero su longitud no está definida (se devuelve la de origen).
            if (rho == 0.0) return GeoPoint(pole * 90.0, wrap(longitudeOfOriginDeg))
            val t = rho / rhoPerT
            val latitude = pole * Math.toDegrees(atan(conformal.tau(conformal.tauPrimeFromT(t))))
            val longitude = wrap(longitudeOfOriginDeg + Math.toDegrees(atan2(dx, -pole * dy)))
            return if (latitude.isFinite() && longitude.isFinite()) GeoPoint(latitude, longitude) else null
        }

        /**
         * Factor de escala y convergencia con fórmula cerrada (S032/RF-14; plan D8): `k = ρ / (a·m)` con `m = cosφ/√(1 − e²sin²φ)` y una
         * convergencia igual a la diferencia de longitud en el polo norte y a su opuesta en el sur (positiva al este, como en UTM). En los
         * polos la convergencia no está definida: no hay valor; el polo opuesto no tiene imagen.
         */
        override fun factors(latitudeDeg: Double, longitudeDeg: Double): PointFactors? {
            if (!latitudeDeg.isFinite() || !longitudeDeg.isFinite() || abs(latitudeDeg) >= 90.0) return null
            val phi = Math.toRadians(latitudeDeg)
            val sinPhi = sin(phi)
            val m = cos(phi) / sqrt(1 - e2 * sinPhi * sinPhi)
            val scale = rhoPerT * tOf(latitudeDeg) / (a * m)
            val delta = Math.toRadians(wrap(longitudeDeg - longitudeOfOriginDeg))
            return PointFactors(scale, pole * delta)
        }

        /** El `t` de GN 7-2 del polo de esta proyección: `exp(−ψ)` de la latitud vista desde ese polo. */
        private fun tOf(latitudeDeg: Double): Double = conformal.t(conformal.tauPrime(tan(Math.toRadians(pole * latitudeDeg))))

        private fun wrap(longitudeDeg: Double): Double {
            if (longitudeDeg > -180.0 && longitudeDeg <= 180.0) return longitudeDeg
            val wrapped = ((longitudeDeg + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
            return if (wrapped == -180.0) 180.0 else wrapped
        }
    }
}
