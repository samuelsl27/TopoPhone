package es.topophone.app.ui.points

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.projects.SurveyNavigation
import es.topophone.app.ui.projects.heightTitle
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.format.AngleAxis
import es.topophone.core.model.format.AngleFormat
import es.topophone.core.model.format.AngleInput
import es.topophone.core.model.format.NumberInput
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointChange
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointNames
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.survey.ManualCoordinates
import es.topophone.core.survey.ManualResult
import es.topophone.core.survey.PositionUnavailable
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.point.PointRepository
import es.topophone.data.point.PointSaveResult
import es.topophone.data.project.ProjectRepository
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val FORM_NAME_TAG = "form_name"
const val FORM_DESCRIPTION_TAG = "form_description"
const val FORM_PROJECT_CRS_TAG = "form_project_crs"
const val FORM_GEOGRAPHIC_TAG = "form_geographic"
const val FORM_FIRST_TAG = "form_first"
const val FORM_SECOND_TAG = "form_second"
const val FORM_HEIGHT_TAG = "form_height"
const val FORM_KIND_PROJECT_TAG = "form_kind_project"
const val FORM_KIND_ELLIPSOIDAL_TAG = "form_kind_ellipsoidal"
const val FORM_SAVE_TAG = "form_save"

/** Por qué no vale el nombre (S010/RF-3, S011/RF-9). */
enum class NameProblem { EMPTY, TAKEN }

/**
 * Lo que muestra el formulario de punto (S011/RF-8, RF-9, RF-16 a RF-19): lo escrito tal cual, qué campos no valen,
 * por qué no se puede guardar y si se puede.
 */
data class PointFormUiState(
    val loaded: Boolean = false,
    val editing: Boolean = false,
    val geographicCrs: Boolean = false,
    val allowEllipsoidalKind: Boolean = false,
    val linearUnit: LinearUnit = LinearUnit.METRE,
    val angularUnit: AngularUnit = AngularUnit.DMS,
    val heightSystem: HeightSystem = HeightSystem.ORTHOMETRIC,
    val name: String = "",
    val description: String = "",
    val form: EntryForm = EntryForm.PROJECT_CRS,
    val first: String = "",
    val second: String = "",
    val height: String = "",
    val heightKind: HeightKind = HeightKind.PROJECT,
    val nameError: NameProblem? = null,
    val firstInvalid: Boolean = false,
    val secondInvalid: Boolean = false,
    val heightInvalid: Boolean = false,
    val rejection: PositionUnavailable? = null,
    val canSave: Boolean = false,
)

/** Acciones del formulario de punto. */
class PointFormActions(
    val onName: (String) -> Unit = {},
    val onDescription: (String) -> Unit = {},
    val onForm: (EntryForm) -> Unit = {},
    val onFirst: (String) -> Unit = {},
    val onSecond: (String) -> Unit = {},
    val onHeight: (String) -> Unit = {},
    val onHeightKind: (HeightKind) -> Unit = {},
    val onSave: () -> Unit = {},
)

/**
 * Punto manual nuevo (S011/RF-16 a RF-20) o edición de uno existente (S011/RF-8, RF-9). Al editar, solo si se tocan
 * las coordenadas se recalculan, y un punto medido queda entonces editado (S011/RF-12); su medida no cambia.
 */
