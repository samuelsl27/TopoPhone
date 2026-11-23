package es.topophone.core.model.localization

import kotlin.math.hypot

/** Los límites y los valores por defecto de la localización (S039/RF-4, RF-13). */
object LocalizationLimits {
    /** Pares de un borrador (RF-4). */
    const val MAX_PAIRS = 50

    /** Residuo horizontal o vertical a partir del cual se destaca, en metros (RF-13). */
    const val DEFAULT_RESIDUAL_LIMIT_M = 0.03

    /** Desviación de la escala respecto de 1 a partir de la cual se avisa (RF-13). */
    const val DEFAULT_SCALE_LIMIT = 0.001
}

/** En qué estado está una localización del proyecto: el borrador, la activa o una retirada o sustituida (S039/RF-16, RF-21). */
enum class LocalizationStatus { DRAFT, ACTIVE, ARCHIVED }

/** De dónde salió una localización: de pares medidos o de un SC guardado en «Mis SC» (S039/RF-28). */
enum class LocalizationOrigin { PAIRS, SAVED_CRS }

/** La parte vertical de una localización: ninguna, un desplazamiento o un plano inclinado (S039/RF-9). */
enum class VerticalKind { NONE, OFFSET, PLANE }

/**
 * Los parámetros de una localización (S039/RF-7 a RF-9), respecto a los centroides de los puntos GNSS en el SC sin localizar
 * ([centreE], [centreN]) y de sus coordenadas conocidas ([knownCentreE], [knownCentreN]):
 * `[E' N'] = [knownCentre] + scale · R(rotationRad) · ([E N] − [centre])`. La parte vertical es
 * `h' = h + dh + slopeE · (E − verticalE) + slopeN · (N − verticalN)`. Todo en metros y radianes, sin redondear.
 */
data class LocalizationParams(
    val centreE: Double = 0.0,
    val centreN: Double = 0.0,
    val knownCentreE: Double = 0.0,
    val knownCentreN: Double = 0.0,
    val rotationRad: Double = 0.0,
    val scale: Double = 1.0,
    val hasHorizontal: Boolean = false,
    val verticalKind: VerticalKind = VerticalKind.NONE,
    val dh: Double = 0.0,
    val slopeE: Double = 0.0,
    val slopeN: Double = 0.0,
    val verticalE: Double = 0.0,
    val verticalN: Double = 0.0,
) {
    /** La escala en partes por millón sobre 1 (S039/RF-10). */
    val scalePpm: Double get() = (scale - 1.0) * PPM

    /** La pendiente del plano vertical en partes por millón (S039/RF-10). */
    val slopePpm: Double get() = hypot(slopeE, slopeN) * PPM

    /** La traslación del centroide: lo que se mueve el centroide de los puntos GNSS (S039/RF-10). */
    val translationE: Double get() = knownCentreE - centreE
    val translationN: Double get() = knownCentreN - centreN

    /** Una localización sin parte horizontal ni vertical no cambia nada. */
    val isIdentity: Boolean get() = !hasHorizontal && verticalKind == VerticalKind.NONE

    /** Texto estable con los números sin redondear, para el historial y los paquetes. */
    fun toText(): String = listOf(
        centreE, centreN, knownCentreE, knownCentreN, rotationRad, scale, if (hasHorizontal) 1.0 else 0.0,
        verticalKind.ordinal.toDouble(), dh, slopeE, slopeN, verticalE, verticalN,
    ).joinToString(";")

    companion object {
        private const val PPM = 1.0e6
        private const val FIELDS = 13

        /** `null` si el texto no es de unos parámetros. */
        fun parseOrNull(text: String?): LocalizationParams? {
            if (text.isNullOrEmpty()) return null
            val v = text.split(';').map { it.toDoubleOrNull() ?: return null }
            if (v.size != FIELDS) return null
            val kind = VerticalKind.entries.getOrNull(v[7].toInt()) ?: return null
            return LocalizationParams(v[0], v[1], v[2], v[3], v[4], v[5], v[6] == 1.0, kind, v[8], v[9], v[10], v[11], v[12])
        }
    }
}

/**
 * Un par de localización (S039): el punto GNSS ([pointId], null si se borró; [pointName] copiado), su posición en el SC sin localizar al formar el
 * par ([scE], [scN], [scH]) y las coordenadas conocidas ([knownE], [knownN], [knownH]). [useHorizontal] y [useVertical] dicen qué componentes entran
 * en el cálculo (RF-5); [confirmed] es falso mientras un punto de baja calidad espera la confirmación del usuario (RF-6).
 */
data class LocalizationPair(
    val id: String,
    val pointId: String?,
    val pointName: String,
    val scE: Double,
    val scN: Double,
    val scH: Double?,
    val knownE: Double,
    val knownN: Double,
    val knownH: Double?,
    val useHorizontal: Boolean = true,
    val useVertical: Boolean = true,
    val confirmed: Boolean = true,
) {
    /** ¿Entra en el ajuste horizontal? */
    val countsHorizontal: Boolean get() = confirmed && useHorizontal

    /** ¿Entra en el ajuste vertical? Sin alturas no hay componente vertical. */
    val countsVertical: Boolean get() = confirmed && useVertical && scH != null && knownH != null
}
