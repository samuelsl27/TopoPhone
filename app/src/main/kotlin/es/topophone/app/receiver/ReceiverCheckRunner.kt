package es.topophone.app.receiver

import es.topophone.core.model.guide.CheckId
import es.topophone.core.model.guide.ConfigListId
import es.topophone.core.model.guide.ConfigLists
import es.topophone.core.model.guide.ReceiverCheck
import es.topophone.core.model.point.BaseSource
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.survey.guide.CheckContext
import es.topophone.core.survey.guide.ReceiverChecker
import es.topophone.data.project.ProjectRepository
import es.topophone.receiver.session.ReceiverSession
import kotlinx.coroutines.flow.first

/** Quien hace las comprobaciones previas con lo que llega del receptor (S043/RF-5); en los tests, uno falso. */
fun interface CheckRunner {
    /** Las comprobaciones de la lista [list] (o todas, si es null) sobre los últimos 10 s; solo lee, nunca escribe en el receptor (RF-11). */
    suspend fun run(list: ConfigListId?): List<ReceiverCheck>

    companion object {
        val NONE = CheckRunner { emptyList() }
    }
}

/** El datum de las correcciones que declara el proyecto abierto más recientemente (S006), para comprobar el del NTRIP (S043/RF-8). */
fun interface ProjectDatumSource {
    suspend fun datum(): CorrectionsDatum?

    companion object {
        val NONE = ProjectDatumSource { null }
    }
}

class RepositoryProjectDatumSource(private val projects: ProjectRepository) : ProjectDatumSource {
    // La búsqueda sin texto devuelve los proyectos del abierto más recientemente al más antiguo (S006/RF-1, RF-4).
    override suspend fun datum(): CorrectionsDatum? = projects.search("").first().firstOrNull()?.project?.correctionsDatum
}

/**
 * Las comprobaciones previas con lo que hay en la ventana de 10 s del [ReceiverWindowRecorder], el modo de inclinación del receptor, el
 * datum del proyecto y el año ITRF de la base de las correcciones (S043/RF-5 a RF-8; plan D1, A2). Es instantánea: solo hace cuentas
 * sobre lo que ya llegó.
 */
class ReceiverCheckRunner(
    private val recorder: ReceiverWindowRecorder,
    private val session: ReceiverSession,
    private val tilt: CurrentTilt,
    private val datum: ProjectDatumSource,
) : CheckRunner {

    override suspend fun run(list: ConfigListId?): List<ReceiverCheck> {
        val settings = tilt.state.value.settings
        val base = session.state.value.base.position?.takeIf { it.source == BaseSource.CORRECTIONS }
        val context = CheckContext(
            ntripOpen = recorder.ntripOpen,
            tiltMode = settings.mode,
            tiltVerified = settings.verified,
            projectDatum = datum.datum(),
            itrfYear = base?.itrfYear,
        )
        val checker = ReceiverChecker { id: CheckId -> list?.let { ConfigLists.stepFor(it, id) } }
        val all = checker.check(recorder.snapshot(), context)
        val inList = list?.let { ConfigLists.byId(it).steps.flatMap { step -> step.checks }.toSet() } ?: return all
        return all.filter { it.id in inList }
    }
}
