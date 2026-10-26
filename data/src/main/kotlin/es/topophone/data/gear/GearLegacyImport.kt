package es.topophone.data.gear

import es.topophone.core.model.point.PoleKind
import es.topophone.core.survey.PoleHeight
import es.topophone.data.receiver.ReceiverPreferences
import kotlinx.coroutines.flow.first

/**
 * Pasa la altura de jalón que guardaba S002 al primer jalón guardado, una sola vez (S046/RF-29; plan A6). Si ya hay jalones
 * (una copia restaurada, o la importación hecha a medias), no crea ninguno; si no hay ninguno, crea siempre uno: el de la altura
 * antigua o, sin ella, el "Jalón estándar" de 1,800 m (S046/RF-9). Primero se crea el jalón y después se borra el dato
 * antiguo: si la app se cierra entre medias, la segunda vez ya hay un jalón y no se duplica. Los puntos no se tocan.
 */
class GearLegacyImport(private val preferences: ReceiverPreferences, private val repository: GearRepository) {

    /** Los nombres son textos de la interfaz y llegan como parámetro. */
    suspend fun run(standardName: String, previousName: String) {
        if (repository.poles().first().isEmpty()) {
            val legacy = preferences.legacyPoleHeightM()
            val height = legacy?.takeIf { it != PoleHeight.DEFAULT_M } ?: PoleHeight.DEFAULT_M
            val name = if (height == PoleHeight.DEFAULT_M) standardName else previousName
            val text = height.toBigDecimal().toPlainString()
            repository.savePole(null, name, PoleKind.VERTICAL, text)
        }
        preferences.markLegacyPoleHeightImported()
    }
}
