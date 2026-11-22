package es.topophone.core.geodesy.catalog

import es.topophone.core.model.project.CrsDefinition
import es.topophone.core.model.project.CustomCrs
import es.topophone.core.model.project.CustomHeight
import es.topophone.core.model.project.GeoBox
import es.topophone.core.model.project.LinearUnit
import kotlin.math.abs

/** Cuántos parámetros lleva el Helmert que escribe el usuario (S035/RF-3). */
enum class HelmertKind {
    /** Tres traslaciones. */
    TRANSLATIONS_3,

    /** Tres traslaciones y la escala. */
    TRANSLATIONS_SCALE_4,

    /** Tres traslaciones y tres rotaciones. */
    ROTATIONS_6,

    /** Tres traslaciones, tres rotaciones y la escala. */
    SEVEN,
}

/** Convención de las rotaciones de un Helmert: vector de posición o marco de coordenadas (S033/RF-2). */
enum class RotationConvention(val code: String) {
    POSITION_VECTOR("PV"),
    COORDINATE_FRAME("CF"),
}

/** Los siete parámetros de un Helmert: traslaciones en m, rotaciones en segundos de arco y escala en ppm. */
data class HelmertValues(
    val tx: Double = 0.0,
    val ty: Double = 0.0,
    val tz: Double = 0.0,
    val rx: Double = 0.0,
    val ry: Double = 0.0,
    val rz: Double = 0.0,
    val scalePpm: Double = 0.0,
)

/** La transformación del datum de un SC personalizado hasta el de las correcciones (S035/RF-3). */
sealed interface CustomTransformation {
    /** Ninguna: el datum del SC es el de las correcciones. */
    data object None : CustomTransformation

    /** Helmert de 3, 4, 6 o 7 parámetros; los que [kind] no usa valen 0. */
    data class Helmert(val kind: HelmertKind, val convention: RotationConvention, val values: HelmertValues) : CustomTransformation

    /** Molodensky-Badekas de 10 parámetros: los siete del Helmert y el punto de rotación (X, Y, Z geocéntricos, en m). */
    data class MolodenskyBadekas(
        val convention: RotationConvention,
        val values: HelmertValues,
        val pivotX: Double,
        val pivotY: Double,
        val pivotZ: Double,
    ) : CustomTransformation

    /** Una rejilla de desplazamiento horizontal, del catálogo de rejillas (S033, S034). */
    data class Grid(val name: String) : CustomTransformation

    /** Una cadena de la biblioteca que el editor no sabe expresar (varios pasos, o un paso inverso): se conserva tal cual al partir de un SC de la biblioteca. */
    data class Kept(val stepsText: String) : CustomTransformation
}

/**
 * Lo que se edita de un SC personalizado (S035/RF-1 a RF-5). Los campos que aún no se han escrito son `null`; [CustomCrsBuilder] dice cuáles
 * faltan o fallan. [method] es `geo` (sin proyección) o el método de la biblioteca (`tm`, `lcc1`, `lcc2`, `omerca`, `omercb`…) con sus [parameters].
 */
data class CustomCrsDraft(
    val name: String = "",
    val datumName: String = "",
    val semiMajorAxisM: Double? = null,
    /** 0 para una esfera. */
    val inverseFlattening: Double? = null,
    val primeMeridianDeg: Double = 0.0,
    val method: String = GEOGRAPHIC,
    val parameters: Map<String, Double?> = emptyMap(),
    val unit: LinearUnit = LinearUnit.METRE,
    val frame: CorrectionFrame = CorrectionFrame.ETRS89,
    val transformation: CustomTransformation = CustomTransformation.None,
    val transformationAccuracyM: Double? = null,
    val height: CustomHeight = CustomHeight.Ellipsoidal,
    val area: GeoBox? = null,
) {
    companion object {
        const val GEOGRAPHIC = "geo"

        /** Un SC vacío (S035/RF-1). */
        fun empty(): CustomCrsDraft = CustomCrsDraft()

        /** Parte de un SC de la biblioteca: sus elipsoide, método, parámetros y transformación; el nombre queda vacío para que lo ponga el usuario. */
        fun of(definition: CrsDefinition, height: CustomHeight = CustomHeight.Ellipsoidal): CustomCrsDraft = CustomCrsDraft(
            name = "",
            datumName = definition.datumName,
            semiMajorAxisM = definition.ellipsoidSemiMajorM,
            inverseFlattening = definition.ellipsoidInverseFlattening,
            primeMeridianDeg = definition.primeMeridianDeg,
            method = if (definition.projected) definition.method else GEOGRAPHIC,
            parameters = if (definition.projected) definition.parameters else emptyMap(),
            unit = LinearUnit.entries.firstOrNull { abs(it.metres - definition.unitMetres) < UNIT_TOLERANCE } ?: LinearUnit.METRE,
            frame = if (definition.frame == "WGS84") CorrectionFrame.WGS84 else CorrectionFrame.ETRS89,
            transformation = transformationOf(definition.transformationSteps),
            transformationAccuracyM = definition.transformationAccuracyM,
            height = height,
            area = definition.area,
        )

        /** Parte de un SC de Mis SC, con su nombre (S035/RF-1). */
        fun of(custom: CustomCrs): CustomCrsDraft = of(custom.definition, custom.height).copy(name = custom.name)

        private const val UNIT_TOLERANCE = 1e-12

        private fun transformationOf(stepsText: String): CustomTransformation {
            val steps = CrsCatalog.parseSteps(stepsText)
            val step = steps.singleOrNull() ?: return CustomTransformation.Kept(stepsText)
            return when (step) {
                StepSpec.Null -> CustomTransformation.None
                is StepSpec.Grid -> if (step.inverse) CustomTransformation.Kept(stepsText) else CustomTransformation.Grid(step.name)
                is StepSpec.Helmert -> helmertOf(step) ?: CustomTransformation.Kept(stepsText)
            }
        }

        private fun helmertOf(step: StepSpec.Helmert): CustomTransformation? {
            if (step.inverse) return null
            val convention = RotationConvention.entries.firstOrNull { it.code == step.convention } ?: return null
            val v = HelmertValues(
                step.translationM.first,
                step.translationM.second,
                step.translationM.third,
                step.rotationArcSec.first,
                step.rotationArcSec.second,
                step.rotationArcSec.third,
                step.scalePpm,
            )
            step.pivotM?.let { return CustomTransformation.MolodenskyBadekas(convention, v, it.first, it.second, it.third) }
            val rotated = v.rx != 0.0 || v.ry != 0.0 || v.rz != 0.0
            val scaled = v.scalePpm != 0.0
            val kind = when {
                rotated && scaled -> HelmertKind.SEVEN
                rotated -> HelmertKind.ROTATIONS_6
                scaled -> HelmertKind.TRANSLATIONS_SCALE_4
                else -> HelmertKind.TRANSLATIONS_3
            }
            return CustomTransformation.Helmert(kind, convention, v)
        }
    }
}
