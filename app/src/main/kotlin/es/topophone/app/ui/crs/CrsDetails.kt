package es.topophone.app.ui.crs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CatalogArea
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.CrsEntry
import es.topophone.core.geodesy.catalog.CrsKind
import es.topophone.core.geodesy.catalog.GridEntry
import es.topophone.core.geodesy.catalog.GridKind
import es.topophone.core.geodesy.catalog.TransformationOption
import es.topophone.core.geodesy.catalog.TransformationPicker
import es.topophone.core.geodesy.catalog.UnavailableReason
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.geodesy.catalog.frame
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LibraryCrs
import es.topophone.data.grids.GridState
import es.topophone.data.grids.GridStore
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

const val CRS_USE_TAG = "crs_use"
const val CRS_UNAVAILABLE_TAG = "crs_unavailable"
const val CRS_ELLIPSOIDAL_TAG = "crs_height_ellipsoidal"

fun crsTransformationTag(index: Int) = "crs_transformation_$index"

fun crsGeoidTag(name: String) = "crs_geoid_$name"

fun crsGridDownloadTag(name: String) = "crs_grid_download_$name"

/** Una transformación que ofrece la ficha, con el estado de las rejillas que necesita (S034/RF-9, RF-10, RF-12). */
data class TransformationItem(
    val option: TransformationOption,
    /** ¿Cubre su ámbito el centro del proyecto? (RF-10). */
    val coversCentre: Boolean,
    val grids: List<GridItem>,
)

/** Una rejilla con su estado en el móvil. */
data class GridItem(val entry: GridEntry, val state: GridState)

/** Lo que muestra la ficha de un SC (S034/RF-9 a RF-12, RF-14). */
data class CrsDetailsUiState(
    val crs: CrsEntry? = null,
    val datumName: String = "",
    val ellipsoid: String = "",
    val primeMeridianDeg: Double = 0.0,
    val transformations: List<TransformationItem> = emptyList(),
    val selected: TransformationOption? = null,
    val result: CrsBuildResult? = null,
    /** Los geoides de la biblioteca cuyo ámbito cubre el del SC o el centro del proyecto (RF-14). */
    val geoids: List<GridItem> = emptyList(),
    /** El geoide elegido; `null` es la altura elipsoidal. */
    val geoid: String? = null,
    val corrections: CorrectionsDatum = CorrectionsDatum.ETRS89,
) {
    /** El SC con el que se queda el proyecto si se pulsa «Usar», o `null` si no se puede elegir (RF-8). */
    val chosen: LibraryCrs?
        get() = when (val built = result) {
            is CrsBuildResult.Ready -> LibraryCrs(built.definition, corrections, geoid)
            is CrsBuildResult.NeedsGrids -> LibraryCrs(built.definition, corrections, geoid)
            else -> null
        }

    val unavailableReason: UnavailableReason? get() = (result as? CrsBuildResult.Unavailable)?.reason
}

/**
 * La ficha de un SC de la biblioteca: sus datos, la transformación que se usa hasta el datum de las correcciones con sus alternativas y los
 * geoides aplicables (S034/RF-9 a RF-12, RF-14). No guarda nada: [CrsDetailsUiState.chosen] es lo que el proyecto se lleva.
 */
@HiltViewModel
class CrsDetailsViewModel @Inject constructor(private val gridStore: GridStore) : ViewModel() {
    private val catalog = CrsCatalog.bundled
    private val mutableState = MutableStateFlow(CrsDetailsUiState())
    val state: StateFlow<CrsDetailsUiState> = mutableState.asStateFlow()

    private var centre: GeoPoint? = null

