package es.topophone.app.log

import es.topophone.receiver.session.RecordedSession
import es.topophone.receiver.session.SessionLogStore
import es.topophone.receiver.session.SessionSource
import java.io.File

/** Una sesión del informe con sus ficheros; [partial] si solo va su parte más reciente. */
data class SelectedSession(val id: String, val files: List<File>, val partial: Boolean)

/**
 * Qué sesiones grabadas entran en el informe de sistema (S014/RF-12; plan, A6): la sesión en curso y las más recientes que
 * quepan entre todas en 25 MB; las importadas no (suposición de la spec). Si la primera no cabe entera, entra su parte más
 * reciente: sus metadatos y las partes escritas al final, mientras quepan.
 */
object SessionSelection {
    const val LIMIT_BYTES = 25L * 1024 * 1024

    fun select(sessions: List<RecordedSession>, activeId: String?, limitBytes: Long = LIMIT_BYTES): List<SelectedSession> {
        val recorded = sessions.filter { it.source == SessionSource.RECORDED }
        val ordered = recorded.filter { it.id == activeId } + recorded.filter { it.id != activeId }.sortedByDescending { it.startedAt }
        var remaining = limitBytes
        val selected = mutableListOf<SelectedSession>()
        for (session in ordered) {
            val files = files(session)
            val size = files.sumOf { it.length() }
            if (size <= remaining) {
                selected += SelectedSession(session.id, files, partial = false)
                remaining -= size
            } else {
                if (selected.isEmpty()) tail(session, remaining)?.let { selected += it }
                break
            }
        }
        return selected
    }

    private fun files(session: RecordedSession): List<File> =
        listOfNotNull(metadata(session)) + session.receiverFiles + session.correctionFiles

    private fun metadata(session: RecordedSession): File? = File(session.directory, SessionLogStore.METADATA_FILE).takeIf { it.isFile }

    /** La parte más reciente de [session] que cabe en [limit]: los metadatos y las partes escritas al final. */
    private fun tail(session: RecordedSession, limit: Long): SelectedSession? {
        var remaining = limit
        val chosen = mutableListOf<File>()
        metadata(session)?.let {
            if (it.length() <= remaining) {
                chosen += it
                remaining -= it.length()
            }
        }
        for (part in (session.receiverFiles + session.correctionFiles).sortedByDescending { it.lastModified() }) {
            if (part.length() > remaining) break
            chosen += part
            remaining -= part.length()
        }
        return if (chosen.isEmpty()) null else SelectedSession(session.id, chosen, partial = true)
    }
}
