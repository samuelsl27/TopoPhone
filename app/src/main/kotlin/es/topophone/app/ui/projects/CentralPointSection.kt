package es.topophone.app.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.format.AngleFormat
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.CentralPointRejection
import es.topophone.core.survey.PositionUnavailable
import es.topophone.core.survey.ProjectPositionCalculator

const val CENTRAL_POINT_EMPTY_TAG = "project_central_point_empty"
const val CENTRAL_POINT_TYPE_TAG = "project_central_point_type"
const val CENTRAL_POINT_POSITION_TAG = "project_central_point_position"
const val CENTRAL_POINT_REMOVE_TAG = "project_central_point_remove"
const val CENTRAL_POINT_ERROR_TAG = "project_central_point_error"
const val CENTRAL_POINT_FIRST_TAG = "project_central_point_first"
const val CENTRAL_POINT_SECOND_TAG = "project_central_point_second"
const val CENTRAL_POINT_CONFIRM_TAG = "project_central_point_confirm"
const val CENTRAL_POINT_EASTING_TAG = "project_central_point_easting"
const val CENTRAL_POINT_NORTHING_TAG = "project_central_point_northing"
const val CENTRAL_POINT_LATITUDE_TAG = "project_central_point_latitude"
const val CENTRAL_POINT_LONGITUDE_TAG = "project_central_point_longitude"
const val CENTRAL_POINT_UNAVAILABLE_TAG = "project_central_point_unavailable"

/**
 * El punto central opcional del proyecto en el formulario (S006/RF-32, RF-33, RF-35): «Sin fijar» o sus coordenadas en el SC y
 * las unidades del proyecto, con los botones para escribirlas y para quitarlo, y el motivo si el último intento se rechazó
 * (RF-34).
 */
