package es.topophone.core.model.surface

import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit

/** El recuadro de una superficie en el sistema del proyecto, en metros (S042/RF-8, RF-13). */
data class SurfaceExtent(val minEasting: Double, val maxEasting: Double, val minNorthing: Double, val maxNorthing: Double) {
    /** El centro del recuadro. */
    val centerEasting: Double get() = (minEasting + maxEasting) / 2
    val centerNorthing: Double get() = (minNorthing + maxNorthing) / 2

    /** La posición cae dentro del recuadro, borde incluido. */
    fun contains(easting: Double, northing: Double): Boolean = easting in minEasting..maxEasting && northing in minNorthing..maxNorthing
}

/**
 * Una superficie importada de un LandXML en un proyecto (S042). Las coordenadas y las cotas guardadas están ya en el sistema y en
 * los metros del proyecto; [source] y [unit] son lo que el usuario eligió al importar (RF-6, RF-7). [holeCount] son los triángulos
 * que el fichero marcaba como invisibles (RF-12). La cota no cambia de sistema de alturas (suposición de la spec).
 *
 * Una superficie también puede ser un ráster de cotas (S045): [kind] es [SurfaceKind.RASTER] y [raster] da su malla. Entonces [source]
 * es el sistema del ráster, [unit] la unidad de sus cotas, [extent] el recuadro de sus centros de celda en el sistema del proyecto,
 * [minElevationM] y [maxElevationM] las cotas en metros tal como vienen (en el sistema de alturas del ráster, sin convertir) y los
 * recuentos de triángulos valen 0.
 */
data class Surface(
    val id: String,
    val projectId: String,
    val name: String,
    val fileName: String,
    val importedAtMs: Long,
    val source: CadSourceCrs,
    val unit: LinearUnit,
    val extent: SurfaceExtent,
    val minElevationM: Double,
    val maxElevationM: Double,
    val vertexCount: Int,
    val triangleCount: Int,
    val holeCount: Int,
    val kind: SurfaceKind = SurfaceKind.TIN,
    val raster: RasterInfo? = null,
)

/** Qué clase de superficie es (S045/RF-17): un modelo de triángulos de un LandXML (S042) o un ráster de cotas. */
enum class SurfaceKind { TIN, RASTER }

/** Cómo se guardan las celdas de un ráster (S045/RF-14): float64 solo si el fichero trae reales de 64 bits, para no redondear nada. */
enum class RasterValueType { FLOAT32, FLOAT64 }

/**
 * La malla de un ráster de cotas (S045/RF-14, RF-17), en el sistema de coordenadas del ráster y sin remuestrear: [originX] y [originY]
 * son el centro de la celda (0, 0), arriba a la izquierda; [stepX] y [stepY] el tamaño de la celda en las unidades de ese sistema, con
 * [stepY] positivo hacia el sur. [heightSystem] es el sistema de alturas de las cotas. [dataCells] y [noDataCells] cuentan las celdas
 * con y sin valor.
 */
data class RasterInfo(
    val columns: Int,
    val rows: Int,
    val originX: Double,
    val originY: Double,
    val stepX: Double,
    val stepY: Double,
    val valueType: RasterValueType,
    val heightSystem: HeightSystem,
    val dataCells: Long,
    val noDataCells: Long,
) {
    init {
        require(columns > 0 && rows > 0) { "Un ráster tiene al menos una celda" }
        require(stepX > 0.0 && stepY > 0.0) { "El tamaño de la celda es positivo" }
    }

    /** Las celdas del ráster, con y sin dato. */
    val cellCount: Long get() = columns.toLong() * rows

    /** Cuántos bloques de [RasterTile.EDGE] celdas de ancho y de alto lo cubren. */
    val tileColumns: Int get() = (columns + RasterTile.EDGE - 1) / RasterTile.EDGE
    val tileRows: Int get() = (rows + RasterTile.EDGE - 1) / RasterTile.EDGE
}

/**
 * La cota de una superficie en una posición (S042/RF-22, RF-23): su valor en metros, [Outside] si la posición está fuera del
 * contorno o en un hueco, o [Loading] mientras la parte de la superficie que la contiene aún no está en memoria. Cargando nunca se
 * presenta como fuera de la superficie.
 */
sealed interface SurfaceHeight {
    data class Value(val metres: Double) : SurfaceHeight

    data object Outside : SurfaceHeight

    data object Loading : SurfaceHeight

    /** La cota en metros, o null si no la hay (fuera o cargando). */
    val metresOrNull: Double? get() = (this as? Value)?.metres

    /** La misma cota con el desplazamiento vertical sumado (RF-24); fuera y cargando no cambian. */
    fun withOffset(offsetM: Double): SurfaceHeight = if (this is Value) Value(metres + offsetM) else this
}
