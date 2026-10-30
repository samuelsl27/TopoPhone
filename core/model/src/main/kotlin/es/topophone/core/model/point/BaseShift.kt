package es.topophone.core.model.point

import es.topophone.core.model.project.CorrectionsDatum
import kotlin.math.sqrt

/** Vector ECEF (ΔX, ΔY, ΔZ) en metros, igual a la posición del punto conocido menos la medida (S020, «Desplazamiento de base»). */
data class BaseShiftVector(val dx: Double, val dy: Double, val dz: Double) {

    /** Módulo en metros. */
    val moduleM: Double get() = sqrt(dx * dx + dy * dy + dz * dz)
}

/** Qué hace la app con un desplazamiento según su módulo (S020/RF-5). */
enum class BaseShiftClass { OK, NEEDS_CONFIRMATION, REJECTED }

/** Los umbrales del módulo del desplazamiento (S020/RF-5). */
object BaseShiftLimits {
    /** Por encima de este módulo, en metros, se pide una confirmación aparte. */
    const val REVIEW_M = 10.0

    /** Por encima de este módulo, en metros, el desplazamiento no se permite. */
    const val REJECT_M = 1_000.0

    fun classify(moduleM: Double): BaseShiftClass = when {
        moduleM > REJECT_M -> BaseShiftClass.REJECTED
        moduleM > REVIEW_M -> BaseShiftClass.NEEDS_CONFIRMATION
        else -> BaseShiftClass.OK
    }
}

/**
 * Un desplazamiento calculado, de la lista del proyecto (S020/RF-12). Solo uno está [active] por proyecto.
 *
 * @property knownName el nombre del punto conocido, o el que se escribió con sus coordenadas.
 * @property knownPointId el punto del proyecto del que salen las coordenadas, si se eligió uno.
 * @property knownEasting Este del punto conocido en el SC del proyecto, sin redondear.
 * @property knownNorthing Norte del punto conocido en el SC del proyecto, sin redondear.
 * @property knownHeightM altura del punto conocido en el sistema de alturas del proyecto; null si no tiene (RF-6).
 * @property measured la posición medida, en el datum de las correcciones y sin desplazar.
 * @property epochsUsed épocas que entraron en el promediado de la medida.
 * @property horizontalOnly el vector no corrige la altura (RF-6).
 * @property correctionsDatum el datum de las correcciones con que se calculó (RF-16).
 * @property base la posición de la base cuando se calculó, para avisar si cambia (RF-15).
 */
data class BaseShift(
    val id: String,
    val projectId: String,
    val createdAtMs: Long,
    val fromReplay: Boolean,
    val knownName: String,
    val knownPointId: String?,
    val knownEasting: Double,
    val knownNorthing: Double,
    val knownHeightM: Double?,
    val measured: GeodeticPosition,
    val epochsUsed: Int,
    val vector: BaseShiftVector,
    val horizontalOnly: Boolean,
    val correctionsDatum: CorrectionsDatum,
    val active: Boolean,
    val base: GeodeticPosition?,
)

/**
 * Lo que un punto guarda del desplazamiento con que se midió (S020/RF-8): el cálculo, el punto conocido, el vector
 * y la punta del jalón sin desplazar. Sigue valiendo aunque se borre el cálculo (RF-9).
 */
data class AppliedShift(val shiftId: String, val knownName: String, val vector: BaseShiftVector, val unshiftedGround: GeodeticPosition)
