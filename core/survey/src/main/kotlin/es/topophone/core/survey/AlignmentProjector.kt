package es.topophone.core.survey

import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.AlignmentExtent
import es.topophone.core.model.alignment.AlignmentIssue
import es.topophone.core.model.alignment.ProfileOmission
import es.topophone.core.model.alignment.VerticalCurve
import es.topophone.core.model.alignment.VerticalVertex
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadImportOptions
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Un eje tal como sale de un fichero o de la definición a mano, en su unidad y su sistema (S049/RF-4, RF-5): [declaredEnds] son los finales
 * (Este, Norte) que declara el fichero, uno por tramo, si los trae; [profileOmission] es por qué el fichero trae una rasante que no se usa.
 */
class AlignmentDraft(
    val elements: List<AlignmentElement>,
    val declaredEnds: List<Pair<Double, Double>?>? = null,
    val profile: List<VerticalVertex>? = null,
    val profileOmission: ProfileOmission? = null,
)

sealed interface AlignmentProjection {
    /**
     * El eje en el sistema y en los metros del proyecto, sin redondear. [profile] es null si el eje no trae rasante o no vale
     * ([profileOmission] dice por qué). [approximated] es que el eje se guardó como rectas cortas (decisión D2).
     */
    class Converted(
        val elements: List<AlignmentElement>,
        val profile: List<VerticalVertex>?,
        val profileOmission: ProfileOmission?,
        val extent: AlignmentExtent,
        val approximated: Boolean,
    ) : AlignmentProjection

    class Rejected(val issue: AlignmentIssue) : AlignmentProjection
}

/**
 * Del eje de un fichero al del proyecto (S049/RF-5, RF-10 a RF-12, plan A6, decisión D2):
 * 1. unidad a metros (RNF-T9; la cota solo cambia de unidad, como en S040);
 * 2. los tramos tienen que encadenarse y acabar donde el fichero dice (RF-5);
 * 3. si el eje está en el sistema del proyecto se guarda tal cual, tras comprobar el ámbito en un muestreo de todo el eje (RF-12);
 *    si está en otro, se muestrea con una flecha de 0,5 mm, cada punto pasa por la misma ruta que el [CadProjector] de S040 y se guardan
 *    rectas cortas; el PK es la longitud medida en el plano del proyecto desde el PK inicial (RF-11);
 * 4. la rasante se valida contra el PK del eje (RF-10): una que no vale se omite, con su motivo.
 */
