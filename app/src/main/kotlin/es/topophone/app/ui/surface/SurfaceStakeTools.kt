package es.topophone.app.ui.surface

/** «Replantear superficie» en «Herramientas» de Objetos (S062/RF-3, RF-4). */
object SurfaceStakeTools {
    /** Con superficie activa, su replanteo ([onStake]); sin ella, la lista de superficies del proyecto para elegir una ([onChoose]). */
    fun open(activeId: String?, onStake: (surfaceId: String) -> Unit, onChoose: () -> Unit) {
        if (activeId != null) onStake(activeId) else onChoose()
    }
}
