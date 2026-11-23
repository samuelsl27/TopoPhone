package es.topophone.core.survey.localization

import es.topophone.core.model.localization.ActiveLocalizationInfo
import es.topophone.core.model.localization.LocalizationParams
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.hasMeasurement
import es.topophone.core.survey.ProjectPosition

/**
 * Localiza las coordenadas de un punto GNSS con la localización activa (S039/RF-17, RF-19, RF-20; plan D2, D3, D4). La posición del suelo no cambia
 * (constitución nº 1): solo Este, Norte y altura del SC. Sin localización, o sin Este y Norte, las coordenadas quedan como están.
 */
object PointLocalization {
    /** Las coordenadas de [sc] (las del SC sin localizar) localizadas con [localization]; las mismas si no hay localización. */
    fun coordinates(sc: PointCoordinates, localization: ActiveLocalizationInfo?): PointCoordinates {
        if (localization == null) return sc
        val e = sc.easting ?: return sc
        val n = sc.northing ?: return sc
        val (le, ln) = LocalizationApplier.horizontal(localization.params, e, n)
        val lh = sc.height?.let { LocalizationApplier.height(localization.params, e, n, it) }
        return sc.copy(easting = le, northing = ln, height = lh)
    }

    /** ¿Lo transforma una localización? Los puntos con medida y sin coordenadas editadas (RF-17, RF-20). */
    fun appliesTo(point: Point): Boolean = point.origin.hasMeasurement && point.measurement != null && !point.edited

    /** [point] localizado con [localization] y con su identificador guardado (RF-19); sin cambios si no le corresponde. */
    fun localize(point: Point, localization: ActiveLocalizationInfo?): Point {
        if (localization == null || !appliesTo(point)) return point
        val measurement = point.measurement ?: return point
        return point.copy(coordinates = coordinates(measurement.coordinates, localization), localizationId = localization.id)
    }
}

/** La posición con Este, Norte y altura localizados con [params] (S039/RF-17); la misma si no hay localización o falta alguna coordenada. */
fun ProjectPosition.localized(params: LocalizationParams?): ProjectPosition {
    if (params == null) return this
    val e = easting ?: return this
    val n = northing ?: return this
    val (le, ln) = LocalizationApplier.horizontal(params, e, n)
    return copy(easting = le, northing = ln, height = height?.let { LocalizationApplier.height(params, e, n, it) })
}
