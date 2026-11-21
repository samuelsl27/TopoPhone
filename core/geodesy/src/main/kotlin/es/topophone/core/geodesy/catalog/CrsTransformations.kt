package es.topophone.core.geodesy.catalog

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.GeoTiffGrid
import es.topophone.core.geodesy.GridWindow
import es.topophone.core.geodesy.datum.AreaOfUse
import es.topophone.core.geodesy.datum.DatumTransformation
import es.topophone.core.geodesy.datum.GridShiftTransformation
import es.topophone.core.geodesy.datum.GridSource
import es.topophone.core.geodesy.datum.HelmertConvention
import es.topophone.core.geodesy.datum.HelmertParameters
import es.topophone.core.geodesy.datum.HelmertParametersResult
import es.topophone.core.geodesy.datum.HelmertTransformation
import es.topophone.core.geodesy.datum.MolodenskyBadekas
import es.topophone.core.geodesy.datum.MolodenskyBadekasParameters
import es.topophone.core.geodesy.datum.MolodenskyBadekasParametersResult
import es.topophone.core.geodesy.datum.NullTransformation
import es.topophone.core.geodesy.datum.ReversedTransformation
import es.topophone.core.geodesy.datum.RotationUnit
import es.topophone.core.geodesy.datum.TransformationChain
import es.topophone.core.geodesy.datum.TransformationInfo
import es.topophone.core.model.project.CorrectionsDatum

/** A qué datum de la biblioteca corresponde el de las correcciones del proyecto: REGCAN95 se trata como ETRS89 (plan D8). */
fun CorrectionsDatum.frame(): CorrectionFrame = when (this) {
    CorrectionsDatum.ETRS89, CorrectionsDatum.REGCAN95 -> CorrectionFrame.ETRS89
    CorrectionsDatum.ITRF_WGS84 -> CorrectionFrame.WGS84
}

/** De una transformación del catálogo a la que calcula S033 (S034/RF-9, RF-11). */
object CatalogTransformations {

    /** La transformación del datum del SC hasta el de las correcciones, en un solo `DatumTransformation`; las rejillas se leen de [grids]. */
    fun build(option: TransformationOption, grids: GridSource): DatumTransformation {
        val area = option.area?.let { AreaOfUse(it.southDeg, it.northDeg, it.westDeg, it.eastDeg) }
        val info = TransformationInfo(option.name, accuracyM = option.accuracyM, areaOfUse = area)
        val steps = option.steps.map { step ->
            when (step) {
                StepSpec.Null -> NullTransformation(info)
                is StepSpec.Helmert -> helmert(step, info)
                is StepSpec.Grid -> GridShiftTransformation(step.name, grids, info).let {
                    if (step.inverse) ReversedTransformation(it) else it
                }
            }
        }
        return if (steps.size == 1) steps.single() else TransformationChain(steps, info)
    }

    private fun helmert(step: StepSpec.Helmert, info: TransformationInfo): DatumTransformation {
        val convention = if (step.convention == "CF") HelmertConvention.COORDINATE_FRAME else HelmertConvention.POSITION_VECTOR
        val parameters = (
            HelmertParameters.seven(step.translationM, step.rotationArcSec, RotationUnit.ARC_SECOND, step.scalePpm, convention)
                as? HelmertParametersResult.Ok
            )?.parameters ?: error("Parámetros de Helmert no válidos en ${info.name}")
        // El paso se aplica de `before` a `after`; con `inverse` es el inverso exacto de unos parámetros definidos de `after` a `before`.
        val source = if (step.inverse) step.after else step.before
        val target = if (step.inverse) step.before else step.after
        val transformation = if (step.pivotM == null) {
            HelmertTransformation(parameters, source, target, info)
        } else {
            val pivot = (MolodenskyBadekasParameters.of(parameters, step.pivotM) as? MolodenskyBadekasParametersResult.Ok)?.parameters
                ?: error("Punto de rotación no válido en ${info.name}")
            MolodenskyBadekas(pivot, source, target, info)
        }
        return if (step.inverse) ReversedTransformation(transformation) else transformation
    }
}

