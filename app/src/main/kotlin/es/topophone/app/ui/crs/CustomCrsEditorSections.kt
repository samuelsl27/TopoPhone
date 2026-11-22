package es.topophone.app.ui.crs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.geodesy.catalog.CustomCrsDraft
import es.topophone.core.geodesy.catalog.CustomCrsMethods
import es.topophone.core.geodesy.catalog.CustomFields
import es.topophone.core.geodesy.catalog.RotationConvention

const val CUSTOM_METHOD_TAG = "custom_method"
const val CUSTOM_TRANSFORMATION_TAG = "custom_transformation"
const val CUSTOM_GRID_TAG = "custom_grid"
const val CUSTOM_ACCURACY_TAG = "custom_accuracy"
const val CUSTOM_HEIGHT_TAG = "custom_height"
const val CUSTOM_HEIGHT_GEOID_TAG = "custom_height_geoid"
const val CUSTOM_UNDULATION_TAG = "custom_undulation"

fun customMethodTag(method: String) = "custom_method_$method"

fun customParamTag(key: String) = "custom_param_$key"

fun customTransformationTag(kind: TransformationKind) = "custom_transformation_${kind.name}"

fun customHelmertTag(key: String) = "custom_helmert_$key"

fun customPivotTag(axis: String) = "custom_pivot_$axis"

fun customConventionTag(convention: RotationConvention) = "custom_convention_${convention.code}"

fun customGridTag(name: String) = "custom_grid_$name"

fun customHeightTag(kind: HeightKind) = "custom_height_${kind.name}"

fun customGeoidTag(name: String) = "custom_geoid_$name"

private fun methodLabel(method: String): Int = when (method) {
    "tm" -> R.string.custom_method_tm
    "lcc1" -> R.string.custom_method_lcc1
    "lcc2" -> R.string.custom_method_lcc2
    "omerca" -> R.string.custom_method_omerca
    "omercb" -> R.string.custom_method_omercb
    "stereo" -> R.string.custom_method_stereo
    "polara" -> R.string.custom_method_polara
    "polarb" -> R.string.custom_method_polarb
    "polarc" -> R.string.custom_method_polarc
    "merca" -> R.string.custom_method_merca
    "mercb" -> R.string.custom_method_mercb
    else -> R.string.custom_method_geo
}

private fun parameterLabel(key: String): Int = when (key) {
    "latitudeOfOriginDeg" -> R.string.custom_param_latitudeOfOriginDeg
    "longitudeOfOriginDeg" -> R.string.custom_param_longitudeOfOriginDeg
    "scaleFactor" -> R.string.custom_param_scaleFactor
    "falseEasting" -> R.string.custom_param_falseEasting
    "falseNorthing" -> R.string.custom_param_falseNorthing
    "latitudeOfFalseOriginDeg" -> R.string.custom_param_latitudeOfFalseOriginDeg
    "longitudeOfFalseOriginDeg" -> R.string.custom_param_longitudeOfFalseOriginDeg
    "standardParallel1Deg" -> R.string.custom_param_standardParallel1Deg
    "standardParallel2Deg" -> R.string.custom_param_standardParallel2Deg
    "eastingAtFalseOrigin" -> R.string.custom_param_eastingAtFalseOrigin
    "northingAtFalseOrigin" -> R.string.custom_param_northingAtFalseOrigin
    "latitudeOfCentreDeg" -> R.string.custom_param_latitudeOfCentreDeg
    "longitudeOfCentreDeg" -> R.string.custom_param_longitudeOfCentreDeg
    "azimuthDeg" -> R.string.custom_param_azimuthDeg
    "rectifiedGridAngleDeg" -> R.string.custom_param_rectifiedGridAngleDeg
    "eastingAtCentre" -> R.string.custom_param_eastingAtCentre
    "northingAtCentre" -> R.string.custom_param_northingAtCentre
    "latitudeOfStandardParallelDeg" -> R.string.custom_param_latitudeOfStandardParallelDeg
    else -> R.string.custom_param_standardParallelDeg
}

