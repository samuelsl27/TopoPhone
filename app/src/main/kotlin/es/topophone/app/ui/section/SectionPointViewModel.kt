package es.topophone.app.ui.section

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.di.IoDispatcher
import es.topophone.core.model.alignment.Alignment
import es.topophone.core.model.alignment.ChainageInput
import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.SectionCode
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.alignment.section.Superelevation
import es.topophone.core.model.format.NumberInput
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.PointNames
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.survey.AlignmentGeometry
import es.topophone.core.survey.AxisPoint
import es.topophone.core.survey.AxisPointBuilder
import es.topophone.core.survey.AxisPointResult
import es.topophone.core.survey.SectionEval
import es.topophone.core.survey.SectionPointResult
import es.topophone.core.survey.SectionResolution
import es.topophone.core.survey.SectionResolver
import es.topophone.core.survey.VerticalProfileEval
import es.topophone.data.alignment.AlignmentRepository
import es.topophone.data.point.PointRepository
import es.topophone.data.point.PointSaveResult
import es.topophone.data.project.ProjectRepository
import es.topophone.data.section.SectionRepository
import java.util.UUID
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Cómo se pide el punto de la sección: por su código o por un desplazamiento (S050/RF-9, RF-10). */
enum class SectionPointMode { BY_CODE, BY_OFFSET }

/** Por qué no hay punto que calcular en este PK (S050/RF-10, RF-11, RF-12). */
sealed interface SectionPointStatus {
    /** El PK no tiene sección de diseño: ni explícita ni tipo. */
    data object NoSection : SectionPointStatus

    /** El PK queda entre dos secciones que no se pueden interpolar: los códigos que faltan. */
    data class Missing(val codes: List<String>, val heightKindMismatch: Boolean) : SectionPointStatus

    /** El código no existe en la sección de este PK. */
    data object NoCode : SectionPointStatus

    /** El desplazamiento queda más allá de los puntos exteriores: «Fuera de la sección» (RF-10). */
    data object OutsideSection : SectionPointStatus
}

/** El punto calculado de la sección (S050/RF-9): su planta, su cota si la hay y la pendiente transversal. */
data class SectionReading(val code: String?, val point: AxisPoint, val elevationM: Double?, val slope: Double?)

/** Lo que muestra la pantalla de calcular un punto de la sección y verla (S050/RF-9 a RF-11, RF-13, RF-15). */
data class SectionPointUiState(
    val loaded: Boolean = false,
    val alignmentName: String = "",
    val unit: LinearUnit = LinearUnit.METRE,
    val startChainageM: Double = 0.0,
    val endChainageM: Double = 0.0,
    val hasProfile: Boolean = false,
    val chainageText: String = "",
    val mode: SectionPointMode = SectionPointMode.BY_CODE,
    val code: String = SectionCode.AXIS,
    val offsetText: String = "0",
    val name: String = "",
    val chainageInvalid: Boolean = false,
    val offsetInvalid: Boolean = false,
    /** El PK escrito cae fuera del eje: no se calcula nada. */
    val outside: Boolean = false,
    /** Los códigos de la sección de este PK, de izquierda a derecha, para elegir uno. */
    val codes: List<String> = emptyList(),
    /** La sección de diseño en el PK escrito, para dibujarla (RF-13), y el perfil de terreno de ese PK si lo hay. */
    val section: CrossSection? = null,
    val ground: CrossSection? = null,
    /** La cota de la rasante en el PK escrito, para dibujar una sección relativa a ella; null si no hay rasante (RF-11). */
    val profileElevationM: Double? = null,
    val status: SectionPointStatus? = null,
    val reading: SectionReading? = null,
    val showSection: Boolean = false,
    val saving: Boolean = false,
    val savedName: String? = null,
    val duplicateName: Boolean = false,
    val outsideCrsArea: Boolean = false,
) {
    val canSave: Boolean get() = loaded && reading != null && name.isNotBlank() && !saving && savedName == null
}

/**
 * El cálculo de un punto de la sección de un eje (S050/RF-9 a RF-11, RF-13, RF-15): con un PK y un código, o un desplazamiento, da el
 * desplazamiento, el Este y el Norte, la cota (la de la rasante más la sección más el peralte, o la absoluta de la sección) y la
 * pendiente transversal; sin rasante y con una sección relativa solo hay planta (RF-11); más allá de los puntos exteriores dice
 * «Fuera de la sección» (RF-10). «Guardar punto» lo guarda «Calculado sobre eje» con su código de sección (RF-15).
 */