    /** Abre la ficha de [epsg] para un proyecto con las correcciones en [corrections] y centrado en [centre] (si lo tiene). */
    fun load(epsg: Int, corrections: CorrectionsDatum, centre: GeoPoint?, initialGeoid: String? = null) {
        this.centre = centre
        val crs = catalog.crsByEpsg(epsg) ?: return
        val datum = catalog.datums.getValue(crs.datumCode)
        val picked = TransformationPicker.pick(catalog, crs.datumCode, corrections.frame(), centre)
        val items = (picked.applicable.map { it to true } + picked.others.map { it to false }).map { (option, covers) ->
            TransformationItem(option, covers, option.gridNames.mapNotNull(::gridItem))
        }
        val first = picked.best ?: picked.others.firstOrNull()
        val geoids = catalog.grids.values
            .filter { it.kind == GridKind.GEOID && overlaps(it.area, crs.area, centre) }
            .sortedBy { it.name }
            .map { GridItem(it, gridStore.state(it.name)) }
        mutableState.value = CrsDetailsUiState(
            crs = crs,
            datumName = datum.name,
            ellipsoid = "a = ${format(datum.ellipsoid.semiMajorAxisM, 3)} m, 1/f = ${format(datum.ellipsoid.inverseFlattening, 9)}",
            primeMeridianDeg = datum.primeMeridianDeg,
            transformations = items,
            geoids = geoids,
            geoid = initialGeoid?.takeIf { g -> geoids.any { it.entry.name == g } },
            corrections = corrections,
        ).let { it.withSelection(first) }
    }

    fun selectTransformation(option: TransformationOption) = mutableState.update { it.withSelection(option) }

    /** Elige el geoide del proyecto, o `null` para la altura elipsoidal (RF-14). */
    fun selectGeoid(name: String?) = mutableState.update { it.copy(geoid = name) }

    /** Vuelve a mirar qué rejillas están instaladas, por ejemplo tras una descarga (RF-12). */
    fun refresh() {
        val current = mutableState.value
        if (current.crs == null) return
        mutableState.value = current.copy(
            transformations = current.transformations.map { it.copy(grids = it.option.gridNames.mapNotNull(::gridItem)) },
            geoids = current.geoids.map { GridItem(it.entry, gridStore.state(it.entry.name)) },
        ).withSelection(current.selected)
    }

    private fun gridItem(name: String): GridItem? = catalog.grids[name]?.let { GridItem(it, gridStore.state(name)) }

    private fun CrsDetailsUiState.withSelection(option: TransformationOption?): CrsDetailsUiState {
        val crsEntry = crs ?: return this
        val result = catalog.build(
            crsEntry.epsg,
            corrections,
            centre,
            choice = option,
            isInstalled = { gridStore.state(it) is GridState.Installed },
        )
        return copy(selected = option, result = result)
    }

    private fun overlaps(grid: CatalogArea?, crs: CatalogArea?, centre: GeoPoint?): Boolean {
        if (grid == null) return false
        if (centre != null) return grid.contains(centre.latitudeDeg, centre.longitudeDeg)
        if (crs == null) return false
        return grid.contains((crs.southDeg + crs.northDeg) / 2, (crs.westDeg + crs.eastDeg) / 2)
    }

    private fun format(value: Double, decimals: Int): String =
        if (value.isInfinite()) "∞" else String.format(Locale.ROOT, "%.${decimals}f", value).trimEnd('0').trimEnd('.')
}

/** Acciones de la ficha. */
class CrsDetailsActions(
    val onTransformation: (TransformationOption) -> Unit = {},
    val onGeoid: (String?) -> Unit = {},
    /** «Descargar» una rejilla (S034/RF-12, RF-14, RF-19). */
    val onDownload: (GridEntry) -> Unit = {},
    val onUse: (LibraryCrs) -> Unit = {},
    val onBack: () -> Unit = {},
)