private fun transformationLabel(kind: TransformationKind): Int = when (kind) {
    TransformationKind.NONE -> R.string.custom_transformation_none
    TransformationKind.HELMERT_3 -> R.string.custom_transformation_helmert3
    TransformationKind.HELMERT_4 -> R.string.custom_transformation_helmert4
    TransformationKind.HELMERT_6 -> R.string.custom_transformation_helmert6
    TransformationKind.HELMERT_7 -> R.string.custom_transformation_helmert7
    TransformationKind.MOLODENSKY_BADEKAS -> R.string.custom_transformation_mb
    TransformationKind.GRID -> R.string.custom_transformation_grid
    TransformationKind.KEPT -> R.string.custom_transformation_kept
}

private fun helmertLabel(key: String): Int = when (key) {
    "tx" -> R.string.custom_helmert_tx
    "ty" -> R.string.custom_helmert_ty
    "tz" -> R.string.custom_helmert_tz
    "rx" -> R.string.custom_helmert_rx
    "ry" -> R.string.custom_helmert_ry
    "rz" -> R.string.custom_helmert_rz
    else -> R.string.custom_helmert_scalePpm
}

private fun pivotLabel(axis: String): Int = when (axis) {
    "x" -> R.string.custom_pivot_x
    "y" -> R.string.custom_pivot_y
    else -> R.string.custom_pivot_z
}

/** Cuántos de los siete valores de un Helmert se piden según el tipo (S035/RF-3). */
private fun helmertFieldsOf(kind: TransformationKind): List<String> = when (kind) {
    TransformationKind.HELMERT_3 -> listOf("tx", "ty", "tz")
    TransformationKind.HELMERT_4 -> listOf("tx", "ty", "tz", "scalePpm")
    TransformationKind.HELMERT_6 -> listOf("tx", "ty", "tz", "rx", "ry", "rz")
    TransformationKind.HELMERT_7, TransformationKind.MOLODENSKY_BADEKAS -> HELMERT_FIELDS
    else -> emptyList()
}

/** Un botón que abre una lista de [options] (etiqueta, etiqueta de prueba y acción) y enseña la elegida (S035/RF-3, RF-4, RF-5). */
@Composable
private fun Choice(current: String, tag: String, options: List<Triple<String, String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(tag)) { Text(current) }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 360.dp)) {
        options.forEach { (label, optionTag, action) ->
            DropdownMenuItem(
                text = { Text(label) },
                onClick = {
                    open = false
                    action()
                },
                modifier = Modifier.testTag(optionTag),
            )
        }
    }
}

/** La proyección: método y sus parámetros, cada uno con su error (S035/RF-4, RF-6). */
@Composable
fun ProjectionSection(state: CustomCrsEditorUiState, actions: CustomCrsEditorActions) {
    val form = state.form
    val errors = state.shownErrors
    // Los métodos que se ofrecen y, si se partió de un SC de la biblioteca que usa otro, ese también (S035/RF-4).
    val methods = (listOf(CustomCrsDraft.GEOGRAPHIC) + CustomCrsMethods.offered + form.method).distinct()
    Text(stringResource(R.string.custom_projection_title), style = MaterialTheme.typography.titleSmall)
    val options = methods.map { method ->
        Triple(stringResource(methodLabel(method)), customMethodTag(method)) {
            actions.onChange { it.copy(method = method) }
        }
    }
    Choice(stringResource(methodLabel(form.method)), CUSTOM_METHOD_TAG, options)
    (CustomCrsMethods.parameterKeys(form.method) ?: emptyList()).forEach { key ->
        CustomField(
            stringResource(parameterLabel(key)),
            form.parameters[key].orEmpty(),
            { value -> actions.onChange { it.copy(parameters = it.parameters + (key to value)) } },
            customParamTag(key),
            error = errors[CustomFields.parameter(key)],
        )
    }
}