@HiltViewModel
class SectionPointViewModel @Inject constructor(
    private val sections: SectionRepository,
    private val alignments: AlignmentRepository,
    private val projects: ProjectRepository,
    private val points: PointRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val log: AppLog = AppLog.NONE,
) : ViewModel() {
    private val mutable = MutableStateFlow(SectionPointUiState())
    val state: StateFlow<SectionPointUiState> = mutable.asStateFlow()

    private var started = false
    private var project: Project? = null
    private var alignment: Alignment? = null
    private var geometry: AlignmentGeometry? = null
    private var profile: VerticalProfileEval? = null
    private var resolver: SectionResolver? = null
    private var grounds: List<CrossSection> = emptyList()

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
            val stored = sections.sections(alignmentId).first().map { it.section }
            grounds = stored.filter { it.kind == SectionKind.GROUND }
            resolver = SectionResolver(
                sections.templates(alignmentId).first().map { it.template },
                Superelevation(sections.superelevation(alignmentId).first()),
                stored,
            )
            val name = PointNames.nextFree(points.lastName(projectId), points.names(projectId))
            mutable.update {
                it.copy(
                    loaded = true,
                    alignmentName = loaded.name,
                    unit = loadedProject.linearUnit,
                    startChainageM = loaded.startChainage,
                    endChainageM = loaded.endChainage,
                    hasProfile = loaded.profile != null,
                    name = name,
                )
            }
        }
    }

    fun setChainage(text: String) = edit { it.copy(chainageText = text, savedName = null) }

    fun setMode(mode: SectionPointMode) = edit { it.copy(mode = mode, savedName = null) }

    fun setCode(code: String) = edit { it.copy(code = code, savedName = null) }

    fun setOffset(text: String) = edit { it.copy(offsetText = text, savedName = null) }

    fun setName(name: String) = mutable.update { it.copy(name = name, duplicateName = false, savedName = null) }

    /** Enseña u oculta el alzado de la sección en el PK escrito (RF-13). */
    fun setShowSection(show: Boolean) = mutable.update { it.copy(showSection = show) }

    private fun edit(change: (SectionPointUiState) -> SectionPointUiState) = mutable.update { calculate(change(it)) }

    /** Calcula el punto con lo escrito (RF-9, RF-10); un PK fuera del eje no calcula nada. */
    private fun calculate(current: SectionPointUiState): SectionPointUiState {
        val unit = current.unit
        val chainage = ChainageInput.parse(current.chainageText, unit)
        val offset = (if (current.offsetText.isBlank()) 0.0 else NumberInput.parse(current.offsetText))?.let(unit::toMetres)
        val cleared = current.copy(
            chainageInvalid = current.chainageText.isNotBlank() && chainage == null,
            offsetInvalid = current.mode == SectionPointMode.BY_OFFSET && offset == null,
            outside = false,
            codes = emptyList(),
            section = null,
            ground = null,
            profileElevationM = null,
            status = null,
            reading = null,
            duplicateName = false,
            outsideCrsArea = false,
        )
        val geometry = geometry ?: return cleared
        val resolver = resolver ?: return cleared
        if (chainage == null) return cleared
        if (!geometry.contains(chainage)) return cleared.copy(outside = true)
        val section = when (val r = resolver.at(chainage)) {
            is SectionResolution.Found -> r.section
            is SectionResolution.Missing -> return cleared.copy(status = SectionPointStatus.Missing(r.codes, r.heightKindMismatch))
            SectionResolution.OutsideRange -> return cleared.copy(status = SectionPointStatus.NoSection)
        }
        val codes = section.points.map { it.code }
        val ground = grounds.firstOrNull { abs(it.chainage - chainage) < GROUND_MATCH_M }
        val code =
            current.code.takeIf { it in codes } ?: (if (SectionCode.AXIS in codes) SectionCode.AXIS else codes.firstOrNull().orEmpty())
        val base = cleared.copy(
            codes = codes,
            section = section,
            ground = ground,
            profileElevationM = profile?.at(chainage)?.elevation,
            code = code,
        )
        val result = when (current.mode) {
            SectionPointMode.BY_CODE -> SectionEval.byCode(section, geometry, profile, code)
            SectionPointMode.BY_OFFSET -> {
                if (offset == null) return base
                SectionEval.byOffset(section, geometry, profile, offset)
            }
        }
        return when (result) {
            is SectionPointResult.Value -> base.copy(
                reading = SectionReading(result.code, result.axisPoint, result.elevationM, result.slope),
            )
            SectionPointResult.NoCode -> base.copy(status = SectionPointStatus.NoCode)
            SectionPointResult.OutsideSection -> base.copy(status = SectionPointStatus.OutsideSection)
            SectionPointResult.OutsideAxis -> base.copy(outside = true)
        }
    }

    /** Guarda el punto calculado como «Calculado sobre eje», con su código de sección si se pidió por código (RF-15). */
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
                val built = AxisPointBuilder().build(
                    newId(),
                    project,
                    name,
                    alignment.name,
                    reading.point,
                    reading.elevationM,
                    clock(),
                    sectionCode = reading.code,
                )
            ) {
                is AxisPointResult.Rejected -> mutable.update { it.copy(saving = false, outsideCrsArea = true) }
                is AxisPointResult.Built -> when (points.create(built.point)) {
                    PointSaveResult.Saved -> {
                        // S050/RF-8: el log lleva el hecho, nunca el nombre ni las coordenadas.
                        log.info(LogCategory.POINTS, "Punto de una sección guardado")
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

    private companion object {
        /** Un perfil de terreno se dibuja con la sección si está a menos de 1 mm de su PK. */
        const val GROUND_MATCH_M = 1e-3
    }
}
