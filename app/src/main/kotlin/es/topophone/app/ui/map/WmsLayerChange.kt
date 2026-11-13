package es.topophone.app.ui.map

/**
 * Lo que el mapa tiene que hacer para pasar del plan de capas WMS que ya dibuja al nuevo (S036/RF-21, RF-22, RF-33): nada, cambiar solo la
 * opacidad de alguna capa, o quitar las capas WMS y volver a ponerlas en su orden. En ningún caso se toca el mapa base ni el Catastro.
 */
sealed interface WmsLayerChange {
    /** Nada que cambiar en el mapa: el plan es el mismo o solo cambió una atribución, que se ve fuera del mapa. */
    data object None : WmsLayerChange

    /** Las mismas capas en el mismo orden y con la misma petición; solo cambia la opacidad de [layers], con su valor nuevo. */
    data class Opacity(val layers: List<WmsPlanLayer>) : WmsLayerChange

    /** Cambia qué capas hay, su orden o su petición: se quitan las capas WMS y se ponen de nuevo debajo de la capa ancla. */
    data object Rebuild : WmsLayerChange

    companion object {
        fun between(applied: WmsLayerPlan, desired: WmsLayerPlan): WmsLayerChange {
            if (applied.layers.map { it.id to it.source } != desired.layers.map { it.id to it.source }) return Rebuild
            val changed = desired.layers.filterIndexed { i, layer -> layer.opacity != applied.layers[i].opacity }
            return if (changed.isEmpty()) None else Opacity(changed)
        }
    }
}
