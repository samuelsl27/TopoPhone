package es.topophone.core.survey

import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.alignment.section.SectionPoint
import es.topophone.core.model.surface.SurfaceHeight
import es.topophone.core.model.surface.SurfaceHeightSource

/** Un caso de `fixtures/ejes/taludes/casos.csv` (S051): lo calcula `tools/ejes/generar_taludes.py` con fracciones. */
/** [height] es la cota del punto de talud y [slopeHeight] la altura de talud (diferencia con la cota del borde). */
class SlopeCase(
    val name: String,
    val terrain: String,
    val rightSide: Boolean,
    val chainage: Double,
    val edgeOffset: Double,
    val edgeHeight: Double,
    val cutRatio: Double,
    val fillRatio: Double,
    val state: String,
    val kind: String,
    val t: Double?,
    val offset: Double?,
    val height: Double?,
    val slopeHeight: Double?,
    val easting: Double?,
    val northing: Double?,
)

/** Los perfiles, el TIN y los casos de `fixtures/ejes/taludes/` (S051). */
object SlopeFixtures {
    private fun rows(name: String) = SurveyFixtures.rows("taludes/$name", "ejes")

    private fun number(text: String) = text.takeIf { it.isNotEmpty() }?.toDouble()

    fun cases(): List<SlopeCase> = rows("casos.csv").map {
        SlopeCase(
            it[0], it[1], it[2] == "derecha", it[3].toDouble(), it[4].toDouble(), it[5].toDouble(), it[6].toDouble(), it[7].toDouble(),
            it[8], it[9], number(it[10]), number(it[11]), number(it[12]), number(it[13]), number(it[14]), number(it[15]),
        )
    }

    /** El perfil [name] como una sección de terreno absoluta en el PK [chainage], con [lift] metros más de cota. */
    fun ground(name: String, chainage: Double, lift: Double = 0.0): CrossSection {
        val points = rows("perfiles.csv").filter { it[0] == name }.map { SectionPoint("T", it[1].toDouble(), it[2].toDouble() + lift) }
        return CrossSection(SectionKind.GROUND, chainage, HeightKind.ABSOLUTE, points.sortedBy { it.offsetM })
    }

    /** El TIN como una fuente de cotas: busca el triángulo que contiene el punto, o dice que está fuera. */
    fun tin(): SurfaceHeightSource {
        val triangles = rows("tin.csv").map { r -> r.map { it.toDouble() } }
        return SurfaceHeightSource { x, y ->
            triangles.firstNotNullOfOrNull { t ->
                val (x1, y1, z1, x2, y2, z2, x3, y3, z3) = t
                val den = (y2 - y3) * (x1 - x3) + (x3 - x2) * (y1 - y3)
                val a = ((y2 - y3) * (x - x3) + (x3 - x2) * (y - y3)) / den
                val b = ((y3 - y1) * (x - x3) + (x1 - x3) * (y - y3)) / den
                val c = 1 - a - b
                if (a >= -1e-12 && b >= -1e-12 && c >= -1e-12) SurfaceHeight.Value(a * z1 + b * z2 + c * z3) else null
            } ?: SurfaceHeight.Outside
        }
    }
}

private operator fun <T> List<T>.component6() = this[5]

private operator fun <T> List<T>.component7() = this[6]

private operator fun <T> List<T>.component8() = this[7]

private operator fun <T> List<T>.component9() = this[8]
