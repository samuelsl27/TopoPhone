package es.topophone.core.survey

import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.slope.SlopeRule
import es.topophone.core.model.alignment.slope.SlopeSide

/** El borde de explanación de un PK y un lado, o por qué no se puede calcular (S051/RF-5, RF-8). */
sealed interface SlopeEdgeResult {
    /** El borde: su desplazamiento con signo, su cota absoluta y su punto en planta; [rule] es el talud que se le aplica. */
    data class Edge(val rule: SlopeRule, val chainageM: Double, val offsetM: Double, val heightM: Double, val point: AxisPoint) :
        SlopeEdgeResult

    /** No hay talud definido para ese lado en ese PK. */
    data object NoRule : SlopeEdgeResult

    /** El talud no trae borde manual y ese PK no tiene sección de diseño. */
    data object NoSection : SlopeEdgeResult

    /** El código del borde no existe en la sección de ese PK o queda en el otro lado. */
    data class NoEdge(val code: String) : SlopeEdgeResult

    /** La cota es relativa a la rasante y el eje no la tiene en ese PK. */
    data object NoHeight : SlopeEdgeResult

    /** El PK queda fuera del eje. */
    data object OutsideAxis : SlopeEdgeResult
}

/**
 * El borde de explanación de un PK y un lado (S051/RF-1, RF-2, RF-5; plan A1): el borde manual del talud si lo trae; si no, el punto de
 * la sección de S050 con el código elegido o, por defecto, el más exterior del lado.
 */
object SlopeEdge {
    /** Un desplazamiento y una cota absoluta, o el motivo por el que no hay. */
    private class Raw(val offsetM: Double, val heightM: Double, val failure: SlopeEdgeResult?)

    private fun failed(result: SlopeEdgeResult) = Raw(0.0, 0.0, result)

    fun of(
        rules: List<SlopeRule>,
        side: SlopeSide,
        chainageM: Double,
        geometry: AlignmentGeometry,
        profile: VerticalProfileEval?,
        resolver: SectionResolver?,
    ): SlopeEdgeResult {
        val rule = rules.firstOrNull { it.side == side && it.contains(chainageM) } ?: return SlopeEdgeResult.NoRule
        val ground = profile?.at(chainageM)?.elevation
        val raw = rule.manualEdge?.let { manual ->
            val z = if (manual.heightKind == HeightKind.ABSOLUTE) manual.heightM else ground?.plus(manual.heightM)
            if (z == null) failed(SlopeEdgeResult.NoHeight) else Raw(manual.offsetM, z, null)
        } ?: sectionEdge(rule, chainageM, ground, resolver)
        raw.failure?.let { return it }
        val point = geometry.pointAt(chainageM, raw.offsetM) ?: return SlopeEdgeResult.OutsideAxis
        return SlopeEdgeResult.Edge(rule, chainageM, raw.offsetM, raw.heightM, point)
    }

    private fun sectionEdge(rule: SlopeRule, chainageM: Double, ground: Double?, resolver: SectionResolver?): Raw {
        val section = (resolver?.at(chainageM) as? SectionResolution.Found)?.section ?: return failed(SlopeEdgeResult.NoSection)
        val onSide = section.points.filter { it.offsetM * rule.side.sign > 0.0 }
        val code = rule.edgeCode
        val point = if (code != null) onSide.firstOrNull { it.code == code } else onSide.maxByOrNull { it.offsetM * rule.side.sign }
        point ?: return failed(SlopeEdgeResult.NoEdge(code.orEmpty()))
        val z = if (section.heightKind == HeightKind.ABSOLUTE) point.heightM else ground?.plus(point.heightM)
        return if (z == null) failed(SlopeEdgeResult.NoHeight) else Raw(point.offsetM, z, null)
    }
}
