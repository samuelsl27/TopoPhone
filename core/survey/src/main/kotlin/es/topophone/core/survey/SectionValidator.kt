package es.topophone.core.survey

import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.SectionCode
import es.topophone.core.model.alignment.section.SectionIssue
import es.topophone.core.model.alignment.section.SectionSlope
import es.topophone.core.model.alignment.section.SectionTemplate
import es.topophone.core.model.alignment.section.SuperelevationRow

/** Comprueba una sección o una sección tipo antes de guardarla (S050/RF-7). Devuelve el primer motivo o null. */
object SectionValidator {
    /** Holgura para un PK en el extremo del eje, por redondeo del fichero. */
    private const val CHAINAGE_TOLERANCE_M = 1e-6

    /** Una sección explícita de diseño o de terreno en un eje que va del PK [axisStart] al [axisEnd]. */
    fun validate(section: CrossSection, axisStart: Double, axisEnd: Double): SectionIssue? {
        chainageIssue(section.chainage, axisStart, axisEnd)?.let { return it }
        val seen = HashSet<String>()
        var previous = Double.NEGATIVE_INFINITY
        for (p in section.points) {
            if (!SectionCode.isValid(p.code)) return SectionIssue.InvalidCode(p.code)
            if (!seen.add(p.code)) return SectionIssue.RepeatedCode(p.code)
            if (!(p.offsetM > previous)) return SectionIssue.Unordered(p.code)
            previous = p.offsetM
        }
        return null
    }

    /** Una sección tipo; [others] son las ya guardadas del eje, con las que no puede solaparse (plan D1). */
    fun validate(template: SectionTemplate, axisStart: Double, axisEnd: Double, others: List<SectionTemplate>): SectionIssue? {
        chainageIssue(template.fromChainage, axisStart, axisEnd)?.let { return it }
        chainageIssue(template.toChainage, axisStart, axisEnd)?.let { return it }
        if (template.toChainage < template.fromChainage) return SectionIssue.ReversedRange(template.fromChainage, template.toChainage)
        val seen = hashSetOf(SectionCode.AXIS)
        for (s in template.left + template.right) {
            if (!SectionCode.isValid(s.code)) return SectionIssue.InvalidCode(s.code)
            if (!seen.add(s.code)) return SectionIssue.RepeatedCode(s.code)
            if (!(s.widthM > 0.0)) return SectionIssue.Unordered(s.code)
            if (!SectionSlope.isValid(s.slope)) return SectionIssue.SlopeTooSteep(s.code, s.slope)
        }
        if (template.gradeCode !in seen) return SectionIssue.UnknownGradeCode(template.gradeCode)
        for (o in others) {
            if (template.fromChainage < o.toChainage && o.fromChainage < template.toChainage) {
                return SectionIssue.OverlappingTemplates(template.fromChainage, template.toChainage)
            }
        }
        return null
    }

    internal fun chainageIssue(chainage: Double, axisStart: Double, axisEnd: Double): SectionIssue? =
        if (!chainage.isFinite() || chainage < axisStart - CHAINAGE_TOLERANCE_M || chainage > axisEnd + CHAINAGE_TOLERANCE_M) {
            SectionIssue.ChainageOutsideAxis(chainage)
        } else {
            null
        }
}

/** Comprueba la tabla de peralte antes de guardarla (S050/RF-7). */
object SuperelevationValidator {
    fun validate(rows: List<SuperelevationRow>, axisStart: Double, axisEnd: Double): SectionIssue? {
        val seen = HashSet<Double>()
        for (r in rows) {
            SectionValidator.chainageIssue(r.chainage, axisStart, axisEnd)?.let { return it }
            if (!seen.add(r.chainage)) return SectionIssue.RepeatedChainage(r.chainage)
            if (!SectionSlope.isValid(r.leftSlope)) return SectionIssue.SlopeTooSteep("", r.leftSlope)
            if (!SectionSlope.isValid(r.rightSlope)) return SectionIssue.SlopeTooSteep("", r.rightSlope)
        }
        return null
    }
}
