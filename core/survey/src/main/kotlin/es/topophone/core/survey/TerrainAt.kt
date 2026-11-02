package es.topophone.core.survey

import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.surface.SurfaceHeight
import es.topophone.core.model.surface.SurfaceHeightSource
import kotlin.math.abs

/** De dónde sale la cota del terreno (S051/RF-6). */
enum class TerrainSource { PROFILE, SURFACE }

/** La cota del terreno en un punto y la fuente de la que sale. */
data class TerrainSample(val heightM: Double, val source: TerrainSource)

/**
 * La cota del terreno natural en un PK y un desplazamiento (S051/RF-6): del perfil de terreno de S050 de ese PK, interpolado con el PK
 * entre los dos perfiles más próximos que lo rodean; si no hay o el desplazamiento queda fuera del perfil, de la superficie activa
 * (S042) sin su desplazamiento vertical, que es de diseño; si tampoco, no hay terreno.
 */
class TerrainAt(
    grounds: List<CrossSection>,
    private val geometry: AlignmentGeometry,
    private val profile: VerticalProfileEval?,
    private val surface: SurfaceHeightSource?,
) {
    private val grounds = grounds.filter { it.kind == SectionKind.GROUND }.sortedBy { it.chainage }

    fun heightAt(chainageM: Double, offsetM: Double): TerrainSample? {
        fromProfiles(chainageM, offsetM)?.let { return TerrainSample(it, TerrainSource.PROFILE) }
        val point = geometry.pointAt(chainageM, offsetM) ?: return null
        val height = surface?.heightAt(point.easting, point.northing) as? SurfaceHeight.Value ?: return null
        return TerrainSample(height.metres, TerrainSource.SURFACE)
    }

    private fun fromProfiles(chainageM: Double, offsetM: Double): Double? {
        if (grounds.isEmpty()) return null
        grounds.firstOrNull { abs(it.chainage - chainageM) <= EPS }?.let { return heightOf(it, offsetM) }
        val after = grounds.firstOrNull { it.chainage > chainageM } ?: return null
        val before = grounds.lastOrNull { it.chainage < chainageM } ?: return null
        val a = heightOf(before, offsetM) ?: return null
        val b = heightOf(after, offsetM) ?: return null
        return a + (b - a) * (chainageM - before.chainage) / (after.chainage - before.chainage)
    }

    /** La cota absoluta de un perfil en el desplazamiento, lineal entre sus puntos; null si queda fuera de sus puntos. */
    private fun heightOf(section: CrossSection, offsetM: Double): Double? {
        val points = section.points
        if (points.size < 2 || offsetM < points.first().offsetM - EPS || offsetM > points.last().offsetM + EPS) return null
        val i = (0 until points.size - 1).firstOrNull { offsetM <= points[it + 1].offsetM } ?: points.size - 2
        val a = points[i]
        val b = points[i + 1]
        val z = if (b.offsetM ==
            a.offsetM
        ) {
            a.heightM
        } else {
            a.heightM + (b.heightM - a.heightM) * (offsetM - a.offsetM) / (b.offsetM - a.offsetM)
        }
        return if (section.heightKind == HeightKind.ABSOLUTE) z else profile?.at(section.chainage)?.elevation?.plus(z)
    }

    private companion object {
        const val EPS = 1e-9
    }
}
