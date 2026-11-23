package es.topophone.app.ui.localization

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.core.formats.csv.ExportFileName
import es.topophone.core.formats.localization.LocalizationReportCsv
import es.topophone.core.model.format.NumberInput
import es.topophone.core.model.localization.Localization
import es.topophone.core.model.localization.LocalizationLimits
import es.topophone.core.model.localization.LocalizationOrigin
import es.topophone.core.model.localization.LocalizationPair
import es.topophone.core.model.localization.PairResidualValues
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.hasMeasurement
import es.topophone.core.model.project.Project
import es.topophone.core.survey.localization.KnownCoordinates
import es.topophone.core.survey.localization.LocalizationAssessment
import es.topophone.core.survey.localization.LocalizationDraftRules
import es.topophone.core.survey.localization.LocalizationOutcome
import es.topophone.core.survey.localization.LocalizationSolver
import es.topophone.core.survey.localization.PairRejection
import es.topophone.core.survey.localization.PairResult
import es.topophone.core.survey.localization.assess
import es.topophone.data.localization.ApplyResult
import es.topophone.data.localization.LocalizationRepository
import es.topophone.data.point.PointRepository
import es.topophone.data.project.ProjectRepository
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** En qué paso está la confirmación de aplicar: la primera con el recuento, y la segunda si hay residuos o escala fuera de umbral (S039/RF-14, RF-15). */
enum class ApplyStage { CONFIRM, CONFIRM_WARNINGS }

/** La confirmación de aplicar el borrador: cuántos puntos cambian y qué supera los umbrales (S039/RF-14, RF-15). */
data class ApplyConfirmUi(
    val stage: ApplyStage,
    val pointsAffected: Int,
    val highPairNames: List<String> = emptyList(),
    val scaleOutOfLimit: Boolean = false,
) {
    val hasWarnings: Boolean get() = highPairNames.isNotEmpty() || scaleOutOfLimit
}

/** Qué pasó con la última acción de aplicar, quitar o reactivar (S039/RF-14, RF-21, RF-22). */
sealed interface LocalizationNotice {
    data class Applied(val pointsChanged: Int) : LocalizationNotice

    data class Removed(val pointsChanged: Int) : LocalizationNotice

    /** Una localización archivada pasó al borrador y falta aplicarla (RF-21). */
    data object DraftReady : LocalizationNotice

    data object TerrainActive : LocalizationNotice

    data object CannotSolve : LocalizationNotice

    data object StaleCrs : LocalizationNotice

    data object NotFound : LocalizationNotice

    /** El informe se guardó o compartió, o no se pudo (RF-25). */
    data object ReportSaved : LocalizationNotice

    data object ReportFailed : LocalizationNotice
}

/** De dónde salen las coordenadas conocidas de un par (S039/RF-2). */
enum class KnownSource { TYPED, POINT }

/** Por qué no se puede añadir el par que se está formando (S039/RF-3, RF-4). */
enum class PairError { NO_GNSS, NO_KNOWN_POINT, INVALID_COORDINATES, NO_MEASUREMENT, ALREADY_IN_DRAFT, LIMIT_REACHED, NO_POSITION }

/** Un punto GNSS que se puede elegir para un par: medido o replanteado, con posición y aún sin par (S039/RF-2). */
data class GnssCandidate(val id: String, val name: String)

/** Un punto manual o importado del que tomar las coordenadas conocidas (S039/RF-2). */
data class KnownCandidate(val id: String, val name: String, val easting: Double, val northing: Double, val heightM: Double?)

/** El par que se está formando (S039/RF-2). */
data class AddPairUi(
    val gnssId: String? = null,
    val source: KnownSource = KnownSource.TYPED,
    val knownPointId: String? = null,
    val eastingText: String = "",
    val northingText: String = "",
    val heightText: String = "",
    val error: PairError? = null,
)

