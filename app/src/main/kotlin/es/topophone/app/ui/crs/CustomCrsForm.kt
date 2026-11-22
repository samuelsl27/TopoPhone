package es.topophone.app.ui.crs

import es.topophone.core.geodesy.catalog.CorrectionFrame
import es.topophone.core.geodesy.catalog.CustomCrsDraft
import es.topophone.core.geodesy.catalog.CustomCrsMethods
import es.topophone.core.geodesy.catalog.CustomError
import es.topophone.core.geodesy.catalog.CustomFields
import es.topophone.core.geodesy.catalog.CustomTransformation
import es.topophone.core.geodesy.catalog.HelmertKind
import es.topophone.core.geodesy.catalog.HelmertValues
import es.topophone.core.geodesy.catalog.RotationConvention
import es.topophone.core.model.project.CustomHeight
import es.topophone.core.model.project.LinearUnit

/** La transformación del datum que elige el usuario en el editor (S035/RF-3). */
enum class TransformationKind { NONE, HELMERT_3, HELMERT_4, HELMERT_6, HELMERT_7, MOLODENSKY_BADEKAS, GRID, KEPT }

/** La altura que elige el usuario en el editor (S035/RF-5). */
enum class HeightKind { ELLIPSOIDAL, GEOID, CONSTANT }

/** Los campos de un Helmert, en el orden del editor. */
val HELMERT_FIELDS = listOf("tx", "ty", "tz", "rx", "ry", "rz", "scalePpm")

/** Los campos del punto de rotación de Molodensky-Badekas. */
val PIVOT_FIELDS = listOf("x", "y", "z")

/** La transformación del datum tal como se escribe: cada número, su texto. */
data class TransformationForm(
    val kind: TransformationKind = TransformationKind.NONE,
    val convention: RotationConvention = RotationConvention.POSITION_VECTOR,
    val values: Map<String, String> = emptyMap(),
    val pivot: Map<String, String> = emptyMap(),
    val grid: String = "",
    /** La cadena de la biblioteca que el editor no sabe expresar (S035/RF-1): se conserva tal cual. */
    val keptSteps: String = "",
    val keptName: String = "",
)

/**
 * Lo que el usuario escribe en el editor de SC personalizados (S035/RF-1 a RF-5): todos los números como texto, tal como están en pantalla, para
 * poder marcar el que no se entiende y no perder lo escrito a medias. [CustomCrsFormMapper] lo pasa al borrador que valida el núcleo.
 */
data class CustomCrsForm(
    val name: String = "",
    val datumName: String = "",
    val semiMajor: String = "",
    val inverseFlattening: String = "",
    val primeMeridian: String = "0.0",
    val method: String = CustomCrsDraft.GEOGRAPHIC,
    val parameters: Map<String, String> = emptyMap(),
    val unit: LinearUnit = LinearUnit.METRE,
    val transformation: TransformationForm = TransformationForm(),
    val accuracy: String = "",
    val heightKind: HeightKind = HeightKind.ELLIPSOIDAL,
    val geoidGrid: String = "",
    val undulation: String = "",
)

/** El borrador que sale del formulario y los campos cuyo texto no es un número (S035/RF-6). */
data class ParsedForm(val draft: CustomCrsDraft, val textErrors: Map<String, CustomError>)

/** Del formulario de texto al borrador del núcleo y de vuelta (S035/RF-1, RF-6). */
object CustomCrsFormMapper {

    /** Un número escrito con punto o con coma decimal; `null` si no se entiende. Vacío es «sin escribir». */
    fun number(text: String): Double? {
        val clean = text.trim()
        if (clean.isEmpty()) return null
        val normalised = if ('.' in clean) clean else clean.replace(',', '.')
        return normalised.toDoubleOrNull()
    }

