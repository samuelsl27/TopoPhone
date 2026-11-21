package es.topophone.core.survey

import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.project.CrsDefinition
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LibraryCrs

/**
 * De una coordenada escrita en el SC de origen de un fichero (un SC de la biblioteca, S057/RF-2) al suelo: latitud y longitud en el datum de las
 * correcciones del proyecto (S057/RF-7, RF-11, RF-12). Del SC proyectado a geográficas con la inversa de su proyección, y de ahí al datum de las
 * correcciones con la transformación guardada en su definición; un SC geográfico salta la proyección. Sin redondear nada (constitución nº 3).
 *
 * Una rejilla que falta o un punto fuera de ella dan la causa en vez de una coordenada, y un punto fuera del ámbito del SC de origen se rechaza.
 */
class SourceGround(private val definition: CrsDefinition, resources: LibraryResources = LibraryResources.current) {
    private val crs = LibraryCrs(definition)
    private val engine = LibraryEngine(crs, resources)

    /** ¿Es un SC proyectado (X e Y en una unidad de longitud) y no uno geográfico (X longitud e Y latitud, en grados)? */
    val isProjected: Boolean get() = definition.projected

    /** La unidad lineal propia del SC de origen, en metros; la que se propone al importar (S057/RF-3). */
    val unitMetres: Double get() = definition.unitMetres

    /**
     * El suelo del punto ([x], [y]) del fichero. [unitMetres] es la unidad lineal de las coordenadas (la elegida al importar); en un SC geográfico
     * [x] es la longitud e [y] la latitud, en grados, y la unidad no cuenta.
     */
    fun toGround(x: Double, y: Double, unitMetres: Double = this.unitMetres): GroundResult {
        if (!x.isFinite() || !y.isFinite()) return GroundResult.Unavailable(PositionUnavailable.OUTSIDE_CRS_AREA)
        val result = if (definition.projected) engine.groundFromGrid(x * unitMetres, y * unitMetres) else engine.groundFromDatum(y, x)
        if (result is GroundResult.Ok && !crs.containsPosition(result.latitudeDeg, result.longitudeDeg)) {
            return GroundResult.Unavailable(PositionUnavailable.OUTSIDE_CRS_AREA)
        }
        return result
    }

    /**
     * Lo contrario de [toGround]: el punto del suelo ([latitudeDeg], [longitudeDeg]) en el SC de origen, como (X, Y) en [unitMetres] o, en un SC
     * geográfico, (longitud, latitud) en grados. `null` si cae fuera de la rejilla de la transformación, del ámbito del SC o falta la rejilla.
     * Sirve para consultar un ráster desde una posición del proyecto (S057/RF-15).
     */
    fun fromGround(latitudeDeg: Double, longitudeDeg: Double, unitMetres: Double = this.unitMetres): Pair<Double, Double>? {
        val position = engine.fromGround(GeodeticPosition(latitudeDeg, longitudeDeg, null), HeightSystem.ELLIPSOIDAL)
        if (PositionUnavailable.OUTSIDE_CRS_AREA in position.unavailable ||
            PositionUnavailable.OUTSIDE_DATUM_GRID in position.unavailable ||
            PositionUnavailable.MISSING_GRID in position.unavailable
        ) {
            return null
        }
        return if (definition.projected) {
            val easting = position.easting ?: return null
            val northing = position.northing ?: return null
            easting / unitMetres to northing / unitMetres
        } else {
            val longitude = position.longitudeDeg ?: return null
            val latitude = position.latitudeDeg ?: return null
            longitude to latitude
        }
    }
}
