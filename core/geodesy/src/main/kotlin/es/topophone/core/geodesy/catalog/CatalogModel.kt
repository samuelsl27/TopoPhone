package es.topophone.core.geodesy.catalog

import es.topophone.core.geodesy.Ellipsoid

/** Recuadro en geográficas, en grados; si [westDeg] es mayor que [eastDeg] cruza el antimeridiano (S034/RF-4). */
data class CatalogArea(val southDeg: Double, val westDeg: Double, val northDeg: Double, val eastDeg: Double) {
    fun contains(latitudeDeg: Double, longitudeDeg: Double): Boolean {
        if (latitudeDeg !in southDeg..northDeg) return false
        return if (westDeg <= eastDeg) longitudeDeg in westDeg..eastDeg else longitudeDeg >= westDeg || longitudeDeg <= eastDeg
    }

    companion object {
        /** `S,W,N,E`, o `null` si el texto está vacío. */
        fun parse(text: String): CatalogArea? {
            if (text.isEmpty()) return null
            val v = text.split(',').map { it.toDouble() }
            require(v.size == 4) { "Ámbito mal formado: $text" }
            return CatalogArea(v[0], v[1], v[2], v[3])
        }
    }
}

enum class CrsKind { PROJECTED, GEOGRAPHIC }

/** El datum de las correcciones al que lleva una transformación: ETRS89 (REGCAN95 se trata igual) o WGS 84 (ITRF) (S034, plan D8). */
enum class CorrectionFrame { ETRS89, WGS84 }

/**
 * Un SC de la biblioteca (S034/RF-4). [method] es el nombre corto del método (`tm`, `lcc2`, `geo`…) y [parameters] los de
 * `ProjectionParameters` de S032, con los ángulos en grados y los falsos en la unidad lineal del SC ([unitMetres] metros por unidad).
 */
data class CrsEntry(
    val epsg: Int,
    val name: String,
    val kind: CrsKind,
    val datumCode: Int,
    val method: String,
    val parameters: Map<String, Double>,
    val unitMetres: Double,
    val area: CatalogArea?,
    val countries: List<String>,
    val areaName: String,
)

/** El datum de un SC: el CRS geográfico de EPSG que lo identifica, con su elipsoide y su meridiano de origen (° respecto a Greenwich). */
data class DatumEntry(val code: Int, val name: String, val ellipsoid: Ellipsoid, val primeMeridianDeg: Double, val area: CatalogArea?)

data class Country(val iso: String, val nameEs: String, val nameEn: String)

/** Un paso de una transformación del catálogo (S033). */
sealed interface StepSpec {
    /** No cambia nada. */
    data object Null : StepSpec

    /**
     * Helmert (o Molodensky-Badekas si hay [pivotM]) entre geocéntricas: rotaciones en segundos de arco y escala en ppm. [before] y [after]
     * son los elipsoides de las geográficas antes y después; con [inverse] se aplica el inverso exacto de los parámetros.
     */
    data class Helmert(
        val before: Ellipsoid,
        val after: Ellipsoid,
        val inverse: Boolean,
        val convention: String,
        val translationM: Triple<Double, Double, Double>,
        val rotationArcSec: Triple<Double, Double, Double>,
        val scalePpm: Double,
        val pivotM: Triple<Double, Double, Double>?,
    ) : StepSpec

    /** Rejilla de desplazamiento horizontal. */
    data class Grid(val name: String, val inverse: Boolean) : StepSpec
}

/** Una transformación de un datum hasta ETRS89 o WGS 84, con lo que la ficha del SC muestra de ella (S034/RF-9, RF-10). */
data class TransformationOption(
    val datumCode: Int,
    val target: CorrectionFrame,
    val name: String,
    val accuracyM: Double?,
    val area: CatalogArea?,
    val steps: List<StepSpec>,
) {
    /** Las rejillas que necesita, en orden. */
    val gridNames: List<String> get() = steps.filterIsInstance<StepSpec.Grid>().map { it.name }
}

enum class GridKind { HORIZONTAL, GEOID }

/** Licencia de una rejilla; si no es [open] (no está clara), la app la ofrece con un aviso y el enlace a la fuente (S034/RF-20). */
data class GridLicense(val id: String, val name: String, val open: Boolean, val url: String)

/** Una rejilla del CDN de PROJ que la app puede descargar o ya trae ([bundled]) (S034/RF-12, RF-17, RF-19). */
data class GridEntry(
    val name: String,
    val kind: GridKind,
    val sizeBytes: Long,
    val sha256: String,
    val license: GridLicense,
    val source: String,
    val sourceUrl: String,
    val verticalCrs: String,
    val area: CatalogArea?,
    val bundled: Boolean,
)
