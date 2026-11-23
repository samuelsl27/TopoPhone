package es.topophone.app.ui.localization

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.core.model.localization.Localization
import es.topophone.core.model.localization.LocalizationLimits
import es.topophone.core.model.localization.LocalizationOrigin
import es.topophone.core.model.localization.LocalizationPair
import es.topophone.core.model.project.Project
import es.topophone.core.survey.localization.LocalizationAssessment
import es.topophone.core.survey.localization.LocalizationOutcome
import es.topophone.core.survey.localization.LocalizationSolver
import es.topophone.core.survey.localization.assess
import es.topophone.data.localization.LocalizationRepository
import es.topophone.data.project.ProjectRepository
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/**
 * Lo que enseña la pantalla de localización (S039/RF-1): el proyecto, la localización activa, el borrador con sus pares (o los de la activa si aún no
 * se edita), el cálculo del borrador y sus avisos, y el historial.
 */
data class LocalizationUiState(
    val loaded: Boolean = false,
    val project: Project? = null,
    /** La localización activa, o null (RF-1). */
    val active: Localization? = null,
    /** El borrador, o null mientras no se edita nada (RF-16). */
    val draft: Localization? = null,
    /** Los pares que se ven: los del borrador y, sin borrador, los de la activa. */
    val pairs: List<LocalizationPair> = emptyList(),
    val fixScale: Boolean = false,
    val residualLimitM: Double = 0.0,
    val scaleLimit: Double = 0.0,
    /** El cálculo de los pares que se ven, o null si no hay pares (RF-10, RF-11, RF-12). */
    val outcome: LocalizationOutcome? = null,
    val assessment: LocalizationAssessment? = null,
    val history: List<Localization> = emptyList(),
) {
    /** El proyecto está en coordenadas de terreno (RF-22). */
    val terrain: Boolean get() = project?.terrain != null
}

/** La pantalla de localización de un proyecto (S039/RF-1). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LocalizationViewModel @Inject constructor(
    private val localizations: LocalizationRepository,
    private val projects: ProjectRepository,
) : ViewModel() {
    private val projectId = MutableStateFlow<String?>(null)

    fun start(id: String) {
        projectId.value = id
    }

    val state: StateFlow<LocalizationUiState> = projectId.filterNotNull().flatMapLatest { id ->
        combine(
            projects.observe(id),
            localizations.active(id),
            localizations.draft(id),
            localizations.history(id),
        ) { project, active, draft, history ->
            val shown = draft ?: active
            val pairs = shown?.pairs.orEmpty()
            val fixScale = shown?.fixScale ?: false
            val outcome = if (shown != null && shown.origin == LocalizationOrigin.PAIRS && pairs.isNotEmpty()) {
                LocalizationSolver.solve(pairs, fixScale)
            } else {
                null
            }
            val residualLimit = shown?.residualLimitM ?: LocalizationLimits.DEFAULT_RESIDUAL_LIMIT_M
            val scaleLimit = shown?.scaleLimit ?: LocalizationLimits.DEFAULT_SCALE_LIMIT
            LocalizationUiState(
                loaded = project != null,
                project = project,
                active = active,
                draft = draft,
                pairs = pairs,
                fixScale = fixScale,
                residualLimitM = residualLimit,
                scaleLimit = scaleLimit,
                outcome = outcome,
                assessment = (outcome as? LocalizationOutcome.Solved)?.solution?.assess(residualLimit, scaleLimit),
                history = history,
            )
        }.flowOn(Dispatchers.Default)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LocalizationUiState())

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
