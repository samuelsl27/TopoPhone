package es.topophone.core.survey

import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionCode
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.alignment.section.SectionPoint

/** Un punto medido del perfil transversal: su desplazamiento, su cota y, si el usuario lo dio, su código de sección (S052/RF-17). */
data class CrossInput(val offsetM: Double, val heightM: Double?, val code: String? = null)

sealed interface CrossProfileResult {
    /** El perfil de terreno del PK, listo para guardarse como el de S050. */
    data class Built(val section: CrossSection) : CrossProfileResult

    data object NoPoints : CrossProfileResult

    /** El punto de este desplazamiento no tiene cota. */
    data class MissingHeight(val offsetM: Double) : CrossProfileResult

    /** Dos puntos tienen el mismo desplazamiento: una sección no puede tener dos cotas en el mismo sitio (S050/RF-7). */
    data class SameOffset(val offsetM: Double) : CrossProfileResult
}

/**
 * De los puntos de un perfil transversal al perfil de terreno del PK (S052/RF-17, RF-19, RF-20; plan A3): ordenados por desplazamiento,
 * con la cota absoluta. El código es el que dio el usuario si es válido y no se repite; si no, el siguiente `P1`, `P2`… libre
 * ([SectionCode.generated]).
 */
object CrossProfileBuilder {
    fun build(chainageM: Double, inputs: List<CrossInput>): CrossProfileResult {
        if (inputs.isEmpty()) return CrossProfileResult.NoPoints
        val sorted = inputs.sortedBy { it.offsetM }
        for ((i, p) in sorted.withIndex()) {
            if (p.heightM == null) return CrossProfileResult.MissingHeight(p.offsetM)
            if (i > 0 && p.offsetM == sorted[i - 1].offsetM) return CrossProfileResult.SameOffset(p.offsetM)
        }
        val used = HashSet<String>()
        val wanted = sorted.map { p -> p.code?.takeIf { SectionCode.isValid(it) && used.add(it) } }
        var next = 0
        val points = sorted.mapIndexed { i, p ->
            val code = wanted[i] ?: generateSequence { SectionCode.generated(next++) }.first { used.add(it) }
            SectionPoint(code, p.offsetM, p.heightM!!)
        }
        return CrossProfileResult.Built(CrossSection(SectionKind.GROUND, chainageM, HeightKind.ABSOLUTE, points))
    }
}