    fun toDraft(form: CustomCrsForm, frame: CorrectionFrame): ParsedForm {
        val errors = linkedMapOf<String, CustomError>()
        fun parse(text: String, field: String): Double? {
            val value = number(text)
            if (value == null && text.isNotBlank()) errors[field] = CustomError.INVALID
            return value
        }

        val keys = CustomCrsMethods.parameterKeys(form.method).orEmpty()
        val parameters = keys.associateWith { key -> parse(form.parameters[key].orEmpty(), CustomFields.parameter(key)) }
        val values = HELMERT_FIELDS.associateWith { key ->
            parse(form.transformation.values[key].orEmpty(), CustomFields.helmert(key))
                ?: 0.0
        }
        val helmert = HelmertValues(
            values.getValue("tx"),
            values.getValue("ty"),
            values.getValue("tz"),
            values.getValue("rx"),
            values.getValue("ry"),
            values.getValue("rz"),
            values.getValue("scalePpm"),
        )
        val pivot = PIVOT_FIELDS.associateWith { axis -> parse(form.transformation.pivot[axis].orEmpty(), CustomFields.pivot(axis)) ?: 0.0 }
        val t = form.transformation
        val transformation: CustomTransformation = when (t.kind) {
            TransformationKind.NONE -> CustomTransformation.None
            TransformationKind.HELMERT_3 -> CustomTransformation.Helmert(HelmertKind.TRANSLATIONS_3, t.convention, helmert)
            TransformationKind.HELMERT_4 -> CustomTransformation.Helmert(HelmertKind.TRANSLATIONS_SCALE_4, t.convention, helmert)
            TransformationKind.HELMERT_6 -> CustomTransformation.Helmert(HelmertKind.ROTATIONS_6, t.convention, helmert)
            TransformationKind.HELMERT_7 -> CustomTransformation.Helmert(HelmertKind.SEVEN, t.convention, helmert)
            TransformationKind.MOLODENSKY_BADEKAS ->
                CustomTransformation.MolodenskyBadekas(t.convention, helmert, pivot.getValue("x"), pivot.getValue("y"), pivot.getValue("z"))
            TransformationKind.GRID -> CustomTransformation.Grid(t.grid)
            TransformationKind.KEPT -> CustomTransformation.Kept(t.keptSteps, t.keptName.ifBlank { "Datum shift" })
        }
        val height = when (form.heightKind) {
            HeightKind.ELLIPSOIDAL -> CustomHeight.Ellipsoidal
            HeightKind.GEOID -> CustomHeight.Geoid(form.geoidGrid)
            HeightKind.CONSTANT -> CustomHeight.ConstantGeoid(parse(form.undulation, CustomFields.HEIGHT) ?: Double.NaN)
        }
        if (form.heightKind == HeightKind.CONSTANT && form.undulation.isBlank()) errors[CustomFields.HEIGHT] = CustomError.REQUIRED
        val draft = CustomCrsDraft(
            name = form.name,
            datumName = form.datumName,
            semiMajorAxisM = parse(form.semiMajor, CustomFields.SEMI_MAJOR_AXIS),
            inverseFlattening = parse(form.inverseFlattening, CustomFields.INVERSE_FLATTENING),
            primeMeridianDeg = parse(form.primeMeridian, CustomFields.PRIME_MERIDIAN) ?: 0.0,
            method = form.method,
            parameters = parameters,
            unit = form.unit,
            frame = frame,
            transformation = transformation,
            transformationAccuracyM = parse(form.accuracy, CustomFields.ACCURACY),
            height = height,
        )
        return ParsedForm(draft, errors)
    }

    /** El formulario de un borrador: los números salen de `Double.toString`, sin redondear y con punto decimal. */
    fun of(draft: CustomCrsDraft): CustomCrsForm {
        fun text(value: Double?) = value?.toString().orEmpty()
        val t = draft.transformation
        val helmert = when (t) {
            is CustomTransformation.Helmert -> t.values
            is CustomTransformation.MolodenskyBadekas -> t.values
            else -> HelmertValues()
        }
        val transformation = TransformationForm(
            kind = when (t) {
                CustomTransformation.None -> TransformationKind.NONE
                is CustomTransformation.Helmert -> when (t.kind) {
                    HelmertKind.TRANSLATIONS_3 -> TransformationKind.HELMERT_3
                    HelmertKind.TRANSLATIONS_SCALE_4 -> TransformationKind.HELMERT_4
                    HelmertKind.ROTATIONS_6 -> TransformationKind.HELMERT_6
                    HelmertKind.SEVEN -> TransformationKind.HELMERT_7
                }
                is CustomTransformation.MolodenskyBadekas -> TransformationKind.MOLODENSKY_BADEKAS
                is CustomTransformation.Grid -> TransformationKind.GRID
                is CustomTransformation.Kept -> TransformationKind.KEPT
            },
            convention = when (t) {
                is CustomTransformation.Helmert -> t.convention
                is CustomTransformation.MolodenskyBadekas -> t.convention
                else -> RotationConvention.POSITION_VECTOR
            },
            values = mapOf(
                "tx" to helmert.tx.toString(),
                "ty" to helmert.ty.toString(),
                "tz" to helmert.tz.toString(),
                "rx" to helmert.rx.toString(),
                "ry" to helmert.ry.toString(),
                "rz" to helmert.rz.toString(),
                "scalePpm" to helmert.scalePpm.toString(),
            ),
            pivot = (t as? CustomTransformation.MolodenskyBadekas)
                ?.let { mapOf("x" to it.pivotX.toString(), "y" to it.pivotY.toString(), "z" to it.pivotZ.toString()) }
                .orEmpty(),
            grid = (t as? CustomTransformation.Grid)?.name.orEmpty(),
            keptSteps = (t as? CustomTransformation.Kept)?.stepsText.orEmpty(),
            keptName = (t as? CustomTransformation.Kept)?.name.orEmpty(),
        )
        val height = draft.height
        return CustomCrsForm(
            name = draft.name,
            datumName = draft.datumName,
            semiMajor = text(draft.semiMajorAxisM),
            inverseFlattening = text(draft.inverseFlattening),
            primeMeridian = draft.primeMeridianDeg.toString(),
            method = draft.method,
            parameters = draft.parameters.mapValues { text(it.value) },
            unit = draft.unit,
            transformation = transformation,
            accuracy = text(draft.transformationAccuracyM),
            heightKind = when (height) {
                CustomHeight.Ellipsoidal -> HeightKind.ELLIPSOIDAL
                is CustomHeight.Geoid -> HeightKind.GEOID
                is CustomHeight.ConstantGeoid -> HeightKind.CONSTANT
            },
            geoidGrid = (height as? CustomHeight.Geoid)?.grid.orEmpty(),
            undulation = (height as? CustomHeight.ConstantGeoid)?.undulationM?.toString().orEmpty(),
        )
    }
}