@HiltViewModel
class PointFormViewModel @Inject constructor(
    private val points: PointRepository,
    private val projects: ProjectRepository,
    private val calculator: Lazy<ProjectPositionCalculator>,
) : ViewModel() {
    private data class Form(
        val project: Project? = null,
        val original: Point? = null,
        val name: String = "",
        val description: String = "",
        val form: EntryForm = EntryForm.PROJECT_CRS,
        val first: String = "",
        val second: String = "",
        val height: String = "",
        val heightKind: HeightKind = HeightKind.PROJECT,
        val touched: Boolean = false,
    )

    /** Lo escrito leído: coordenadas válidas o qué campos no valen. */
    private data class Parsed(
        val typed: TypedCoordinates?,
        val firstInvalid: Boolean,
        val secondInvalid: Boolean,
        val heightInvalid: Boolean,
    )

    private val form = MutableStateFlow(Form())
    private val nameTaken = MutableStateFlow(false)
    private val eventChannel = Channel<Unit>(Channel.BUFFERED)
    private var nameCheck: Job? = null

    /** Cada vez que el punto se ha guardado: la pantalla vuelve atrás. */
    val events: Flow<Unit> = eventChannel.receiveAsFlow()

    private val manual: ManualCoordinates get() = ManualCoordinates(calculator.get())

    val state: StateFlow<PointFormUiState> = combine(form, nameTaken) { f, taken -> uiState(f, taken) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PointFormUiState())

    /** [locale] escribe los valores de un punto que se edita como los escribiría el usuario. */
    fun start(projectId: String, pointId: String?, locale: Locale) {
        if (form.value.project != null) return
        viewModelScope.launch {
            val project = projects.project(projectId) ?: return@launch
            val original = pointId?.let { points.point(it).first() }
            form.value = if (original == null) {
                val name = PointNames.nextFree(points.lastName(projectId), points.names(projectId)) // S011/RF-16
                Form(project, name = name, form = if (project.crs.isUtm) EntryForm.PROJECT_CRS else EntryForm.GEOGRAPHIC)
            } else {
                prefilled(project, original, locale)
            }
        }
    }

    fun setName(name: String) {
        form.update { it.copy(name = name) }
        checkName()
    }

    fun setDescription(description: String) = form.update { it.copy(description = description) }

    /** Al cambiar de forma, las coordenadas escritas en la otra no valen: se vacían; la altura se queda. */
    fun setForm(entry: EntryForm) = form.update {
        if (it.form == entry) it else it.copy(form = entry, first = "", second = "", heightKind = HeightKind.PROJECT, touched = true)
    }

    fun setFirst(text: String) = form.update { it.copy(first = text, touched = true) }

    fun setSecond(text: String) = form.update { it.copy(second = text, touched = true) }

    fun setHeight(text: String) = form.update { it.copy(height = text, touched = true) }

    fun setHeightKind(kind: HeightKind) = form.update { it.copy(heightKind = kind, touched = true) }

    fun save() {
        if (!state.value.canSave) return
        val f = form.value
        val project = f.project ?: return
        viewModelScope.launch {
            val name = PointNames.clean(f.name)
            val description = f.description.trim()
            val original = f.original
            val recompute = original == null || f.touched
            val typed = if (recompute) parse(f, project).typed ?: return@launch else null
            val coordinates = typed?.let { (manual.compute(it, project.crs, project.heightSystem) as? ManualResult.Computed)?.coordinates }
            if (recompute && coordinates == null) return@launch
            val result = when {
                original == null -> points.create(
                    newPoint(project, name, description, coordinates ?: return@launch, typed),
                )
                !recompute && name == original.name && description == original.description -> PointSaveResult.Saved // nada que guardar
                else -> points.update(
                    original.copy(
                        name = name,
                        description = description,
                        coordinates = coordinates ?: original.coordinates,
                        typed = if (recompute) typed else original.typed,
                        edited = if (recompute) original.origin == PointOrigin.MEASURED else original.edited, // S011/RF-12
                    ),
                    PointChange.EDIT,
                )
            }
            when (result) {
                PointSaveResult.Saved, PointSaveResult.NotFound -> eventChannel.send(Unit)
                PointSaveResult.DuplicateName -> nameTaken.value = true // S011/RF-9
            }
        }
    }

    private fun newPoint(project: Project, name: String, description: String, coordinates: PointCoordinates, typed: TypedCoordinates?) =
        Point(
            id = UUID.randomUUID().toString(),
            projectId = project.id,
            name = name,
            description = description,
            origin = PointOrigin.MANUAL,
            coordinates = coordinates,
            measurement = null,
            typed = typed,
            edited = false,
            hidden = false,
            copyOf = null,
            createdAtMs = System.currentTimeMillis(),
            deletedAtMs = null,
        )

    private fun checkName() {
        val f = form.value
        val project = f.project ?: return
        val name = PointNames.clean(f.name)
        nameCheck?.cancel()
        nameCheck = viewModelScope.launch {
            nameTaken.value = name.isNotEmpty() && name != f.original?.name && points.nameExists(project.id, name)
        }
    }

    private fun uiState(f: Form, taken: Boolean): PointFormUiState {
        val project = f.project ?: return PointFormUiState()
        val parsed = parse(f, project)
        val rejection = parsed.typed?.let { (manual.compute(it, project.crs, project.heightSystem) as? ManualResult.Rejected)?.reason }
        val nameError = when {
            !PointNames.isValid(f.name) -> NameProblem.EMPTY
            taken -> NameProblem.TAKEN
            else -> null
        }
        val coordinatesOk = (f.original != null && !f.touched) || (parsed.typed != null && rejection == null)
        return PointFormUiState(
            loaded = true,
            editing = f.original != null,
            geographicCrs = !project.crs.isUtm,
            allowEllipsoidalKind = allowsEllipsoidal(f.form, project),
            linearUnit = project.linearUnit,
            angularUnit = project.angularUnit,
            heightSystem = project.heightSystem,
            name = f.name,
            description = f.description,
            form = f.form,
            first = f.first,
            second = f.second,
            height = f.height,
            heightKind = if (allowsEllipsoidal(f.form, project)) f.heightKind else HeightKind.PROJECT,
            nameError = nameError,
            firstInvalid = parsed.firstInvalid,
            secondInvalid = parsed.secondInvalid,
            heightInvalid = parsed.heightInvalid,
            rejection = rejection,
            canSave = nameError == null && coordinatesOk,
        )
    }

    /** S011/RF-17: en geográficas se elige la altura, salvo en ED50 (solo altitud) o si el proyecto ya es elipsoidal. */
    private fun allowsEllipsoidal(entry: EntryForm, project: Project): Boolean =
        entry == EntryForm.GEOGRAPHIC && project.crs.datum != GeodeticDatum.ED50 && project.heightSystem == HeightSystem.ORTHOMETRIC

    private fun parse(f: Form, project: Project): Parsed {
        val projected = f.form == EntryForm.PROJECT_CRS && project.crs.isUtm
        fun length(text: String) = NumberInput.parse(text)?.let(project.linearUnit::toMetres)
        val first = if (projected) length(f.first) else AngleInput.parse(f.first, project.angularUnit, AngleAxis.LATITUDE)
        val second = if (projected) length(f.second) else AngleInput.parse(f.second, project.angularUnit, AngleAxis.LONGITUDE)
        val height = if (f.height.isBlank()) null else length(f.height)
        val heightInvalid = f.height.isNotBlank() && height == null
        val kind = if (allowsEllipsoidal(f.form, project)) f.heightKind else HeightKind.PROJECT
        val entry = if (projected) EntryForm.PROJECT_CRS else EntryForm.GEOGRAPHIC
        val typed = if (first != null && second != null && !heightInvalid) TypedCoordinates(entry, first, second, height, kind) else null
        return Parsed(typed, f.first.isNotBlank() && first == null, f.second.isNotBlank() && second == null, heightInvalid)
    }

    /** Lo escrito del punto o, si no tiene, sus coordenadas del proyecto, como lo escribiría el usuario. */
    private fun prefilled(project: Project, point: Point, locale: Locale): Form {
        fun length(valueM: Double?) = valueM?.let { editableNumber(project.linearUnit.fromMetres(it), locale) }.orEmpty()
        val typed = point.typed
        val base = Form(project, point, point.name, point.description)
        return when {
            typed != null && typed.form == EntryForm.PROJECT_CRS && project.crs.isUtm ->
                base.copy(
                    form = EntryForm.PROJECT_CRS,
                    first = length(typed.first),
                    second = length(typed.second),
                    height = length(typed.height),
                    heightKind = typed.heightKind,
                )
            typed != null && typed.form == EntryForm.GEOGRAPHIC -> base.copy(
                form = EntryForm.GEOGRAPHIC,
                first = AngleFormat.latitude(typed.first, project.angularUnit, locale),
                second = AngleFormat.longitude(typed.second, project.angularUnit, locale),
                height = length(typed.height),
                heightKind = typed.heightKind,
            )
            project.crs.isUtm -> base.copy(
                form = EntryForm.PROJECT_CRS,
                first = length(point.coordinates.easting),
                second = length(point.coordinates.northing),
                height = length(point.coordinates.height),
            )
            else -> base.copy(
                form = EntryForm.GEOGRAPHIC,
                first = AngleFormat.latitude(point.coordinates.ground.latitudeDeg, project.angularUnit, locale),
                second = AngleFormat.longitude(point.coordinates.ground.longitudeDeg, project.angularUnit, locale),
                height = length(point.coordinates.height),
            )
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val EDIT_DECIMALS = 4

        /** Sin separador de miles y hasta 0,1 mm, para editar cómodamente. */
        fun editableNumber(value: Double, locale: Locale): String = NumberFormat.getNumberInstance(locale).apply {
            isGroupingUsed = false
            maximumFractionDigits = EDIT_DECIMALS
        }.format(value)
    }
}

@Composable
fun PointFormRoute(
    projectId: String,
    pointId: String?,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    navigation: SurveyNavigation,
    viewModel: PointFormViewModel = hiltViewModel(),
) {
    val locale = uiLocale()
    LaunchedEffect(projectId, pointId) { viewModel.start(projectId, pointId, locale) }
    LaunchedEffect(viewModel) { viewModel.events.collect { navigation.onBack() } }
    val state by viewModel.state.collectAsStateWithLifecycle()
    PointFormScreen(
        themeState,
        onToggleSun,
        state,
        PointFormActions(
            onName = viewModel::setName,
            onDescription = viewModel::setDescription,
            onForm = viewModel::setForm,
            onFirst = viewModel::setFirst,
            onSecond = viewModel::setSecond,
            onHeight = viewModel::setHeight,
            onHeightKind = viewModel::setHeightKind,
            onSave = viewModel::save,
        ),
        navigation.onBack,
    )
}

/**
 * Formulario de punto (S011/RF-8, RF-16 a RF-19): nombre, descripción, coordenadas en el SC del proyecto o en
 * geográficas de su datum en sus unidades, altura opcional con su tipo y el motivo si no se puede guardar.
 */
@Composable
fun PointFormScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: PointFormUiState,
    actions: PointFormActions,
    onBack: () -> Unit,
) {
    SectionScaffold(
        title = stringResource(if (state.editing) R.string.point_edit_title else R.string.point_new),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        if (!state.loaded) return@SectionScaffold
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val nameError = when (state.nameError) {
                NameProblem.EMPTY -> R.string.collector_name_invalid
                NameProblem.TAKEN -> R.string.collector_name_taken
                null -> null
            }
            Field(
                state.name,
                actions.onName,
                stringResource(R.string.collector_name),
                FORM_NAME_TAG,
                error = nameError?.let {
                    stringResource(it)
                },
            )
            Field(state.description, actions.onDescription, stringResource(R.string.collector_description), FORM_DESCRIPTION_TAG)
            if (!state.geographicCrs) {
                Choice(
                    listOf(
                        Triple(R.string.point_form_project_crs, FORM_PROJECT_CRS_TAG, state.form == EntryForm.PROJECT_CRS) to
                            { actions.onForm(EntryForm.PROJECT_CRS) },
                        Triple(R.string.point_form_geographic, FORM_GEOGRAPHIC_TAG, state.form == EntryForm.GEOGRAPHIC) to
                            { actions.onForm(EntryForm.GEOGRAPHIC) },
                    ),
                )
            }
            CoordinateFields(state, actions)
            state.rejection?.let { reason ->
                Text(stringResource(reason.messageRes()), color = MaterialTheme.colorScheme.error) // S011/RF-19
            }
            Button(
                onClick = actions.onSave,
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(FORM_SAVE_TAG),
            ) { Text(stringResource(R.string.project_save)) }
        }
    }
}

