package es.topophone.core.model.alignment.section

/** Si la cota de los puntos de una sección es una diferencia con la rasante o una cota absoluta (S050/RF-4, RF-11; plan D4). */
enum class HeightKind { RELATIVE, ABSOLUTE }

/** Una sección de diseño o un perfil de terreno (S050, Términos; plan D5). */
enum class SectionKind { DESIGN, GROUND }

/** El nombre de un punto de sección: hasta [MAX_LENGTH] caracteres, independiente de los códigos de levantamiento (S050/Términos). */
object SectionCode {
    const val MAX_LENGTH = 12

    /** El código del eje, implícito en una sección tipo y punto de rasante por omisión. */
    const val AXIS = "EJE"

    fun isValid(code: String): Boolean =
        code.isNotBlank() && code.length <= MAX_LENGTH && code == code.trim() && code.none { it.isISOControl() }

    /** El código que se asigna a un punto sin nombre, en orden de desplazamiento: P1, P2… (S050/RF-5). */
    fun generated(index: Int): String = "P${index + 1}"
}

/** Pendiente transversal: fracción (0,02 = 2 %), positiva si el terreno **baja al alejarse del eje** en ese lado (S050, plan D2). */
object SectionSlope {
    /** El módulo máximo admitido, 100 % (S050/RF-7). */
    const val MAX_ABS = 1.0

    fun fromPercent(percent: Double): Double = percent / 100.0

    fun toPercent(slope: Double): Double = slope * 100.0

    fun isValid(slope: Double): Boolean = slope.isFinite() && kotlin.math.abs(slope) <= MAX_ABS
}

/**
 * Un punto de una sección: su código, su desplazamiento con signo (negativo a la izquierda del eje, S049) y su cota, que según el
 * [HeightKind] de la sección es una diferencia con la rasante o absoluta. Sin redondear (S050/RF-8).
 */
data class SectionPoint(val code: String, val offsetM: Double, val heightM: Double)

/**
 * Una sección en un PK: de diseño o un perfil de terreno. Que los puntos estén ordenados y sin códigos repetidos lo comprueba el
 * validador de `core:survey` (S050/RF-7).
 */
data class CrossSection(val kind: SectionKind, val chainage: Double, val heightKind: HeightKind, val points: List<SectionPoint>)

/** Un tramo de una sección tipo desde el eje hacia un lado: ancho, pendiente y si sigue el peralte (S050/RF-2). */
data class TemplateSegment(val code: String, val widthM: Double, val slope: Double, val followsSuperelevation: Boolean)

/** Una sección tipo aplicada entre [fromChainage] y [toChainage] (extremos incluidos), con su punto de rasante (S050/RF-2). */
data class SectionTemplate(
    val fromChainage: Double,
    val toChainage: Double,
    val gradeCode: String,
    val left: List<TemplateSegment>,
    val right: List<TemplateSegment>,
)

/** Una fila de la tabla de peralte: las pendientes de cada lado en un PK (S050/RF-6). */
data class SuperelevationRow(val chainage: Double, val leftSlope: Double, val rightSlope: Double)

/** La tabla de peralte de un eje, por PK creciente (S050/RF-6). */
data class Superelevation(val rows: List<SuperelevationRow>)