@Composable
internal fun CentralPointSection(
    crs: ProjectCrs,
    linearUnit: LinearUnit,
    angularUnit: AngularUnit,
    point: CentralPoint?,
    error: CentralPointRejection?,
    onTyped: (String, String) -> Unit,
    onRemove: () -> Unit,
    onPosition: () -> Unit,
) {
    var typing by remember { mutableStateOf(false) }
    Text(stringResource(R.string.central_point_title), style = MaterialTheme.typography.titleMedium)
    if (point == null) {
        Text(stringResource(R.string.central_point_unset), modifier = Modifier.testTag(CENTRAL_POINT_EMPTY_TAG))
    } else {
        CentralPointValues(point, crs, linearUnit, angularUnit)
    }
    error?.let {
        Text(
            stringResource(centralPointRejectionText(it)),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(CENTRAL_POINT_ERROR_TAG),
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { typing = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(CENTRAL_POINT_TYPE_TAG),
        ) { Text(stringResource(R.string.central_point_type)) }
        OutlinedButton(
            onClick = onPosition,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(CENTRAL_POINT_POSITION_TAG),
        ) { Text(stringResource(R.string.central_point_use_position)) }
        if (point != null) {
            OutlinedButton(
                onClick = onRemove,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(CENTRAL_POINT_REMOVE_TAG),
            ) { Text(stringResource(R.string.central_point_remove)) }
        }
    }
    if (typing) {
        CentralPointDialog(crs, linearUnit, angularUnit, onDismiss = { typing = false }) { first, second ->
            typing = false
            onTyped(first, second)
        }
    }
}

/** Este y Norte en el SC del proyecto, o latitud y longitud, en sus unidades; o por qué no se pueden dar (S006/RF-36). */
@Composable
internal fun CentralPointValues(point: CentralPoint, crs: ProjectCrs, linearUnit: LinearUnit, angularUnit: AngularUnit) {
    val position = remember(point, crs) {
        ProjectPositionCalculator().fromGround(GeodeticPosition(point.latitudeDeg, point.longitudeDeg, null), crs, HeightSystem.ELLIPSOIDAL)
    }
    val locale = uiLocale()
    val unit = unitSymbol(linearUnit)
    position.easting?.let {
        CoordinateRow(
            stringResource(R.string.coord_easting),
            "${LengthFormat.inUnit(it, linearUnit, locale)} $unit",
            CENTRAL_POINT_EASTING_TAG,
        )
    }
    position.northing?.let {
        CoordinateRow(
            stringResource(R.string.coord_northing),
            "${LengthFormat.inUnit(it, linearUnit, locale)} $unit",
            CENTRAL_POINT_NORTHING_TAG,
        )
    }
    position.latitudeDeg?.let {
        CoordinateRow(
            stringResource(R.string.coord_latitude_symbol),
            AngleFormat.latitude(it, angularUnit, locale),
            CENTRAL_POINT_LATITUDE_TAG,
        )
    }
    position.longitudeDeg?.let {
        CoordinateRow(
            stringResource(R.string.coord_longitude_symbol),
            AngleFormat.longitude(it, angularUnit, locale),
            CENTRAL_POINT_LONGITUDE_TAG,
        )
    }
    // Tras cambiar el SC del proyecto, el punto puede quedar fuera de su ámbito.
    position.unavailable.firstOrNull { it == PositionUnavailable.OUTSIDE_CRS_AREA || it == PositionUnavailable.OUTSIDE_ED50_GRID }?.let {
        Text(
            stringResource(
                if (it ==
                    PositionUnavailable.OUTSIDE_ED50_GRID
                ) {
                    R.string.project_outside_ed50
                } else {
                    R.string.project_outside_crs
                },
            ),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(CENTRAL_POINT_UNAVAILABLE_TAG),
        )
    }
}

/** Dos campos: Este y Norte con la unidad del proyecto, o latitud y longitud en su unidad angular (S006/RF-33). */
@Composable
private fun CentralPointDialog(
    crs: ProjectCrs,
    linearUnit: LinearUnit,
    angularUnit: AngularUnit,
    onDismiss: () -> Unit,
    onFix: (String, String) -> Unit,
) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val projected = crs.isUtm
    val unit = unitSymbol(linearUnit)
    val (latitudeHint, longitudeHint) = when (angularUnit) {
        AngularUnit.DMS -> R.string.point_hint_dms to R.string.point_hint_dms_lon
        AngularUnit.DECIMAL_DEGREES -> R.string.point_hint_dd to R.string.point_hint_dd_lon
        AngularUnit.GON -> R.string.point_hint_gon to R.string.point_hint_gon_lon
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.central_point_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it },
                    label = { Text(stringResource(if (projected) R.string.coord_easting else R.string.point_latitude)) },
                    supportingText = if (projected) null else ({ Text(stringResource(latitudeHint)) }),
                    suffix = if (projected) ({ Text(unit) }) else null,
                    singleLine = true,
                    keyboardOptions = if (projected) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
                    modifier = Modifier.fillMaxWidth().testTag(CENTRAL_POINT_FIRST_TAG),
                )
                OutlinedTextField(
                    value = second,
                    onValueChange = { second = it },
                    label = { Text(stringResource(if (projected) R.string.coord_northing else R.string.point_longitude)) },
                    supportingText = if (projected) null else ({ Text(stringResource(longitudeHint)) }),
                    suffix = if (projected) ({ Text(unit) }) else null,
                    singleLine = true,
                    keyboardOptions = if (projected) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
                    modifier = Modifier.fillMaxWidth().testTag(CENTRAL_POINT_SECOND_TAG),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onFix(first, second) },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(CENTRAL_POINT_CONFIRM_TAG),
            ) { Text(stringResource(R.string.central_point_dialog_fix)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

/** El motivo por el que no se fijó el punto central (S006/RF-34). */
internal fun centralPointRejectionText(rejection: CentralPointRejection): Int = when (rejection) {
    CentralPointRejection.NO_POSITION -> R.string.central_point_no_position
    CentralPointRejection.OUTSIDE_CRS_AREA -> R.string.central_point_outside_crs
    CentralPointRejection.OUTSIDE_ED50_GRID -> R.string.central_point_outside_ed50
    CentralPointRejection.INVALID_NUMBER -> R.string.central_point_invalid
}
