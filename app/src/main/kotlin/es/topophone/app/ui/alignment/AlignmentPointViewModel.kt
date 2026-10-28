package es.topophone.app.ui.alignment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.di.IoDispatcher
import es.topophone.core.model.alignment.Alignment
import es.topophone.core.model.alignment.ChainageInput
import es.topophone.core.model.format.NumberInput
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.PointNames
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.survey.AlignmentGeometry
import es.topophone.core.survey.AxisPoint
import es.topophone.core.survey.AxisPointBuilder
import es.topophone.core.survey.AxisPointResult
import es.topophone.core.survey.VerticalProfileEval
import es.topophone.data.alignment.AlignmentRepository
import es.topophone.data.point.PointRepository
import es.topophone.data.point.PointSaveResult
import es.topophone.data.project.ProjectRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Lo que sale de un PK y un desplazamiento sobre el eje (S049/RF-20): el punto, su cota y la pendiente de la rasante en ese PK. */
data class AxisReading(val point: AxisPoint, val elevationM: Double?, val slopePercent: Double?)

/** Lo que muestra la pantalla de calcular un punto sobre un eje (S049/RF-20 a RF-22). */
data class AlignmentPointUiState(
    val loaded: Boolean = false,
    val alignmentName: String = "",
    val unit: LinearUnit = LinearUnit.METRE,
    val angularUnit: AngularUnit = AngularUnit.DMS,
    /** El PK inicial y final del eje, en metros (RF-21). */
    val startChainageM: Double = 0.0,
    val endChainageM: Double = 0.0,
    val hasProfile: Boolean = false,
    val chainageText: String = "",
    val offsetText: String = "0",
    val name: String = "",
    val chainageInvalid: Boolean = false,
    val offsetInvalid: Boolean = false,
    /** El PK escrito cae fuera del eje: no se calcula nada (RF-21). */
    val outside: Boolean = false,
    val reading: AxisReading? = null,
    val saving: Boolean = false,
    /** El nombre del punto guardado; null si no se ha guardado (RF-22). */
    val savedName: String? = null,
    val duplicateName: Boolean = false,
    /** El punto cae fuera del ámbito del sistema del proyecto y no se puede guardar. */
    val outsideCrsArea: Boolean = false,
) {
    val canSave: Boolean get() = loaded && reading != null && name.isNotBlank() && !saving && savedName == null
}

/**
 * El cálculo de un punto sobre un eje (S049/RF-20 a RF-22): con un PK y un desplazamiento (negativo a la izquierda, positivo a la
 * derecha) da el Este y el Norte, el azimut y el radio del eje en ese PK y, si el eje tiene rasante, la cota y la pendiente; un PK fuera
 * del eje no da nada y dice el PK inicial y el final. «Guardar punto» lo guarda como punto del proyecto «Calculado sobre eje».
 */
@HiltViewModel
class AlignmentPointViewModel @Inject constructor(
    private val alignments: AlignmentRepository,
    private val projects: ProjectRepository,
    private val points: PointRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val log: AppLog = AppLog.NONE,
) : ViewModel() {
    private val mutable = MutableStateFlow(AlignmentPointUiState())
    val state: StateFlow<AlignmentPointUiState> = mutable.asStateFlow()

    private var started = false
    private var project: Project? = null
    private var alignment: Alignment? = null
    private var geometry: AlignmentGeometry? = null
    private var profile: VerticalProfileEval? = null

    internal var clock: () -> Long = System::currentTimeMillis
    internal var newId: () -> String = { UUID.randomUUID().toString() }

    /** Prepara el cálculo sobre el eje [alignmentId] del proyecto [projectId]; solo la primera vez. */
    fun start(projectId: String, alignmentId: String) {
        if (started) return
        started = true
        viewModelScope.launch(io) {
            val loadedProject = projects.project(projectId) ?: return@launch
            val loaded = alignments.alignment(alignmentId) ?: return@launch
            project = loadedProject
            alignment = loaded
            geometry = AlignmentGeometry(loaded)
            profile = loaded.profile?.let { VerticalProfileEval(it) }
            val name = PointNames.nextFree(points.lastName(projectId), points.names(projectId))
            mutable.update {
                it.copy(
                    loaded = true,
                    alignmentName = loaded.name,
                    unit = loadedProject.linearUnit,
                    angularUnit = loadedProject.angularUnit,
                    startChainageM = loaded.startChainage,
                    endChainageM = loaded.endChainage,
                    hasProfile = loaded.profile != null,
                    name = name,
                )
            }
        }
    }

    fun setChainage(text: String) = edit { it.copy(chainageText = text, savedName = null) }

    fun setOffset(text: String) = edit { it.copy(offsetText = text, savedName = null) }

    fun setName(name: String) = mutable.update { it.copy(name = name, duplicateName = false, savedName = null) }

    private fun edit(change: (AlignmentPointUiState) -> AlignmentPointUiState) = mutable.update { calculate(change(it)) }

    /** Calcula el punto con lo escrito (RF-20); un PK fuera del eje no calcula nada (RF-21). */
    private fun calculate(current: AlignmentPointUiState): AlignmentPointUiState {
        val unit = current.unit
        val chainage = ChainageInput.parse(current.chainageText, unit)
        val offset = (if (current.offsetText.isBlank()) 0.0 else NumberInput.parse(current.offsetText))?.let(unit::toMetres)
        val cleared = current.copy(
            chainageInvalid = current.chainageText.isNotBlank() && chainage == null,
            offsetInvalid = offset == null,
            outside = false,
            reading = null,
            duplicateName = false,
            outsideCrsArea = false,
        )
        val geometry = geometry ?: return cleared
        if (chainage == null || offset == null) return cleared
        val axisPoint = geometry.pointAt(chainage, offset) ?: return cleared.copy(outside = true)
        val value = profile?.at(chainage)
        return cleared.copy(reading = AxisReading(axisPoint, value?.elevation, value?.slopePercent))
    }

    /** Guarda el punto calculado como punto del proyecto «Calculado sobre eje», con su PK y su desplazamiento (RF-22). */
    fun save() {
        val current = mutable.value
        val reading = current.reading ?: return
        val project = project ?: return
        val alignment = alignment ?: return
        if (!current.canSave) return
        mutable.update { it.copy(saving = true) }
        viewModelScope.launch(io) {
            val name = PointNames.clean(current.name)
            when (
                val built = AxisPointBuilder().build(newId(), project, name, alignment.name, reading.point, reading.elevationM, clock())
            ) {
                is AxisPointResult.Rejected -> mutable.update { it.copy(saving = false, outsideCrsArea = true) }
                is AxisPointResult.Built -> when (points.create(built.point)) {
                    PointSaveResult.Saved -> {
                        // S049/RF-16: el log lleva el hecho, nunca el nombre ni las coordenadas.
                        log.info(LogCategory.POINTS, "Punto calculado sobre un eje guardado")
                        mutable.update { it.copy(saving = false, savedName = name) }
                    }
                    PointSaveResult.DuplicateName -> mutable.update { it.copy(saving = false, duplicateName = true) }
                    PointSaveResult.NotFound -> mutable.update { it.copy(saving = false, outsideCrsArea = true) }
                }
            }
        }
    }

    /** Tras guardar, deja el cálculo listo para otro punto: borra el resultado guardado y propone el nombre siguiente. */
    fun another() {
        val project = project ?: return
        viewModelScope.launch(io) {
            val next = PointNames.nextFree(points.lastName(project.id), points.names(project.id))
            mutable.update { it.copy(savedName = null, name = next) }
        }
    }
}