class AlignmentProjector(
    private val crs: ProjectCrs,
    private val source: CadSourceCrs = CadSourceCrs.Project,
    private val unit: LinearUnit = LinearUnit.METRE,
    private val calculator: ProjectPositionCalculator = ProjectPositionCalculator(),
) {
    private val metres = unit.metres

    fun convert(draft: AlignmentDraft): AlignmentProjection {
        if (crs.utmZone == null) return AlignmentProjection.Rejected(AlignmentIssue.GeographicProject)
        val scaled = draft.elements.map { scale(it) }
        val ends = draft.declaredEnds?.map { end -> end?.let { it.first * metres to it.second * metres } }
        val problem = if (ends != null) AlignmentValidator.validate(scaled, ends) else AlignmentValidator.validate(scaled)
        if (problem != null) return AlignmentProjection.Rejected(problem)
        val profile = draft.profile?.map { scale(it) }

        val projector = CadProjector(crs, CadImportOptions(source = source, unit = LinearUnit.METRE, useElevations = false), calculator)
        return if (source == CadSourceCrs.Project) {
            val samples = AlignmentSampler.sample(scaled, EXTENT_SAGITTA_M, CHECK_STEP_M)
            if (!inside(projector, samples)) return AlignmentProjection.Rejected(AlignmentIssue.OutsideCrsArea)
            finish(scaled, profile, draft.profileOmission, samples, approximated = false)
        } else {
            val samples = AlignmentSampler.sample(scaled, APPROXIMATION_SAGITTA_M, MAX_LINE_STEP_M)
            val converted = convertSamples(projector, samples) ?: return AlignmentProjection.Rejected(AlignmentIssue.OutsideCrsArea)
            val (elements, oldToNew) = rebuild(scaled.first().startChainage, samples, converted)
            val remapped = profile?.let { remap(it, samples, oldToNew) }
            finish(
                elements,
                remapped,
                draft.profileOmission,
                AlignmentSampler.sample(elements, EXTENT_SAGITTA_M, CHECK_STEP_M),
                approximated = true,
            )
        }
    }

    private fun finish(
        elements: List<AlignmentElement>,
        profile: List<VerticalVertex>?,
        omission: ProfileOmission?,
        samples: AxisSamples,
        approximated: Boolean,
    ): AlignmentProjection {
        var usable = profile
        var reason = omission
        if (usable != null) {
            val start = elements.first().startChainage
            val end = elements.last().endChainage
            val issue = ProfileValidator.validate(usable, start, end)
            if (issue != null) {
                usable = null
                reason = ProfileOmission.Invalid(issue)
            }
        }
        val extent = AlignmentExtent(samples.easting.min(), samples.easting.max(), samples.northing.min(), samples.northing.max())
        return AlignmentProjection.Converted(elements, usable?.sortedBy { it.chainage }, reason, extent, approximated)
    }

    private fun inside(projector: CadProjector, samples: AxisSamples): Boolean {
        val entity = CadEntity(CadEntityKind.POLYLINE, samples.easting, samples.northing, null, null)
        return projector.convert(entity) is CadProjection.Converted
    }

    private fun convertSamples(projector: CadProjector, samples: AxisSamples): CadEntity? {
        val entity = CadEntity(CadEntityKind.POLYLINE, samples.easting, samples.northing, null, null)
        return (projector.convert(entity) as? CadProjection.Converted)?.entity
    }

    /** Las rectas cortas entre los puntos convertidos y, por cada muestra, su PK nuevo (la longitud acumulada desde el PK inicial). */
    private fun rebuild(startChainage: Double, samples: AxisSamples, converted: CadEntity): Pair<List<AlignmentElement>, DoubleArray> {
        val elements = ArrayList<AlignmentElement>()
        val newChainage = DoubleArray(samples.size)
        newChainage[0] = startChainage
        var chainage = startChainage
        for (i in 1 until samples.size) {
            val dE = converted.easting[i] - converted.easting[i - 1]
            val dN = converted.northing[i] - converted.northing[i - 1]
            val length = hypot(dE, dN)
            if (length > 0.0) {
                elements += AlignmentElement.line(chainage, length, converted.easting[i - 1], converted.northing[i - 1], atan2(dE, dN))
                chainage += length
            }
            newChainage[i] = chainage
        }
        return elements to newChainage
    }

    /** Los vértices de la rasante en el PK nuevo, con los acuerdos escalados por lo que se alargó o acortó el eje. */
    private fun remap(vertices: List<VerticalVertex>, samples: AxisSamples, newChainage: DoubleArray): List<VerticalVertex> {
        val oldLength = samples.chainage.last() - samples.chainage.first()
        val ratio = if (oldLength > 0.0) (newChainage.last() - newChainage.first()) / oldLength else 1.0
        return vertices.map { v ->
            val curve = when (val c = v.curve) {
                VerticalCurve.None -> c
                is VerticalCurve.Parabola -> VerticalCurve.Parabola(c.length * ratio)
                is VerticalCurve.Circular -> VerticalCurve.Circular(c.radius, c.length * ratio)
            }
            VerticalVertex(newChainageAt(v.chainage, samples.chainage, newChainage), v.elevation, curve)
        }
    }

    private fun newChainageAt(old: Double, oldChainage: DoubleArray, newChainage: DoubleArray): Double {
        if (old <= oldChainage.first()) return newChainage.first() + (old - oldChainage.first())
        if (old >= oldChainage.last()) return newChainage.last() + (old - oldChainage.last())
        var low = 0
        var high = oldChainage.lastIndex
        while (high - low > 1) {
            val mid = (low + high) / 2
            if (oldChainage[mid] <= old) low = mid else high = mid
        }
        val t = (old - oldChainage[low]) / (oldChainage[high] - oldChainage[low])
        return newChainage[low] + t * (newChainage[high] - newChainage[low])
    }

    private fun scale(e: AlignmentElement): AlignmentElement = if (metres == 1.0) {
        e
    } else {
        e.copy(
            startChainage = e.startChainage * metres,
            length = e.length * metres,
            startEasting = e.startEasting * metres,
            startNorthing = e.startNorthing * metres,
            startCurvature = e.startCurvature / metres,
            endCurvature = e.endCurvature / metres,
        )
    }

    private fun scale(v: VerticalVertex): VerticalVertex = if (metres == 1.0) {
        v
    } else {
        val curve = when (val c = v.curve) {
            VerticalCurve.None -> c
            is VerticalCurve.Parabola -> VerticalCurve.Parabola(c.length * metres)
            is VerticalCurve.Circular -> VerticalCurve.Circular(c.radius * metres, c.length * metres)
        }
        VerticalVertex(v.chainage * metres, v.elevation * metres, curve)
    }

    internal companion object {
        /** La flecha del muestreo con el que se calcula la extensión y se comprueba el ámbito: la del mapa (1 cm). */
        const val EXTENT_SAGITTA_M = 0.01
        const val CHECK_STEP_M = 100.0

        /** La flecha con la que un eje de otro sistema se guarda como rectas (decisión D2) y su paso máximo en rectas. */
        const val APPROXIMATION_SAGITTA_M = 0.0005
        const val MAX_LINE_STEP_M = 250.0
    }
}
