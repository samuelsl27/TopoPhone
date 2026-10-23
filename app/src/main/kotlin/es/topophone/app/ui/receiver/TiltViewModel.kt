package es.topophone.app.ui.receiver

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.receiver.CurrentTilt
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.TiltMode
import es.topophone.core.survey.ReceiverModel
import es.topophone.core.survey.TiltModeWizard
import es.topophone.core.survey.VerticalPole
import es.topophone.core.survey.WizardInconclusive
import es.topophone.core.survey.WizardState
import es.topophone.data.receiver.ReceiverPreferences
import es.topophone.receiver.session.ReceiverSession
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** El apartado "Inclinación" del menú del receptor y su asistente (S016/RF-1 a RF-10). */
@HiltViewModel
class TiltViewModel @Inject constructor(
    private val tilt: CurrentTilt,
    private val log: AppLog,
    private val session: ReceiverSession,
    private val preferences: ReceiverPreferences,
) : ViewModel() {

    private val wizardState = MutableStateFlow<WizardState?>(null)
    private var wizard: TiltModeWizard? = null
    private var wizardJob: Job? = null

    val state: StateFlow<TiltUiState> = combine(tilt.state, wizardState) { current, wizard -> TiltUiState(current.settings, wizard) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TiltUiState(tilt.state.value.settings))

    /** Elegido a mano: queda sin comprobar (S016/RF-3). */
    fun choose(mode: TiltMode) {
        viewModelScope.launch {
            tilt.choose(mode)
            log.info(LogCategory.RECEIVER, "Modo de inclinación: ${mode.logName()} (sin comprobar)")
            if (mode == TiltMode.VERTICAL && tilt.state.value.settings.detected == TiltMode.RECEIVER) {
                log.warning(LogCategory.RECEIVER, "Jalón vertical con un receptor que compensa") // S016/RF-4, S014/RF-5
            }
        }
    }

    /** Abre el asistente con la altura de antena de la app (S016/RF-5; plan A7) y cancela la medida en curso (RF-28). */
    fun openWizard() {
        tilt.openWizard()
        wizardJob?.cancel()
        wizardJob = viewModelScope.launch {
            val antennaHeight = VerticalPole.antennaHeightM(preferences.poleHeightM.first(), ReceiverModel.RS3)
            val created = TiltModeWizard(antennaHeight)
            wizard = created
            wizardState.value = created.state
            launch {
                session.epochs.collect { received -> publish(created.onEpoch(received.epoch, System.currentTimeMillis())) }
            }
            while (true) {
                delay(TICK_MS)
                publish(created.onTick(System.currentTimeMillis()))
            }
        }
    }

    /** S016/RF-6. */
    fun startTest() {
        val created = wizard ?: return
        if (created.start(System.currentTimeMillis())) publish(created.state)
    }

    /** "Repetir" (S016/RF-9). */
    fun repeatTest() {
        val created = wizard ?: return
        created.reset()
        publish(created.state)
    }

    /** Guarda el modo propuesto como comprobado (S016/RF-10). */
    fun confirm() {
        val proposed = wizardState.value as? WizardState.Proposed ?: return
        viewModelScope.launch {
            tilt.confirm(proposed.mode)
            log.info(LogCategory.RECEIVER, "Modo de inclinación: ${proposed.mode.logName()} (comprobado)")
            closeWizard()
        }
    }

    fun closeWizard() {
        wizardJob?.cancel()
        wizardJob = null
        wizard = null
        wizardState.value = null
        tilt.closeWizard()
    }

    override fun onCleared() {
        if (wizard != null) tilt.closeWizard()
    }

    private fun publish(state: WizardState) {
        val previous = wizardState.value
        if (state == previous) return
        wizardState.value = state
        if (state is WizardState.Inconclusive && previous !is WizardState.Inconclusive) {
            log.warning(LogCategory.RECEIVER, "Asistente de la inclinación no concluyente: ${state.reason.logText()}") // S014/RF-5
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val TICK_MS = 250L
    }
}

/** El modo en el log, en español y sin depender del idioma de la interfaz (S014). */
internal fun TiltMode.logName(): String = when (this) {
    TiltMode.APP -> "calculada por la app"
    TiltMode.RECEIVER -> "compensada por el receptor"
    TiltMode.VERTICAL -> "jalón vertical"
}

private fun WizardInconclusive.logText(): String = when (this) {
    WizardInconclusive.MISMATCH -> "la posición no se ha movido como en ningún modo"
    WizardInconclusive.LOST -> "se ha perdido el FIX o la inclinación"
    WizardInconclusive.TIMEOUT -> "el jalón no se ha inclinado a tiempo"
}
