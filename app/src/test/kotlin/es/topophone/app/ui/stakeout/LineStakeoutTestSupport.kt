package es.topophone.app.ui.stakeout

import es.topophone.app.log.RecordingLog
import es.topophone.app.receiver.CurrentGear
import es.topophone.app.ui.collector.MeasurementInProgress
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.cad.CadRepository
import es.topophone.data.point.CollectorPreferences
import es.topophone.data.point.StakeoutPreferences
import es.topophone.data.project.ProjectRepository
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.session.ReceiverSession
import kotlinx.coroutines.flow.MutableStateFlow

/** Lo común a los tests del replanteo de una línea: preferencias en memoria y el modelo de vista con lo mínimo (S027). */
object LineStakeoutTestSupport {

    class FakeStakeoutPreferences : StakeoutPreferences {
        override val settings = MutableStateFlow(StakeoutSettings())

        override suspend fun save(settings: StakeoutSettings) {
            this.settings.value = settings.copy(toleranceM = settings.validToleranceM, toleranceVM = settings.validToleranceVM)
        }
    }

    class FakeCollectorPreferences : CollectorPreferences {
        override val settings = MutableStateFlow(CollectorSettings())

        override suspend fun save(settings: CollectorSettings) {
            this.settings.value = settings
        }
    }

    val silentAlerts = object : Alerts {
        override fun connectionLost() = Unit

        override fun correctionsLost() = Unit

        override fun pointSaved() = Unit

        override fun solutionLost() = Unit

        override fun tiltLost() = Unit
    }

    fun viewModel(
        projects: ProjectRepository,
        cad: CadRepository,
        preferences: StakeoutPreferences,
        session: ReceiverSession,
        calculator: ProjectPositionCalculator,
        stakeLines: StakeLines,
        inProgress: MeasurementInProgress = MeasurementInProgress(),
    ) = LineStakeoutViewModel(
        projects,
        cad,
        preferences,
        FakeCollectorPreferences(),
        CurrentGear.NONE,
        session,
        silentAlerts,
        { calculator },
        inProgress,
        RecordingLog(),
        stakeLines = stakeLines,
    )
}