@Composable
private fun CoordinateFields(state: PointFormUiState, actions: PointFormActions) {
    val unit = unitSymbol(state.linearUnit)
    val invalid = stringResource(R.string.point_value_invalid)
    val projected = state.form == EntryForm.PROJECT_CRS && !state.geographicCrs
    // Un ejemplo por eje, en la unidad angular del proyecto (S011/RF-17).
    val (latitudeHint, longitudeHint) = when (state.angularUnit) {
        AngularUnit.DMS -> R.string.point_hint_dms to R.string.point_hint_dms_lon
        AngularUnit.DECIMAL_DEGREES -> R.string.point_hint_dd to R.string.point_hint_dd_lon
        AngularUnit.GON -> R.string.point_hint_gon to R.string.point_hint_gon_lon
    }
    Field(
        state.first,
        actions.onFirst,
        stringResource(if (projected) R.string.coord_easting else R.string.point_latitude),
        FORM_FIRST_TAG,
        error = invalid.takeIf { state.firstInvalid },
        hint = stringResource(latitudeHint).takeUnless { projected },
        suffix = unit.takeIf { projected },
    )
    Field(
        state.second,
        actions.onSecond,
        stringResource(if (projected) R.string.coord_northing else R.string.point_longitude),
        FORM_SECOND_TAG,
        error = invalid.takeIf { state.secondInvalid },
        hint = stringResource(longitudeHint).takeUnless { projected },
        suffix = unit.takeIf { projected },
    )
    val heightName = if (state.heightKind ==
        HeightKind.ELLIPSOIDAL
    ) {
        stringResource(R.string.coord_ellipsoidal_height)
    } else {
        heightTitle(state.heightSystem)
    }
    Field(
        state.height,
        actions.onHeight,
        stringResource(R.string.point_height_optional, heightName),
        FORM_HEIGHT_TAG,
        error = invalid.takeIf { state.heightInvalid },
        suffix = unit,
    ) // S011/RF-18
    if (state.allowEllipsoidalKind) {
        Choice(
            listOf(
                Triple(R.string.point_height_altitude, FORM_KIND_PROJECT_TAG, state.heightKind == HeightKind.PROJECT) to
                    { actions.onHeightKind(HeightKind.PROJECT) },
                Triple(R.string.point_height_ellipsoidal, FORM_KIND_ELLIPSOIDAL_TAG, state.heightKind == HeightKind.ELLIPSOIDAL) to {
                    actions.onHeightKind(HeightKind.ELLIPSOIDAL)
                },
            ),
        ) // S011/RF-17
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    tag: String,
    error: String? = null,
    hint: String? = null,
    suffix: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = (error ?: hint)?.let { text -> { Text(text) } },
        suffix = suffix?.let { text -> { Text(text) } },
        singleLine = true,
        keyboardOptions = if (suffix != null) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
private fun Choice(options: List<Pair<Triple<Int, String, Boolean>, () -> Unit>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (option, onClick) ->
            val (text, tag, selected) = option
            FilterChip(
                selected = selected,
                onClick = onClick,
                label = { Text(stringResource(text)) },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(tag),
            )
        }
    }
}

private fun PositionUnavailable.messageRes(): Int = when (this) {
    PositionUnavailable.OUTSIDE_ED50_GRID -> R.string.project_outside_ed50
    PositionUnavailable.OUTSIDE_GEOID -> R.string.point_outside_geoid
    else -> R.string.project_outside_crs
}
