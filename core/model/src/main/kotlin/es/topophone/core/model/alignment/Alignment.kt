package es.topophone.core.model.alignment

/** De dónde sale un eje (S049/RF-1): un LandXML, una polilínea de un dibujo, un CSV de rasante o la definición a mano. */
enum class AlignmentSource { LANDXML, DRAWING, CSV, MANUAL }

/** La clase de un tramo del eje en planta (S049/RF-4). */
enum class ElementKind { LINE, ARC, CLOTHOID }

/**
 * Un tramo del eje en planta, en el sistema del proyecto y en metros, sin redondear (S049/RF-13). La curvatura es la inversa del
 * radio y **positiva a la izquierda** del sentido de los PK, negativa a la derecha, cero en una recta; varía linealmente a lo
 * largo del tramo de [startCurvature] a [endCurvature]. [startAzimuth] es horario desde el Norte, en radianes (RNF-T9).
 */
data class AlignmentElement(
    val kind: ElementKind,
    val startChainage: Double,
    val length: Double,
    val startEasting: Double,
    val startNorthing: Double,
    val startAzimuth: Double,
    val startCurvature: Double,
    val endCurvature: Double,
) {
    init {
        require(length > 0.0) { "Un tramo tiene longitud" }
        when (kind) {
            ElementKind.LINE -> require(startCurvature == 0.0 && endCurvature == 0.0) { "Una recta no tiene curvatura" }
            ElementKind.ARC -> require(startCurvature != 0.0 && startCurvature == endCurvature) { "Un arco tiene una curvatura constante" }
            ElementKind.CLOTHOID -> require(startCurvature != endCurvature) { "Una clotoide cambia de curvatura" }
        }
    }

    val endChainage: Double get() = startChainage + length

    companion object {
        fun line(startChainage: Double, length: Double, easting: Double, northing: Double, azimuth: Double) =
            AlignmentElement(ElementKind.LINE, startChainage, length, easting, northing, azimuth, 0.0, 0.0)

        fun arc(startChainage: Double, length: Double, easting: Double, northing: Double, azimuth: Double, curvature: Double) =
            AlignmentElement(ElementKind.ARC, startChainage, length, easting, northing, azimuth, curvature, curvature)

        fun clothoid(
            startChainage: Double,
            length: Double,
            easting: Double,
            northing: Double,
            azimuth: Double,
            startCurvature: Double,
            endCurvature: Double,
        ) = AlignmentElement(ElementKind.CLOTHOID, startChainage, length, easting, northing, azimuth, startCurvature, endCurvature)
    }
}

/** El acuerdo vertical de un vértice de la rasante (S049/RF-4): una parábola simétrica o un círculo, con su longitud horizontal. */
sealed interface VerticalCurve {
    data object None : VerticalCurve

    /** Parábola simétrica de [length] metros de longitud horizontal, centrada en el vértice. */
    data class Parabola(val length: Double) : VerticalCurve

    /** Círculo de [radius] metros tangente a las dos pendientes; [length] es la longitud de su arco. */
    data class Circular(val radius: Double, val length: Double) : VerticalCurve
}

/** Un vértice de la rasante: su PK, su cota en metros y el acuerdo que lo suaviza (S049/RF-4, RF-8). */
data class VerticalVertex(val chainage: Double, val elevation: Double, val curve: VerticalCurve = VerticalCurve.None)

/** La rasante de un eje: sus vértices por PK creciente. Que sea válida lo comprueba el validador de `core:survey` (RF-10). */
data class VerticalProfile(val vertices: List<VerticalVertex>) {
    init {
        require(vertices.size >= 2) { "Una rasante necesita al menos dos vértices" }
    }
}

/** El recuadro de un eje en el sistema del proyecto, en metros. */
data class AlignmentExtent(val minEasting: Double, val maxEasting: Double, val minNorthing: Double, val maxNorthing: Double)

/**
 * Un eje en un proyecto (S049): su planta, su rasante si la tiene y lo necesario para listarlo (RF-13, RF-14, RF-19). [fileName] es
 * null si se definió a mano. [visible] es la elección de mostrarlo en el mapa (RF-19). [approximated] es que el eje se importó de otro
 * sistema de coordenadas y se guardó como rectas cortas (decisión D2): su radio no se conoce.
 */
data class Alignment(
    val id: String,
    val projectId: String,
    val name: String,
    val fileName: String?,
    val importedAtMs: Long,
    val source: AlignmentSource,
    val elements: List<AlignmentElement>,
    val profile: VerticalProfile?,
    val visible: Boolean,
    val extent: AlignmentExtent,
    val approximated: Boolean = false,
) {
    init {
        require(elements.isNotEmpty()) { "Un eje tiene al menos un tramo" }
    }

    /** El PK del primer punto del eje (0 por defecto). */
    val startChainage: Double get() = elements.first().startChainage

    val lengthM: Double get() = elements.sumOf { it.length }

    val endChainage: Double get() = startChainage + lengthM
}
