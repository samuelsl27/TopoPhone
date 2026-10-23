package es.topophone.core.model.point

/** Cómo se calcula la punta del jalón con un receptor (S016/RF-1). Se elige para cada receptor. */
enum class TiltMode {
    /** La app calcula la punta con la inclinación del ETC; el receptor no compensa la posición (ADR-7). */
    APP,

    /** El receptor compensa la posición: la GGA ya es la punta (S016/RF-14). */
    RECEIVER,

    /** Jalón vertical, sin inclinación (S002/RF-32). */
    VERTICAL,
    ;

    /** "El modo usa la inclinación" (términos de S016). */
    val usesTilt: Boolean get() = this != VERTICAL
}

/**
 * Vector unitario del jalón en el plano tangente local, de la punta a la antena: (sen θ·sen α, sen θ·cos α, cos θ)
 * con θ la inclinación y α su dirección (ETC_rev_3). En un promediado, la media de los de las épocas usadas.
 */
data class PoleVector(val east: Double, val north: Double, val up: Double) {
    companion object {
        val VERTICAL = PoleVector(0.0, 0.0, 1.0)
    }
}

/**
 * La inclinación de un punto (S016/RF-22): inclinación, dirección y rumbo de la última época usada, la mayor
 * inclinación y las precisiones de la inclinación en planta y en vertical (las mayores de las épocas usadas), en
 * radianes y metros.
 *
 * @property pole media del vector del jalón, para corregir la altura del jalón a lo largo de él (S016/RF-25).
 * @property receiverPoleHeightM en "Compensada por el receptor", la altura del jalón declarada al medir (S016/RF-15).
 */
data class TiltAngles(
    val tiltRad: Double,
    val directionRad: Double,
    val headingRad: Double?,
    val maxTiltRad: Double,
    val horizontalAccuracyM: Double,
    val verticalAccuracyM: Double,
    val pole: PoleVector,
    val receiverPoleHeightM: Double? = null,
)

/**
 * El modo de inclinación con el que se midió un punto, si estaba comprobado con el asistente (S016/RF-10) y sus
 * ángulos, si las épocas usadas estaban en estado 30 (S016/RF-22).
 */
data class PointTilt(val mode: TiltMode, val verified: Boolean, val angles: TiltAngles?)