/**
 * Lo que enseña la pantalla de localización (S039/RF-1): el proyecto, la localización activa, el borrador con sus pares (o los de la activa si aún no
 * se edita), el cálculo de los pares que se ven y sus avisos, el historial y el par que se está formando.
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
    /** Los puntos GNSS que aún no están en ningún par y los puntos de los que tomar coordenadas (RF-2). */
    val gnssCandidates: List<GnssCandidate> = emptyList(),
    val knownCandidates: List<KnownCandidate> = emptyList(),
    /** El par que se está formando, o null con el formulario cerrado. */
    val addPair: AddPairUi? = null,
    /** La confirmación de aplicar abierta, o null (RF-14, RF-15). */
    val confirmApply: ApplyConfirmUi? = null,
    /** Cuántos puntos cambiarían al quitar la localización, con la confirmación de quitar abierta; null si está cerrada (RF-21). */
    val confirmRemove: Int? = null,
    val notice: LocalizationNotice? = null,
) {
    /** El proyecto está en coordenadas de terreno (RF-22). */
    val terrain: Boolean get() = project?.terrain != null

    /** Se puede aplicar: hay un borrador que se calcula y el proyecto no está en terreno (RF-12, RF-14, RF-22). */
    val canApply: Boolean get() = draft != null && outcome is LocalizationOutcome.Solved && !terrain
}

