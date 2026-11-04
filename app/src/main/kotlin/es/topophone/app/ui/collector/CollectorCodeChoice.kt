package es.topophone.app.ui.collector

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * El código que el recolector debe elegir al volver del editor de códigos del proyecto: el que se acaba de crear, o el existente que el
 * usuario prefirió usar (S024/RF-5, RF-6). Es la clave del código (sin mayúsculas ni acentos); el recolector la consume y la borra.
 */
@Singleton
class CollectorCodeChoice @Inject constructor() {
    val key = MutableStateFlow<String?>(null)
}
