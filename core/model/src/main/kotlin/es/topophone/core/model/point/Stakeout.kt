package es.topophone.core.model.point

import es.topophone.core.model.format.NumberInput
import es.topophone.core.model.project.LinearUnit
import java.math.BigInteger

/** Cómo guía el replanteo: con una flecha hacia el punto o con las diferencias de Norte y Este (S018/RF-5). */
enum class StakeMode { BY_DIRECTION, BY_DISTANCE }

/** De dónde sale el rumbo del usuario: del avance de la punta o del IMU del receptor (S018/RF-8). */
enum class HeadingChoice { WALKING, IMU }

/**
 * Modo de guía, origen del rumbo y tolerancias del replanteo, los mismos para todos los proyectos (S018/RF-5, RF-8,
 * RF-18; S019/RF-10). Las tolerancias van en metros, sea cual sea la unidad del proyecto: [toleranceM] es la horizontal y
 * [toleranceVM] la vertical.
 */
data class StakeoutSettings(
    val mode: StakeMode = StakeMode.BY_DIRECTION,
    val heading: HeadingChoice = HeadingChoice.WALKING,
    val toleranceM: Double = DEFAULT_TOLERANCE_M,
    val toleranceVM: Double = DEFAULT_TOLERANCE_V_M,
    /** El último modo del replanteo de una línea, el mismo para todos los proyectos (S041/RF-3). */
    val lineMode: LineStakeoutMode = LineStakeoutMode.DEFAULT,
) {
    /** La tolerancia dentro de [TOLERANCE_M]; 2,5 cm si no es un número. */
    val validToleranceM: Double get() = if (toleranceM.isNaN()) DEFAULT_TOLERANCE_M else toleranceM.coerceIn(TOLERANCE_M)

    /** La tolerancia vertical dentro de [TOLERANCE_M] (el mismo rango que la horizontal); 3 cm si no es un número (S019/RF-10). */
    val validToleranceVM: Double get() = if (toleranceVM.isNaN()) DEFAULT_TOLERANCE_V_M else toleranceVM.coerceIn(TOLERANCE_M)

    companion object {
        /** S018/RF-18: 2,5 cm. */
        const val DEFAULT_TOLERANCE_M = 0.025

        /** S019/RF-10: 3 cm. */
        const val DEFAULT_TOLERANCE_V_M = 0.03

        /** S018/RF-18: de 1 cm a 1 m. */
        val TOLERANCE_M = 0.01..1.0

        /** Los milímetros que se perdonan al límite al escribirlo redondeado en pies: 0,03 ft son 9,1 mm. */
        private const val ROUNDING_MARGIN_M = 0.001

        /** Los límites de la tolerancia en la unidad del proyecto, para enseñarlos (S018/RF-18). */
        fun toleranceRangeIn(unit: LinearUnit): ClosedFloatingPointRange<Double> =
            unit.fromMetres(TOLERANCE_M.start)..unit.fromMetres(TOLERANCE_M.endInclusive)

        /** La tolerancia escrita en la unidad del proyecto, en metros; null si no es un número o está fuera de límites. */
        fun parseTolerance(text: String, unit: LinearUnit): Double? {
            val metres = NumberInput.parse(text)?.let(unit::toMetres) ?: return null
            return when {
                metres in TOLERANCE_M -> metres
                metres < TOLERANCE_M.start && metres >= TOLERANCE_M.start - ROUNDING_MARGIN_M -> TOLERANCE_M.start
                metres > TOLERANCE_M.endInclusive && metres <= TOLERANCE_M.endInclusive + ROUNDING_MARGIN_M -> TOLERANCE_M.endInclusive
                else -> null
            }
        }
    }
}

/** El nombre de un punto replanteado: el del diseño con `_stkd`, y `_stkd2`, `_stkd3`… si ya existe (S018/RF-27). */
object StakeoutNames {
    private const val SUFFIX = "_stkd"

    /** El nombre libre del próximo replanteo del punto de diseño [design]. */
    fun next(design: String, taken: Set<String>): String {
        val base = PointNames.clean(design) + SUFFIX
        if (base !in taken) return base
        var n = 2
        while ("$base$n" in taken) n++
        return "$base$n"
    }

    /** El nombre libre del próximo replanteo de un elemento de la capa [layer] de un dibujo: `capa_n_stkd` (S041/RF-27). */
    fun nextForLayer(layer: String, taken: Set<String>): String {
        val clean = PointNames.clean(layer)
        var n = 1
        while ("${clean}_${n}$SUFFIX" in taken) n++
        return "${clean}_${n}$SUFFIX"
    }

    private val NUMBERED = Regex("""_stkd(\d*)$""")

    /**
     * El número de replanteo de [name]: 1 para `_stkd`, 2 para `_stkd2`… (S019/RF-21). Null si el nombre no acaba así
     * (un punto editado) o el número no cabe en un entero.
     */
    fun number(name: String): Int? {
        val digits = NUMBERED.find(name)?.groupValues?.get(1) ?: return null
        return if (digits.isEmpty()) 1 else digits.toIntOrNull()
    }
}

/**
 * El orden de "Siguiente" y "Anterior" en el replanteo: por nombre, con los números como números, sin distinguir
 * mayúsculas y con los ceros a la izquierda sin valor (S018/RF-3). Dos nombres que solo difieren en las mayúsculas
 * nunca empatan.
 */
object NaturalOrder : Comparator<String> {
    private val CHUNKS = Regex("""\d+|\D+""")

    override fun compare(a: String, b: String): Int {
        val left = CHUNKS.findAll(a).map { it.value }.toList()
        val right = CHUNKS.findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(left.size, right.size)) {
            val x = left[i]
            val y = right[i]
            val byChunk = when {
                x[0].isDigit() && y[0].isDigit() -> BigInteger(x).compareTo(BigInteger(y))
                else -> x.compareTo(y, ignoreCase = true)
            }
            if (byChunk != 0) return byChunk
        }
        val bySize = left.size.compareTo(right.size)
        return if (bySize != 0) bySize else a.compareTo(b)
    }
}
