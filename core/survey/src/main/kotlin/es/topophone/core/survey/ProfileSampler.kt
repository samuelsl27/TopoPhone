package es.topophone.core.survey

import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.survey.linear.SurveyFlag
import kotlin.math.abs

/**
 * Lo que se calcula de un punto medido al meterlo en un perfil (S052/RF-11, RF-17): su PK y su desplazamiento sobre el eje (positivo a la
 * derecha), la diferencia de cota con la rasante o la sección de diseño y la marca. Sin proyección sobre el eje no hay PK ni
 * desplazamiento. [dHeightM] es null si no hay cota medida o contra qué compararla.
 */
data class ProfileSample(val chainageM: Double?, val offsetM: Double?, val dHeightM: Double?, val flag: SurveyFlag)

/**
 * PK, desplazamiento, diferencia de cota y marca de un punto en un perfil (S052/RF-11, RF-12, RF-14, RF-17, RF-18; plan A2). La
 * posición es la del suelo, en el sistema del proyecto, y la proyección sobre el eje es la de [AlignmentGeometry.locate].
 */
object ProfileSampler {
    /**
     * Un punto del perfil longitudinal: la marca es `OUT_OF_AXIS` si su desplazamiento pasa de [toleranceM] y `NO_PROJECTION` si no cae
     * entre los extremos del eje; la diferencia de cota es la cota medida menos la de la rasante en su PK.
     */
    fun longitudinal(
        geometry: AlignmentGeometry,
        profile: VerticalProfileEval?,
        easting: Double,
        northing: Double,
        heightM: Double?,
        toleranceM: Double,
    ): ProfileSample {
        val at = geometry.locate(easting, northing) ?: return NO_PROJECTION
        val grade = profile?.at(at.chainage)?.elevation
        val flag = if (abs(at.offset) > toleranceM) SurveyFlag.OUT_OF_AXIS else SurveyFlag.NONE
        return ProfileSample(at.chainage, at.offset, if (heightM != null && grade != null) heightM - grade else null, flag)
    }

    /**
     * Un punto del perfil transversal del PK [sectionChainageM]: la marca es `OUT_OF_SECTION` si su error en PK (el PK del punto menos el
     * de la sección) pasa de [toleranceM]; la diferencia de cota es la cota medida menos la de la sección de diseño en su
     * desplazamiento, o null fuera de ella o sin [design].
     */
    fun cross(
        geometry: AlignmentGeometry,
        profile: VerticalProfileEval?,
        design: CrossSection?,
        sectionChainageM: Double,
        easting: Double,
        northing: Double,
        heightM: Double?,
        toleranceM: Double,
    ): ProfileSample {
        val at = geometry.locate(easting, northing) ?: return NO_PROJECTION
        val designHeight = design?.let { (SectionEval.byOffset(it, geometry, profile, at.offset) as? SectionPointResult.Value)?.elevationM }
        val flag = if (abs(at.chainage - sectionChainageM) > toleranceM) SurveyFlag.OUT_OF_SECTION else SurveyFlag.NONE
        return ProfileSample(at.chainage, at.offset, if (heightM != null && designHeight != null) heightM - designHeight else null, flag)
    }

    private val NO_PROJECTION = ProfileSample(null, null, null, SurveyFlag.NO_PROJECTION)
}