@Composable
fun CrsDetailsScreen(themeState: ThemeState, onToggleSun: () -> Unit, state: CrsDetailsUiState, actions: CrsDetailsActions) {
    val crs = state.crs
    SectionScaffold(
        title = crs?.name ?: stringResource(R.string.crs_picker_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = actions.onBack,
    ) { padding ->
        if (crs == null) return@SectionScaffold
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.crs_epsg_code, crs.epsg), style = MaterialTheme.typography.titleMedium)
            Fact(R.string.crs_datum, state.datumName)
            Fact(R.string.crs_ellipsoid, state.ellipsoid)
            if (state.primeMeridianDeg != 0.0) Fact(R.string.crs_prime_meridian, "${state.primeMeridianDeg}°")
            Fact(R.string.crs_projection, methodLabel(crs.method))
            crs.parameters.forEach { (key, value) -> Fact(parameterLabel(key), parameterValue(key, value, crs)) }
            if (crs.kind == CrsKind.PROJECTED) Fact(R.string.crs_units, unitLabel(crs.unitMetres))
            if (crs.areaName.isNotEmpty()) Fact(R.string.crs_area, crs.areaName)
            Text(
                stringResource(R.string.crs_transformation_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            Transformations(state, actions)
            Text(
                stringResource(R.string.crs_height_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            Heights(state, actions)
            val unavailable = state.unavailableReason
            if (unavailable != null) {
                Text(
                    stringResource(unavailableText(unavailable)),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(CRS_UNAVAILABLE_TAG),
                )
            }
            Button(
                onClick = { state.chosen?.let(actions.onUse) },
                enabled = state.chosen != null,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(CRS_USE_TAG),
            ) { Text(stringResource(R.string.crs_use)) }
        }
    }
}

@Composable
private fun Fact(label: Int, value: String) = Fact(stringResource(label), value)

@Composable
private fun Fact(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Transformations(state: CrsDetailsUiState, actions: CrsDetailsActions) {
    Column(modifier = Modifier.selectableGroup()) {
        state.transformations.forEachIndexed { index, item ->
            val option = item.option
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouchTarget)
                    .selectable(selected = option == state.selected, role = Role.RadioButton, onClick = {
                        actions.onTransformation(option)
                    })
                    .testTag(crsTransformationTag(index)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option == state.selected, onClick = null)
                Column(modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp)) {
                    Text(option.name, style = MaterialTheme.typography.bodyLarge)
                    val accuracy = option.accuracyM?.let { stringResource(R.string.crs_accuracy, formatMetres(it)) }
                        ?: stringResource(R.string.crs_accuracy_unknown)
                    Text(accuracy, style = MaterialTheme.typography.bodySmall)
                    option.area?.let { Text(areaText(it), style = MaterialTheme.typography.bodySmall) }
                    if (!item.coversCentre) {
                        Text(
                            stringResource(R.string.crs_outside_area),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            item.grids.forEach { GridLine(it, actions.onDownload) }
        }
    }
}

@Composable
private fun Heights(state: CrsDetailsUiState, actions: CrsDetailsActions) {
    Column(modifier = Modifier.selectableGroup()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .selectable(selected = state.geoid == null, role = Role.RadioButton, onClick = { actions.onGeoid(null) })
                .testTag(CRS_ELLIPSOIDAL_TAG),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = state.geoid == null, onClick = null)
            Text(stringResource(R.string.project_height_ellipsoidal), modifier = Modifier.padding(start = 12.dp))
        }
        state.geoids.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouchTarget)
                    .selectable(selected = state.geoid == item.entry.name, role = Role.RadioButton, onClick = {
                        actions.onGeoid(item.entry.name)
                    })
                    .testTag(crsGeoidTag(item.entry.name)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = state.geoid == item.entry.name, onClick = null)
                Column(modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp)) {
                    Text(item.entry.verticalCrs.ifEmpty { item.entry.name }, style = MaterialTheme.typography.bodyLarge)
                    Text(item.entry.name, style = MaterialTheme.typography.bodySmall)
                }
            }
            GridLine(item, actions.onDownload)
        }
    }
}

