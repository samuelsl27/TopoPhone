package es.topophone.core.geodesy.catalog

import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.PointFactors
import es.topophone.core.geodesy.TransverseMercator
import es.topophone.core.geodesy.WebMercator
import es.topophone.core.geodesy.projection.LambertConic1Sp
import es.topophone.core.geodesy.projection.LambertConic2Sp
import es.topophone.core.geodesy.projection.MapProjection
import es.topophone.core.geodesy.projection.MercatorA
import es.topophone.core.geodesy.projection.MercatorB
import es.topophone.core.geodesy.projection.ObliqueMercatorA
import es.topophone.core.geodesy.projection.ObliqueMercatorB
import es.topophone.core.geodesy.projection.ObliqueStereographic
import es.topophone.core.geodesy.projection.PolarStereographicA
import es.topophone.core.geodesy.projection.PolarStereographicB
import es.topophone.core.geodesy.projection.PolarStereographicC
import es.topophone.core.geodesy.projection.PrimeMeridianProjection
import es.topophone.core.geodesy.projection.ProjectedPoint
import es.topophone.core.geodesy.projection.ProjectionFactory
import es.topophone.core.geodesy.projection.ProjectionParameters
import es.topophone.core.geodesy.projection.ProjectionResult
import es.topophone.core.geodesy.projection.ProjectionSetup
import es.topophone.core.model.project.LinearUnit
import kotlin.math.abs

/**
 * Del SC de la biblioteca a su proyección ya construida (S034/RF-4): Transversa de Mercator con latitud de origen cualquiera, Web Mercator
 * y los diez métodos de S032 por `ProjectionFactory`. Las longitudes que entran y salen son de Greenwich y las coordenadas, en metros.
 */
object CrsBuilder {

    /** `null` si el SC es geográfico (no proyecta) o si sus parámetros no son válidos para su método. */
    fun projection(crs: CrsEntry, datum: DatumEntry): MapProjection? {
        if (crs.kind == CrsKind.GEOGRAPHIC) return null
        val unit = linearUnit(crs.unitMetres) ?: return null
        val p = crs.parameters
        val ellipsoid = datum.ellipsoid
        val pm = datum.primeMeridianDeg
        return when (crs.method) {
            "tm" -> withPrimeMeridian(
                TransverseMercatorWithOrigin(
                    ellipsoid,
                    p["latitudeOfOriginDeg"] ?: return null,
                    p["longitudeOfOriginDeg"] ?: return null,
                    p["scaleFactor"] ?: return null,
                    unit.toMetres(p["falseEasting"] ?: return null),
                    unit.toMetres(p["falseNorthing"] ?: return null),
                ),
                pm,
            )
            "webmerc" -> WebMercatorProjection
            else -> {
                val parameters = parameters(crs.method, p) ?: return null
                (ProjectionFactory.create(ProjectionSetup(ellipsoid, parameters, unit, pm)) as? ProjectionResult.Ok)?.projection
            }
        }
    }

    private fun withPrimeMeridian(projection: MapProjection, primeMeridianDeg: Double): MapProjection =
        if (primeMeridianDeg == 0.0) projection else PrimeMeridianProjection(projection, primeMeridianDeg)

    private fun linearUnit(metres: Double): LinearUnit? = LinearUnit.entries.firstOrNull { abs(it.metres - metres) < 1e-12 }

    private fun parameters(method: String, p: Map<String, Double>): ProjectionParameters? = try {
        parametersOf(method) { p.getValue(it) }
    } catch (_: NoSuchElementException) {
        null
    }

