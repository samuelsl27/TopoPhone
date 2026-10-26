package es.topophone.core.model.cad

import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.LinearUnit

/** En qué sistema vienen las coordenadas de un DXF (S040/RF-6): el del proyecto u otro UTM de la app. */
sealed interface CadSourceCrs {
    /** Las coordenadas están ya en el sistema de coordenadas del proyecto. */
    data object Project : CadSourceCrs

    /** UTM de un datum y un huso de los que ofrece la app. */
    data class Utm(val datum: GeodeticDatum, val zone: Int) : CadSourceCrs
}

/** Lo que el usuario decide al importar un DXF (S040/RF-6, RF-7, RF-8). */
data class CadImportOptions(
    val source: CadSourceCrs = CadSourceCrs.Project,
    val unit: LinearUnit = LinearUnit.METRE,
    val useElevations: Boolean = true,
)

/** El tipo de una capa de diseño: una capa del DXF se separa en una de puntos y otra de líneas (S040, términos). */
enum class DesignLayerKind { POINTS, LINES }

/** El tipo original de un elemento del dibujo, el que muestra su ficha aunque se guarde como polilínea (S040/RF-14, RF-29). */
enum class CadEntityKind { POINT, LINE, POLYLINE, ARC, CIRCLE, SPLINE, BLOCK_INSERT }

/**
 * El color de una capa o de un elemento: ARGB, o [autoContrast] para el blanco o el negro del DXF, que se pinta del color de
 * contraste del mapa (S040/RF-25).
 */
data class CadColor(val argb: Int, val autoContrast: Boolean = false) {
    companion object {
        val AUTO_CONTRAST = CadColor(0xFF000000.toInt(), autoContrast = true)

        /** Un color opaco desde 0xRRGGBB. */
        fun rgb(rgb: Int) = CadColor(0xFF000000.toInt() or (rgb and 0xFFFFFF))
    }
}

/** El recuadro de un dibujo en el sistema del proyecto (S040/RF-9). */
data class CadExtent(val minEasting: Double, val maxEasting: Double, val minNorthing: Double, val maxNorthing: Double)

/**
 * Un DXF importado en un proyecto (S040). [hasElevations] es la cota del dibujo entero: falso si el usuario no quiso las cotas
 * (RF-8) o si todas las Z valían 0 (RF-22).
 */
data class CadDrawing(
    val id: String,
    val projectId: String,
    val fileName: String,
    val importedAtMs: Long,
    val source: CadSourceCrs,
    val unit: LinearUnit,
    val useElevations: Boolean,
    val hasElevations: Boolean,
    val incomplete: Boolean = false,
    val extent: CadExtent? = null,
) {
    /** Un elemento está con cota si el dibujo la tiene y el elemento trae Z (S040/RF-23). */
    fun hasElevation(entityHasZ: Boolean): Boolean = hasElevations && entityHasZ
}

/** Una capa del DXF de un solo tipo, con su color y su interruptor (S040/RF-26, RF-27). */
data class DesignLayer(
    val id: String,
    val drawingId: String,
    val name: String,
    val kind: DesignLayerKind,
    val color: CadColor,
    val visible: Boolean = true,
    val entityCount: Int = 0,
    val position: Int = 0,
)

/** Un elemento: su capa, el trozo que lo guarda y su posición en él (S040/RF-29; S041 lo usa para elegir qué replantear). */
data class CadEntityRef(val layerId: String, val chunkId: Long, val index: Int)

/** Por qué no entra un elemento o una parte del dibujo (S040/RF-16, RF-17, RF-18). */
enum class CadIgnoredReason {
    UNSUPPORTED_TYPE,
    OUTSIDE_CRS_AREA,
    OUTSIDE_ED50_GRID,
    BLOCK_CYCLE,
    BLOCK_TOO_LARGE,
    UNDEFINED_BLOCK,
    INVALID_VALUE,
}

/** Una clase de lo ignorado: el motivo y, si es un tipo no soportado, su nombre en el DXF (`TEXT`, `HATCH`…) (S040/RF-18). */
data class CadIgnored(val reason: CadIgnoredReason, val dxfType: String? = null)

/** Lo que muestra el resumen al terminar la importación (S040/RF-18). */
data class CadImportSummary(
    val imported: Map<CadEntityKind, Int>,
    val ignored: Map<CadIgnored, Int>,
    val layers: Int,
    val incomplete: Boolean,
) {
    val importedTotal: Int get() = imported.values.sum()
    val ignoredTotal: Int get() = ignored.values.sum()
}