/** La pantalla de localización de un proyecto (S039/RF-1). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LocalizationViewModel @Inject constructor(
    private val localizations: LocalizationRepository,
    private val projects: ProjectRepository,
    private val points: PointRepository,
) : ViewModel() {
    private val projectId = MutableStateFlow<String?>(null)
    private val form = MutableStateFlow<AddPairUi?>(null)
    private val confirmApplyFlow = MutableStateFlow<ApplyConfirmUi?>(null)
    private val confirmRemoveFlow = MutableStateFlow<Int?>(null)
    private val noticeFlow = MutableStateFlow<LocalizationNotice?>(null)

    fun start(id: String) {
        projectId.value = id
    }

    private data class Dialogs(val confirmApply: ApplyConfirmUi?, val confirmRemove: Int?, val notice: LocalizationNotice?)

    private data class Loaded(val project: Project?, val active: Localization?, val draft: Localization?, val history: List<Localization>)

    val state: StateFlow<LocalizationUiState> = projectId.filterNotNull().flatMapLatest { id ->
        combine(
            combine(projects.observe(id), localizations.active(id), localizations.draft(id), localizations.history(id), ::Loaded),
            points.points(id),
            form,
            combine(confirmApplyFlow, confirmRemoveFlow, noticeFlow, ::Dialogs),
        ) { loaded, projectPoints, addPair, dialogs ->
            val shown = loaded.draft ?: loaded.active
            val pairs = shown?.pairs.orEmpty()
            val fixScale = shown?.fixScale ?: false
            val canSolve = shown != null && shown.origin == LocalizationOrigin.PAIRS && pairs.isNotEmpty()
            val outcome = if (canSolve) LocalizationSolver.solve(pairs, fixScale) else null
            val residualLimit = shown?.residualLimitM ?: LocalizationLimits.DEFAULT_RESIDUAL_LIMIT_M
            val scaleLimit = shown?.scaleLimit ?: LocalizationLimits.DEFAULT_SCALE_LIMIT
            val taken = pairs.mapNotNull { it.pointId }.toSet()
            val live = projectPoints.filter { it.deletedAtMs == null }
            LocalizationUiState(
                loaded = loaded.project != null,
                project = loaded.project,
                active = loaded.active,
                draft = loaded.draft,
                pairs = pairs,
                fixScale = fixScale,
                residualLimitM = residualLimit,
                scaleLimit = scaleLimit,
                outcome = outcome,
                assessment = (outcome as? LocalizationOutcome.Solved)?.solution?.assess(residualLimit, scaleLimit),
                history = loaded.history,
                gnssCandidates = live
                    .filter { it.origin.hasMeasurement && it.measurement?.coordinates?.easting != null && it.id !in taken }
                    .map { GnssCandidate(it.id, it.name) },
                knownCandidates = live
                    .filter { it.origin == PointOrigin.MANUAL || it.origin == PointOrigin.IMPORTED }
                    .mapNotNull { p ->
                        val e = p.coordinates.easting
                        val n = p.coordinates.northing
                        if (e == null || n == null) null else KnownCandidate(p.id, p.name, e, n, p.coordinates.height)
                    },
                addPair = addPair,
                confirmApply = dialogs.confirmApply,
                confirmRemove = dialogs.confirmRemove,
                notice = dialogs.notice,
            )
        }.flowOn(Dispatchers.Default)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LocalizationUiState())

    /** Abre el formulario del par (RF-2). */
    fun openAddPair() {
        form.value = AddPairUi()
    }

    fun closeAddPair() {
        form.value = null
    }

    fun selectGnss(id: String) = formUpdate { it.copy(gnssId = id, error = null) }

    fun setSource(source: KnownSource) = formUpdate { it.copy(source = source, error = null) }

    fun selectKnown(id: String) = formUpdate { it.copy(knownPointId = id, error = null) }

    fun setEasting(text: String) = formUpdate { it.copy(eastingText = text, error = null) }

    fun setNorthing(text: String) = formUpdate { it.copy(northingText = text, error = null) }

    fun setHeight(text: String) = formUpdate { it.copy(heightText = text, error = null) }

    private fun formUpdate(change: (AddPairUi) -> AddPairUi) = form.update { it?.let(change) }

    /** Forma el par con las reglas de RF-3 y RF-4 y lo añade al borrador (RF-2); si no se puede, deja el motivo en el formulario. */
    fun confirmAddPair() {
        val id = projectId.value ?: return
        val current = state.value
        val project = current.project ?: return
        val ui = form.value ?: return
        val gnss = ui.gnssId ?: return fail(PairError.NO_GNSS)
        val known = knownCoordinates(ui, project, current.knownCandidates) ?: return fail(
            if (ui.source == KnownSource.POINT) PairError.NO_KNOWN_POINT else PairError.INVALID_COORDINATES,
        )
        viewModelScope.launch {
            val point = points.point(gnss).first() ?: return@launch fail(PairError.NO_MEASUREMENT)
            when (val result = LocalizationDraftRules.formPair(current.pairs, point, known, UUID.randomUUID().toString())) {
                is PairResult.Rejected -> fail(result.reason.toError())
                is PairResult.Added -> {
                    localizations.startDraft(id) // RF-16: editar con una activa parte de una copia suya
                    if (localizations.addPair(id, result.pair)) form.value = null else fail(PairError.LIMIT_REACHED)
                }
            }
        }
    }

    private fun fail(error: PairError) = form.update { it?.copy(error = error) }

    private fun PairRejection.toError() = when (this) {
        PairRejection.NO_MEASUREMENT -> PairError.NO_MEASUREMENT
        PairRejection.ALREADY_IN_DRAFT -> PairError.ALREADY_IN_DRAFT
        PairRejection.LIMIT_REACHED -> PairError.LIMIT_REACHED
        PairRejection.NO_POSITION -> PairError.NO_POSITION
    }

    /** Las coordenadas conocidas escritas (en la unidad del proyecto) o tomadas de un punto, en metros (RF-2). */
    private fun knownCoordinates(ui: AddPairUi, project: Project, candidates: List<KnownCandidate>): KnownCoordinates? = when (ui.source) {
        KnownSource.POINT -> candidates.firstOrNull {
            it.id == ui.knownPointId
        }?.let { KnownCoordinates(it.easting, it.northing, it.heightM) }
        KnownSource.TYPED -> {
            val unit = project.linearUnit
            val e = NumberInput.parse(ui.eastingText)
            val n = NumberInput.parse(ui.northingText)
            val h = if (ui.heightText.isBlank()) null else NumberInput.parse(ui.heightText) ?: return null
            if (e == null || n == null) null else KnownCoordinates(unit.toMetres(e), unit.toMetres(n), h?.let(unit::toMetres))
        }
    }

    /** Activa o excluye la componente horizontal o la vertical de un par sin borrarlo (RF-5). */
    fun setComponent(pairId: String, horizontal: Boolean, enabled: Boolean) {
        val pair = state.value.pairs.firstOrNull { it.id == pairId } ?: return
        val useH = if (horizontal) enabled else pair.useHorizontal
        val useV = if (horizontal) pair.useVertical else enabled
        edit(pairId) { projectId, draftPairId -> localizations.setComponents(projectId, draftPairId, useH, useV) }
    }

    /** Quita un par del borrador (RF-10). */
    fun removePair(pairId: String) {
        edit(pairId) { projectId, draftPairId -> localizations.removePair(projectId, draftPairId) }
    }

    /** «Fijar escala = 1»: solo traslación y giro con 2 o más pares (RF-8). */
    fun setFixScale(fixScale: Boolean) {
        val id = projectId.value ?: return
        viewModelScope.launch { localizations.setFixScale(id, fixScale) }
    }

    /**
     * Los umbrales de aviso: el del residuo en la unidad del proyecto y el de la escala como número (RF-13). Un texto que no sea un número positivo
     * no cambia nada.
     */
    fun setLimits(residualText: String, scaleText: String) {
        val id = projectId.value ?: return
        val project = state.value.project ?: return
        val residual = NumberInput.parse(residualText)?.let(project.linearUnit::toMetres)?.takeIf { it > 0.0 }
        val scale = NumberInput.parse(scaleText)?.takeIf { it > 0.0 }
        if (residual == null && scale == null) return
        val current = state.value
        viewModelScope.launch {
            localizations.setLimits(id, residual ?: current.residualLimitM, scale ?: current.scaleLimit)
        }
    }

    /** «Aplicar al proyecto»: abre la confirmación con cuántos puntos cambian y, si hay residuos o escala fuera de umbral, qué son (RF-14, RF-15). */
    fun requestApply() {
        val id = projectId.value ?: return
        val current = state.value
        if (!current.canApply) return
        viewModelScope.launch {
            val affected = localizations.affectedPoints(id)
            val high = current.assessment?.highPairIds.orEmpty()
            confirmApplyFlow.value = ApplyConfirmUi(
                stage = ApplyStage.CONFIRM,
                pointsAffected = affected,
                highPairNames = current.pairs.filter { it.id in high }.map { it.pointName },
                scaleOutOfLimit = current.assessment?.scaleOutOfLimit == true,
            )
        }
    }

    fun cancelApply() {
        confirmApplyFlow.value = null
    }

    /** Confirma: con avisos de umbral hace falta una segunda confirmación que los nombra (RF-15); después aplica en una transacción (RF-14). */
    fun confirmApply() {
        val id = projectId.value ?: return
        val dialog = confirmApplyFlow.value ?: return
        if (dialog.stage == ApplyStage.CONFIRM && dialog.hasWarnings) {
            confirmApplyFlow.value = dialog.copy(stage = ApplyStage.CONFIRM_WARNINGS)
            return
        }
        confirmApplyFlow.value = null
        viewModelScope.launch { noticeFlow.value = localizations.apply(id).toNotice() }
    }

    /** «Quitar»: abre la confirmación con cuántos puntos vuelven a sus coordenadas sin localizar (RF-21). */
    fun requestRemove() {
        val id = projectId.value ?: return
        if (state.value.active == null) return
        viewModelScope.launch { confirmRemoveFlow.value = localizations.affectedPoints(id) }
    }

    fun cancelRemove() {
        confirmRemoveFlow.value = null
    }

    fun confirmRemove() {
        val id = projectId.value ?: return
        confirmRemoveFlow.value = null
        viewModelScope.launch { noticeFlow.value = LocalizationNotice.Removed(localizations.remove(id)) }
    }

    /** Reactiva una localización del historial: una de pares vuelve al borrador; la de un SC guardado se aplica ya (RF-21, RF-28). */
    fun reactivate(localizationId: String) {
        val id = projectId.value ?: return
        viewModelScope.launch { noticeFlow.value = localizations.reactivate(id, localizationId).toNotice() }
    }

    /** Descarta el borrador y sus pares; la localización activa sigue como estaba (RF-16). */
    fun discardDraft() {
        val id = projectId.value ?: return
        viewModelScope.launch { localizations.discardDraft(id) }
    }

    fun dismissNotice() {
        noticeFlow.value = null
    }

    /** Lo que pasó al guardar o compartir el informe (RF-25). */
    fun reportResult(saved: Boolean) {
        noticeFlow.value = if (saved) LocalizationNotice.ReportSaved else LocalizationNotice.ReportFailed
    }

    /** `<proyecto>_localizacion_<AAAAMMDD-HHMM>.csv` con la hora local del móvil. */
    fun reportFileName(at: Instant, zone: ZoneId): String = ExportFileName.of(state.value.project?.name.orEmpty(), "localizacion", at, zone)

    /**
     * Escribe el informe CSV (parámetros, pares y residuos) en [out], en UTF-8 sin BOM (RF-25); false si no hay nada que informar o no se ha podido
     * escribir. Informa de lo que se ve: el borrador calculado o, sin borrador, la localización activa.
     */
    suspend fun writeReport(out: OutputStream): Boolean {
        val current = state.value
        val project = current.project ?: return false
        val shown = (current.draft ?: current.active) ?: return false
        val solved = (current.outcome as? LocalizationOutcome.Solved)?.solution
        val localization = if (solved == null) {
            shown
        } else {
            shown.copy(
                params = solved.params,
                rmsHorizontal = solved.rmsHorizontal,
                rmsVertical = solved.rmsVertical,
                pairs = current.pairs,
                residuals = solved.residuals.associate { it.pairId to PairResidualValues(it.east, it.north, it.vertical) },
            )
        }
        return withContext(Dispatchers.IO) {
            try {
                BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { LocalizationReportCsv.write(localization, project, it) }
                true
            } catch (_: IOException) {
                false
            }
        }
    }

    private fun ApplyResult.toNotice(): LocalizationNotice = when (this) {
        is ApplyResult.Applied -> LocalizationNotice.Applied(pointsChanged)
        is ApplyResult.DraftReady -> LocalizationNotice.DraftReady
        ApplyResult.TerrainActive -> LocalizationNotice.TerrainActive
        is ApplyResult.CannotSolve -> LocalizationNotice.CannotSolve
        ApplyResult.StaleCrs -> LocalizationNotice.StaleCrs
        ApplyResult.NoDraft, ApplyResult.NotFound -> LocalizationNotice.NotFound
    }

    /** El usuario confirma el uso de un par de baja calidad (RF-6). */
    fun confirmPair(pairId: String) {
        edit(pairId) { projectId, draftPairId -> localizations.confirmPair(projectId, draftPairId) }
    }

    /**
     * Cambia un par del borrador; si aún no hay borrador (se ve la localización activa), lo crea desde la activa y busca el par por su posición,
     * porque la copia lleva identificadores nuevos (RF-16).
     */
    private fun edit(pairId: String, action: suspend (projectId: String, draftPairId: String) -> Unit) {
        val id = projectId.value ?: return
        val current = state.value
        viewModelScope.launch {
            if (current.draft != null) {
                action(id, pairId)
            } else {
                val index = current.pairs.indexOfFirst { it.id == pairId }
                if (index < 0) return@launch
                localizations.startDraft(id)
                val copy = localizations.draft(id).first()?.pairs?.getOrNull(index) ?: return@launch
                action(id, copy.id)
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
