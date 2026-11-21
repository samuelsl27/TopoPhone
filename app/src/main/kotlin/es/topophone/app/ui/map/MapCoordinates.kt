package es.topophone.app.ui.map

import es.topophone.core.model.format.AngleFormat
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.Project
import es.topophone.core.survey.PositionUnavailable
import es.topophone.core.survey.ProjectPosition
import es.topophone.core.survey.ProjectPositionCalculator
import java.util.Locale

/** Cada una de las líneas de «Ver coordenadas» (S036/RF-40). */
enum class CoordinateKind { EASTING, NORTHING, LATITUDE, LONGITUDE }

/** Una línea de «Ver coordenadas»: lo que es y su valor ya formateado, con la unidad si es una longitud. */
data class CoordinateLine(val kind: CoordinateKind, val value: String)

/**
 * Las coordenadas del punto marcado con una pulsación larga (S036/RF-40, RF-41, A8 del plan): su latitud y longitud, las del mapa, y su
 * [position] en el SC del proyecto, que calcula [ProjectPositionCalculator] como para el rover y los puntos manuales (S006, S008). Sin altura:
 * el mapa no sabe a qué cota está un punto.
 */
data class MapCoordinates(val latitudeDeg: Double, val longitudeDeg: Double, val position: ProjectPosition) {
    /** Por qué el punto cae fuera del ámbito del SC del proyecto, o null si cae dentro (RF-41, S008/RF-9, RF-8). */
    val outOfScope: PositionUnavailable?
        get() = position.unavailable.firstOrNull {
            it == PositionUnavailable.OUTSIDE_CRS_AREA ||
                it == PositionUnavailable.OUTSIDE_ED50_GRID ||
                it == PositionUnavailable.OUTSIDE_DATUM_GRID
        }

    /**
     * Las líneas que se ven: Este y Norte del SC del proyecto en su unidad y, en los SC geográficos, la latitud y la longitud de su datum;
     * siempre la latitud y la longitud en la unidad angular del proyecto. Fuera del ámbito del SC solo salen la latitud y la longitud
     * del mapa (RF-40, RF-41). Lo que se copia son estas mismas líneas.
     */
    fun lines(project: Project, unitSymbol: String, locale: Locale): List<CoordinateLine> {
        val inScope = outOfScope == null
        fun length(valueM: Double) = "${LengthFormat.inUnit(valueM, project.linearUnit, locale)} $unitSymbol"
        val latitude = if (inScope) position.latitudeDeg ?: latitudeDeg else latitudeDeg
        val longitude = if (inScope) position.longitudeDeg ?: longitudeDeg else longitudeDeg
        return buildList {
            if (inScope) {
                position.easting?.let { add(CoordinateLine(CoordinateKind.EASTING, length(it))) }
                position.northing?.let { add(CoordinateLine(CoordinateKind.NORTHING, length(it))) }
            }
            add(CoordinateLine(CoordinateKind.LATITUDE, AngleFormat.latitude(latitude, project.angularUnit, locale)))
            add(CoordinateLine(CoordinateKind.LONGITUDE, AngleFormat.longitude(longitude, project.angularUnit, locale)))
        }
    }

    companion object {
        /** Las coordenadas del punto de [press] en el SC de [project] (RF-40). */
        fun of(press: MapPress, project: Project, calculator: ProjectPositionCalculator): MapCoordinates {
            val ground = GeodeticPosition(press.latitudeDeg, press.longitudeDeg, ellipsoidalHeightM = null)
            // Altura elipsoidal: no hay altura que pasar al geoide, y Este y Norte no dependen de ella.
            val position = calculator.fromGround(ground, project.crs, HeightSystem.ELLIPSOIDAL)
            return MapCoordinates(press.latitudeDeg, press.longitudeDeg, position)
        }

        /** El texto que «Copiar» deja en el portapapeles: una línea «nombre: valor» por cada línea que se ve (RF-40). */
        fun copyText(lines: List<CoordinateLine>, label: (CoordinateKind) -> String): String =
            lines.joinToString("\n") { "${label(it.kind)}: ${it.value}" }
    }
}
