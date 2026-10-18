package es.topophone.app.ui.projects

import es.topophone.app.ui.map.FakeMapPreferences
import es.topophone.app.ui.map.FakeMapViewportRepository
import es.topophone.app.ui.receiver.FakePreferences
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.map.MapPreferences
import es.topophone.data.map.MapViewportRepository
import es.topophone.data.project.ProjectRepository
import es.topophone.receiver.session.ReceiverSession

/** El ViewModel de la vista de proyecto con dobles y sin muestreo, para los tests. */
internal fun projectViewModel(
    repository: ProjectRepository,
    session: ReceiverSession,
    mapPreferences: MapPreferences = FakeMapPreferences(),
    viewports: MapViewportRepository = FakeMapViewportRepository(),
    calculator: ProjectPositionCalculator = ProjectPositionCalculator(),
) = ProjectViewModel(repository, session, FakePreferences(), { calculator }, mapPreferences, viewports, samplePeriodMs = 0)