/** La transformación del datum hasta el de las correcciones (S035/RF-3, RF-6). */
@Composable
fun TransformationSection(state: CustomCrsEditorUiState, actions: CustomCrsEditorActions) {
    val t = state.form.transformation
    val errors = state.shownErrors
    fun change(next: TransformationForm) = actions.onChange { it.copy(transformation = next) }
    val kinds = TransformationKind.entries.filter { it != TransformationKind.KEPT || t.kind == TransformationKind.KEPT }
    Text(stringResource(R.string.custom_transformation_title), style = MaterialTheme.typography.titleSmall)
    Choice(
        stringResource(transformationLabel(t.kind)),
        CUSTOM_TRANSFORMATION_TAG,
        kinds.map { kind ->
            Triple(stringResource(transformationLabel(kind)), customTransformationTag(kind)) { change(t.copy(kind = kind)) }
        },
    )
    if (t.kind in helmertKinds) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RotationConvention.entries.forEach { convention ->
                FilterChip(
                    selected = t.convention == convention,
                    onClick = { change(t.copy(convention = convention)) },
                    label = {
                        Text(
                            stringResource(
                                if (convention ==
                                    RotationConvention.POSITION_VECTOR
                                ) {
                                    R.string.custom_convention_pv
                                } else {
                                    R.string.custom_convention_cf
                                },
                            ),
                        )
                    },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(customConventionTag(convention)),
                )
            }
        }
        helmertFieldsOf(t.kind).forEach { key ->
            CustomField(
                stringResource(helmertLabel(key)),
                t.values[key].orEmpty(),
                { value -> change(t.copy(values = t.values + (key to value))) },
                customHelmertTag(key),
                error = errors[CustomFields.helmert(key)],
            )
        }
    }
    if (t.kind == TransformationKind.MOLODENSKY_BADEKAS) {
        PIVOT_FIELDS.forEach { axis ->
            CustomField(
                stringResource(pivotLabel(axis)),
                t.pivot[axis].orEmpty(),
                { value -> change(t.copy(pivot = t.pivot + (axis to value))) },
                customPivotTag(axis),
                error = errors[CustomFields.pivot(axis)],
            )
        }
    }
    if (t.kind == TransformationKind.GRID) {
        Choice(
            t.grid.ifEmpty { stringResource(R.string.custom_grid_choose) },
            CUSTOM_GRID_TAG,
            state.datumGrids.map { grid -> Triple(grid.name, customGridTag(grid.name)) { change(t.copy(grid = grid.name)) } },
        )
        errors[CustomFields.GRID]?.let { Text(customErrorText(it), color = MaterialTheme.colorScheme.error) }
    }
    if (t.kind != TransformationKind.NONE) {
        CustomField(
            stringResource(R.string.custom_field_accuracy),
            state.form.accuracy,
            { value -> actions.onChange { it.copy(accuracy = value) } },
            CUSTOM_ACCURACY_TAG,
            error = errors[CustomFields.ACCURACY],
        )
    }
}

private val helmertKinds = setOf(
    TransformationKind.HELMERT_3,
    TransformationKind.HELMERT_4,
    TransformationKind.HELMERT_6,
    TransformationKind.HELMERT_7,
    TransformationKind.MOLODENSKY_BADEKAS,
)

/** La altura: elipsoidal, un geoide instalado o una ondulación constante (S035/RF-5, RF-6). */
@Composable
fun HeightSection(state: CustomCrsEditorUiState, actions: CustomCrsEditorActions) {
    val form = state.form
    val errors = state.shownErrors
    Text(stringResource(R.string.custom_height_title), style = MaterialTheme.typography.titleSmall)
    @Composable fun label(kind: HeightKind) = stringResource(
        when (kind) {
            HeightKind.ELLIPSOIDAL -> R.string.custom_height_ellipsoidal
            HeightKind.GEOID -> R.string.custom_height_geoid
            HeightKind.CONSTANT -> R.string.custom_height_constant
        },
    )
    Choice(
        label(form.heightKind),
        CUSTOM_HEIGHT_TAG,
        HeightKind.entries.map { kind -> Triple(label(kind), customHeightTag(kind)) { actions.onChange { it.copy(heightKind = kind) } } },
    )
    if (form.heightKind == HeightKind.GEOID) {
        Choice(
            form.geoidGrid.ifEmpty { stringResource(R.string.custom_grid_choose) },
            CUSTOM_HEIGHT_GEOID_TAG,
            state.geoidGrids.map { grid ->
                Triple(grid.name, customGeoidTag(grid.name)) { actions.onChange { it.copy(geoidGrid = grid.name) } }
            },
        )
        errors[CustomFields.HEIGHT]?.let { Text(customErrorText(it), color = MaterialTheme.colorScheme.error) }
    }
    if (form.heightKind == HeightKind.CONSTANT) {
        CustomField(
            stringResource(R.string.custom_field_undulation),
            form.undulation,
            { value -> actions.onChange { it.copy(undulation = value) } },
            CUSTOM_UNDULATION_TAG,
            error = errors[CustomFields.HEIGHT],
        )
    }
}
