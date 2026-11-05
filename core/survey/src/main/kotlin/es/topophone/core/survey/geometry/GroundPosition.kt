package es.topophone.core.survey.geometry

import es.topophone.core.geodesy.Ed50
import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.Utm
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.ProjectCrs

/**
 * De las coordenadas de un punto en el SC del proyecto a su latitud y longitud ETRS89 o REGCAN95 (S026/RF-10): la UTM inversa y, en
 * ED50, la rejilla hacia ETRS89. Es la misma ruta que usa la importación de dibujos (S040/RF-17).
 */
object GroundPosition {

    /**
     * La latitud y la longitud, en ese orden, del punto ([first], [second]) del [crs]: Este y Norte en un SC UTM, o longitud y
     * latitud en grados en uno geográfico. Null si es ED50 y cae fuera de la rejilla.
     */
    fun of(crs: ProjectCrs, first: Double, second: Double): Pair<Double, Double>? = of(crs.datum, first, second, crs.utmZone)

    /** Lo mismo con el datum y el huso sueltos; sin huso, [first] es la longitud y [second] la latitud. */
    fun of(datum: GeodeticDatum, first: Double, second: Double, zone: Int?): Pair<Double, Double>? {
        val (lat, lon) = if (zone != null) {
            val ellipsoid = if (datum == GeodeticDatum.ED50) Ellipsoid.INTERNATIONAL_1924 else Ellipsoid.GRS80
            Utm.inverse(first, second, zone, ellipsoid).let { it.latitudeDeg to it.longitudeDeg }
        } else {
            second to first
        }
        return if (datum == GeodeticDatum.ED50) Ed50.toEtrs89(lat, lon) else lat to lon
    }
}