    private fun parametersOf(method: String, d: (String) -> Double): ProjectionParameters? = when (method) {
        "lcc1" -> LambertConic1Sp(
            d("latitudeOfOriginDeg"),
            d("longitudeOfOriginDeg"),
            d("scaleFactor"),
            d("falseEasting"),
            d("falseNorthing"),
        )
        "lcc2" -> LambertConic2Sp(
            d("latitudeOfFalseOriginDeg"),
            d("longitudeOfFalseOriginDeg"),
            d("standardParallel1Deg"),
            d("standardParallel2Deg"),
            d("eastingAtFalseOrigin"),
            d("northingAtFalseOrigin"),
        )
        "merca" -> MercatorA(d("longitudeOfOriginDeg"), d("scaleFactor"), d("falseEasting"), d("falseNorthing"))
        "mercb" -> MercatorB(d("longitudeOfOriginDeg"), d("standardParallelDeg"), d("falseEasting"), d("falseNorthing"))
        "omerca" -> ObliqueMercatorA(
            d("latitudeOfCentreDeg"),
            d("longitudeOfCentreDeg"),
            d("azimuthDeg"),
            d("rectifiedGridAngleDeg"),
            d("scaleFactor"),
            d("falseEasting"),
            d("falseNorthing"),
        )
        "omercb" -> ObliqueMercatorB(
            d("latitudeOfCentreDeg"),
            d("longitudeOfCentreDeg"),
            d("azimuthDeg"),
            d("rectifiedGridAngleDeg"),
            d("scaleFactor"),
            d("eastingAtCentre"),
            d("northingAtCentre"),
        )
        "stereo" -> ObliqueStereographic(
            d("latitudeOfOriginDeg"),
            d("longitudeOfOriginDeg"),
            d("scaleFactor"),
            d("falseEasting"),
            d("falseNorthing"),
        )
        "polara" -> PolarStereographicA(
            d("latitudeOfOriginDeg"),
            d("longitudeOfOriginDeg"),
            d("scaleFactor"),
            d("falseEasting"),
            d("falseNorthing"),
        )
        "polarb" -> PolarStereographicB(
            d("latitudeOfStandardParallelDeg"),
            d("longitudeOfOriginDeg"),
            d("falseEasting"),
            d("falseNorthing"),
        )
        "polarc" -> PolarStereographicC(
            d("latitudeOfStandardParallelDeg"),
            d("longitudeOfOriginDeg"),
            d("eastingAtFalseOrigin"),
            d("northingAtFalseOrigin"),
        )
        else -> null
    }
}

/**
 * Transversa de Mercator con latitud de origen distinta de 0 (S034): la de S008 con el Norte del origen restado, que es lo que hace EPSG
 * (el Norte del punto es `k0·M(φ) − k0·M(φ0) + FN`). La Transversa de S008 no se toca.
 */
internal class TransverseMercatorWithOrigin(
    ellipsoid: Ellipsoid,
    latitudeOfOriginDeg: Double,
    centralMeridianDeg: Double,
    scaleFactor: Double,
    private val falseEastingM: Double,
    private val falseNorthingM: Double,
) : MapProjection {
    private val tm = TransverseMercator(ellipsoid, centralMeridianDeg, scaleFactor, 0.0, 0.0)
    private val originNorthingM = tm.forward(latitudeOfOriginDeg, centralMeridianDeg).second

    override fun forward(latitudeDeg: Double, longitudeDeg: Double): ProjectedPoint? {
        val (e, n) = tm.forward(latitudeDeg, longitudeDeg)
        if (!e.isFinite() || !n.isFinite()) return null
        return ProjectedPoint(e + falseEastingM, n - originNorthingM + falseNorthingM)
    }

    override fun inverse(eastingM: Double, northingM: Double): GeoPoint? =
        tm.inverse(eastingM - falseEastingM, northingM + originNorthingM - falseNorthingM).takeIf {
            it.latitudeDeg.isFinite() &&
                it.longitudeDeg.isFinite()
        }

    override fun factors(latitudeDeg: Double, longitudeDeg: Double): PointFactors? = tm.factors(latitudeDeg, longitudeDeg)
}

/** Web Mercator (EPSG:3857) como `MapProjection`: la esfera de S009, sin factores propios (la escala es 1/cos φ). */
internal object WebMercatorProjection : MapProjection {
    override fun forward(latitudeDeg: Double, longitudeDeg: Double): ProjectedPoint? {
        if (abs(latitudeDeg) >= WEB_MERCATOR_MAX_LATITUDE_DEG) return null
        val (x, y) = WebMercator.forward(latitudeDeg, longitudeDeg)
        return ProjectedPoint(x, y)
    }

    override fun inverse(eastingM: Double, northingM: Double): GeoPoint? = WebMercator.inverse(eastingM, northingM)

    override fun factors(latitudeDeg: Double, longitudeDeg: Double): PointFactors? {
        if (abs(latitudeDeg) >= WEB_MERCATOR_MAX_LATITUDE_DEG) return null
        return PointFactors(scale = 1.0 / kotlin.math.cos(Math.toRadians(latitudeDeg)), convergenceRad = 0.0)
    }

    private const val WEB_MERCATOR_MAX_LATITUDE_DEG = 89.9
}
