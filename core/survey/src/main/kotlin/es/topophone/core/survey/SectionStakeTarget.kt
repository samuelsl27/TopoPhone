package es.topophone.core.survey

import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.alignment.section.SectionTemplate
import es.topophone.core.model.alignment.section.SuperelevationRow

/** El punto de diseño de un replanteo por PK y código, o por qué no lo hay (S050/RF-16, RF-18, RF-21). */
sealed interface SectionStakeResult {
    /**
     * @property target el punto de diseño como en un replanteo por PK (S054): la planta con el desplazamiento de la sección, el
     *   azimut del eje y la cota de la sección más el espesor; sin cota si no la hay (RF-11).
     * @property slope la pendiente transversal en el punto, positiva si baja al alejarse del eje (plan D2).
     */
    data class Target(val code: String, val target: AxisStakeResult.Target, val slope: Double?) : SectionStakeResult

    /** El código no existe en la sección de ese PK (RF-21). */
    data class NoCode(val code: String, val chainageM: Double) : SectionStakeResult

    /** El PK queda fuera del rango de las secciones (RF-21). */
    data class OutsideRange(val chainageM: Double) : SectionStakeResult

    /** El PK queda entre dos secciones que no se pueden interpolar: los códigos que faltan (RF-12, RF-21). */
    data class Missing(val chainageM: Double, val codes: List<String>, val heightKindMismatch: Boolean) : SectionStakeResult

    /** El PK no cabe en el eje: su PK inicial y su final. */
    data class OutsideAxis(val startM: Double, val endM: Double) : SectionStakeResult
}

/** El punto de diseño de un PK, un código de sección y un espesor (S050/RF-16, RF-18, RF-21; plan A5). */
object SectionStakeTarget {
    fun of(
        geometry: AlignmentGeometry,
        profile: VerticalProfileEval?,
        resolver: SectionResolver,
        chainageM: Double,
        code: String,
        thicknessM: Double = 0.0,
    ): SectionStakeResult {
        val section = when (val r = resolver.at(chainageM)) {
            is SectionResolution.Found -> r.section
            is SectionResolution.Missing -> return SectionStakeResult.Missing(chainageM, r.codes, r.heightKindMismatch)
            SectionResolution.OutsideRange -> return SectionStakeResult.OutsideRange(chainageM)
        }
        return when (val v = SectionEval.byCode(section, geometry, profile, code)) {
            SectionPointResult.NoCode -> SectionStakeResult.NoCode(code, chainageM)
            SectionPointResult.OutsideAxis -> SectionStakeResult.OutsideAxis(geometry.startChainage, geometry.endChainage)
            SectionPointResult.OutsideSection -> SectionStakeResult.NoCode(code, chainageM)
            is SectionPointResult.Value -> SectionStakeResult.Target(
                code,
                AxisStakeResult.Target(
                    chainageM,
                    v.offsetM,
                    thicknessM,
                    v.axisPoint,
                    profile?.at(chainageM)?.elevation,
                    v.elevationM?.plus(thicknessM),
                ),
                v.slope,
            )
        }
    }
}

/** Los PK donde cambia la sección o el peralte de un eje, que se añaden a los puntos singulares de S054 (S050/RF-17). */
object SectionKeyStations {
    /** Dos PK a menos de esto son el mismo. */
    private const val SAME_M = KeyStations.SAME_M

    /**
     * Los extremos de cada sección tipo, el PK de cada sección explícita de diseño y el de cada fila del peralte, por orden y sin
     * repetidos a menos de 1 mm.
     */
    fun of(templates: List<SectionTemplate>, sections: List<CrossSection>, superelevation: List<SuperelevationRow>): List<Double> {
        val all = templates.flatMap { listOf(it.fromChainage, it.toChainage) } +
            sections.filter { it.kind == SectionKind.DESIGN }.map { it.chainage } +
            superelevation.map { it.chainage }
        return all.sorted().fold(ArrayList<Double>()) { out, pk ->
            if (out.isEmpty() || pk - out.last() >= SAME_M) out += pk
            out
        }
    }
}