/** El estado de una rejilla y, si no está, su tamaño, su licencia y el botón «Descargar» (S034/RF-12, RF-19, RF-20). */
@Composable
internal fun GridLine(item: GridItem, onDownload: (GridEntry) -> Unit) {
    Column(modifier = Modifier.padding(start = 40.dp, bottom = 4.dp)) {
        when (item.state) {
            GridState.Bundled -> Text(stringResource(R.string.crs_grid_bundled), style = MaterialTheme.typography.bodySmall)
            is GridState.Installed -> Text(stringResource(R.string.crs_grid_installed), style = MaterialTheme.typography.bodySmall)
            GridState.Missing -> {
                Text(
                    stringResource(R.string.crs_grid_missing, item.entry.name, formatSize(item.entry.sizeBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(item.entry.license.name, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = { onDownload(item.entry) },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(crsGridDownloadTag(item.entry.name)),
                ) { Text(stringResource(R.string.crs_grid_download)) }
            }
        }
    }
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= MEGABYTE -> String.format(Locale.getDefault(), "%.1f MB", bytes / MEGABYTE.toDouble())
    bytes >= KILOBYTE -> String.format(Locale.getDefault(), "%.0f KB", bytes / KILOBYTE.toDouble())
    else -> "$bytes B"
}

private const val KILOBYTE = 1024L
private const val MEGABYTE = 1024L * 1024L

private fun formatMetres(value: Double): String = String.format(Locale.getDefault(), if (value < 1.0) "%.2f" else "%.1f", value)

private fun areaText(area: CatalogArea): String =
    String.format(Locale.getDefault(), "%.2f°…%.2f° N, %.2f°…%.2f° E", area.southDeg, area.northDeg, area.westDeg, area.eastDeg)

@Composable
private fun methodLabel(method: String): String = stringResource(
    when (method) {
        "tm" -> R.string.crs_method_tm
        "lcc1" -> R.string.crs_method_lcc1
        "lcc2" -> R.string.crs_method_lcc2
        "merca" -> R.string.crs_method_merca
        "mercb" -> R.string.crs_method_mercb
        "omerca" -> R.string.crs_method_omerca
        "omercb" -> R.string.crs_method_omercb
        "stereo" -> R.string.crs_method_stereo
        "polara" -> R.string.crs_method_polara
        "polarb" -> R.string.crs_method_polarb
        "polarc" -> R.string.crs_method_polarc
        "webmerc" -> R.string.crs_method_webmerc
        else -> R.string.crs_method_geo
    },
)

@Composable
private fun parameterLabel(key: String): String = stringResource(
    when (key) {
        "latitudeOfOriginDeg" -> R.string.crs_param_latitude_origin
        "longitudeOfOriginDeg" -> R.string.crs_param_longitude_origin
        "scaleFactor" -> R.string.crs_param_scale_factor
        "falseEasting" -> R.string.crs_param_false_easting
        "falseNorthing" -> R.string.crs_param_false_northing
        "latitudeOfFalseOriginDeg" -> R.string.crs_param_latitude_false_origin
        "longitudeOfFalseOriginDeg" -> R.string.crs_param_longitude_false_origin
        "standardParallel1Deg" -> R.string.crs_param_parallel_1
        "standardParallel2Deg" -> R.string.crs_param_parallel_2
        "eastingAtFalseOrigin" -> R.string.crs_param_easting_false_origin
        "northingAtFalseOrigin" -> R.string.crs_param_northing_false_origin
        "standardParallelDeg", "latitudeOfStandardParallelDeg" -> R.string.crs_param_parallel
        "latitudeOfCentreDeg" -> R.string.crs_param_latitude_centre
        "longitudeOfCentreDeg" -> R.string.crs_param_longitude_centre
        "azimuthDeg" -> R.string.crs_param_azimuth
        "rectifiedGridAngleDeg" -> R.string.crs_param_rectified_angle
        "eastingAtCentre" -> R.string.crs_param_easting_centre
        else -> R.string.crs_param_northing_centre
    },
)

private fun parameterValue(key: String, value: Double, crs: CrsEntry): String {
    val text = String.format(Locale.getDefault(), "%.10f", value).trimEnd('0').trimEnd('.', ',')
    return when {
        key.endsWith("Deg") -> "$text°"
        key == "scaleFactor" -> text
        else -> text + if (crs.unitMetres == 1.0) " m" else " ft"
    }
}

@Composable
private fun unitLabel(unitMetres: Double): String = stringResource(
    when (unitMetres) {
        1.0 -> R.string.unit_metre
        0.3048 -> R.string.unit_foot
        else -> R.string.unit_us_survey_foot
    },
)

private fun unavailableText(reason: UnavailableReason): Int = when (reason) {
    UnavailableReason.UNKNOWN_CRS -> R.string.crs_unavailable_unknown
    UnavailableReason.NO_TRANSFORMATION -> R.string.crs_unavailable_no_transformation
    UnavailableReason.NO_PROJECTION -> R.string.crs_unavailable_no_projection
}
