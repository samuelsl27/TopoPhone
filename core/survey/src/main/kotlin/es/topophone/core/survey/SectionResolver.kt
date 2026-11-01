package es.topophone.core.survey

import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.alignment.section.SectionPoint
import es.topophone.core.model.alignment.section.SectionTemplate
import es.topophone.core.model.alignment.section.Superelevation

/** La sección de un PK, o por qué no la hay (S050/RF-12, RF-21). */
sealed interface SectionResolution {
    /** La sección en el PK pedido (con su PK, no el de la sección guardada de la que sale). */
    data class Found(val section: CrossSection) : SectionResolution

    /**
     * El PK está entre dos secciones que no se pueden interpolar: [codes] son los que no tienen las dos (ordenados) y
     * [heightKindMismatch] dice que una es de cota absoluta y la otra relativa (plan D4).
     */
    data class Missing(val codes: List<String>, val heightKindMismatch: Boolean) : SectionResolution

    /** El PK queda fuera del rango de las secciones. */
    data object OutsideRange : SectionResolution
}

/**
 * La sección de diseño que vale en cada PK de un eje (S050/RF-3, RF-12; plan A2, D1): manda la explícita si el PK cae entre la primera y
 * la última (se interpola con el PK); si no, la sección tipo cuyo rango lo contiene; si no, no hay sección. Los perfiles de terreno no
 * cuentan.
 */
class SectionResolver(templates: List<SectionTemplate>, private val superelevation: Superelevation?, sections: List<CrossSection>) {
    private val templates = templates.sortedBy { it.fromChainage }
    private val designs = sections.filter { it.kind == SectionKind.DESIGN }.sortedBy { it.chainage }

    /** Los PK del rango [from, to] donde cambia la sección: los extremos de las secciones tipo y los de las explícitas de diseño (S050/RF-14). */
    fun keyStations(from: Double, to: Double): List<Double> =
        (templates.flatMap { listOf(it.fromChainage, it.toChainage) } + designs.map { it.chainage }).filter { it in from..to }

    fun at(chainage: Double): SectionResolution {
        explicitAt(chainage)?.let { return it }
        val template = templates.firstOrNull { chainage >= it.fromChainage - EPS && chainage <= it.toChainage + EPS }
        if (template == null) return SectionResolution.OutsideRange
        return SectionResolution.Found(SectionBuilder.build(template, superelevation, chainage))
    }

    private fun explicitAt(chainage: Double): SectionResolution? {
        if (designs.isEmpty() || chainage < designs.first().chainage - EPS || chainage > designs.last().chainage + EPS) return null
        designs.firstOrNull { kotlin.math.abs(it.chainage - chainage) <= EPS }?.let { return SectionResolution.Found(it) }
        val i = designs.indexOfFirst { it.chainage > chainage }
        val a = designs[i - 1]
        val b = designs[i]
        val codesA = a.points.map { it.code }.toSet()
        val codesB = b.points.map { it.code }.toSet()
        val mismatch = a.heightKind != b.heightKind
        if (codesA != codesB || mismatch) {
            return SectionResolution.Missing((codesA union codesB).minus(codesA intersect codesB).sorted(), mismatch)
        }
        val t = (chainage - a.chainage) / (b.chainage - a.chainage)
        val byCode = b.points.associateBy { it.code }
        val points = a.points.map { p ->
            val q = byCode.getValue(p.code)
            SectionPoint(p.code, p.offsetM + (q.offsetM - p.offsetM) * t, p.heightM + (q.heightM - p.heightM) * t)
        }
        return SectionResolution.Found(CrossSection(SectionKind.DESIGN, chainage, a.heightKind, points))
    }

    private companion object {
        const val EPS = 1e-9
    }
}