/** Las transformaciones aplicables a un proyecto, en el orden en que se proponen (S034/RF-10). */
class PickedTransformations(val applicable: List<TransformationOption>, val others: List<TransformationOption>) {
    /** La que se propone: la más precisa cuyo ámbito cubre el centro del proyecto. */
    val best: TransformationOption? get() = applicable.firstOrNull()
}

object TransformationPicker {

    /**
     * Las transformaciones del datum del SC hasta [frame]: primero las que cubren [centre] (o todas si no hay centro) y luego las demás; en cada
     * grupo, de más a menos precisas, y a igualdad la que no necesita rejilla (S034/RF-10).
     */
    fun pick(catalog: CrsCatalog, datumCode: Int, frame: CorrectionFrame, centre: GeoPoint?): PickedTransformations {
        val ordered = catalog.optionsFor(datumCode, frame).sortedWith(
            compareBy<TransformationOption> { it.accuracyM ?: Double.MAX_VALUE }.thenBy { it.gridNames.size }.thenBy { it.name },
        )
        val (covering, others) = ordered.partition { option ->
            centre == null || option.area == null || option.area.contains(centre.latitudeDeg, centre.longitudeDeg)
        }
        return PickedTransformations(covering, others)
    }
}

/** Qué rejillas faltan para una transformación (S034/RF-12, RF-18). */
object GridRequirement {

    /** Las rejillas de [option] que no vienen en la app ni están instaladas según [isInstalled]. */
    fun missing(catalog: CrsCatalog, option: TransformationOption, isInstalled: (String) -> Boolean): List<GridEntry> =
        option.gridNames.mapNotNull { name -> catalog.grids[name]?.takeUnless { it.bundled || isInstalled(name) } }
}

/** Una fuente de ondulaciones del geoide: la incluida en la app o una rejilla descargada (S034/RF-14, RF-16). */
interface GeoidSource {
    val name: String
    val attribution: String

    /** Ondulación N en metros, o `null` fuera de la cobertura o si falta la rejilla (ADR-5). */
    fun undulationM(latitudeDeg: Double, longitudeDeg: Double): Double?
}

/**
 * Geoide de una rejilla descargada, que se lee **por ventanas** de [windowDeg] grados alrededor del punto: el EGM2008 pesa 150 MB descomprimido y no
 * cabe entero en el montón de un móvil. Mientras el punto siga dentro de la ventana no se vuelve a leer el fichero.
 *
 * @param bytes devuelve el fichero de la rejilla, o `null` si no está instalada.
 */
class GeoidFromGrid(
    override val name: String,
    override val attribution: String,
    private val windowDeg: Double = DEFAULT_WINDOW_DEG,
    private val bytes: () -> ByteArray?,
) : GeoidSource {
    private var requested: GridWindow? = null
    private var loaded: GeoTiffGrid? = null

    @Synchronized
    override fun undulationM(latitudeDeg: Double, longitudeDeg: Double): Double? {
        if (!latitudeDeg.isFinite() || !longitudeDeg.isFinite()) return null
        val window = requested
        if (window == null || latitudeDeg !in window.southDeg..window.northDeg || longitudeDeg !in window.westDeg..window.eastDeg) {
            val data = bytes() ?: return null
            val half = windowDeg / 2
            val next = GridWindow(latitudeDeg - half, longitudeDeg - half, latitudeDeg + half, longitudeDeg + half)
            requested = next
            loaded = GeoTiffGrid.readWindow(data, next)
        }
        return loaded?.interpolate(latitudeDeg, longitudeDeg)
    }

    companion object {
        const val DEFAULT_WINDOW_DEG = 2.0
    }
}
