package es.topophone.app.ui.projects

/** Lo que un proyecto puede tener activo y que «Datos del proyecto» enseña, en el orden de S059/RF-18. */
enum class ActiveKind { TERRAIN, LOCALIZATION, BASE_SHIFT, SURFACE, ALIGNMENT, LAYERS }

/** El estado de un elemento activo, en datos: el texto lo pone la pantalla en el idioma del usuario (S059/RF-19). */
sealed interface ActiveStatus {
    /** El factor del plano de terreno (S058). */
    data class Factor(val value: Double) : ActiveStatus

    /** El RMS horizontal de la localización en metros, o ninguno si no lo tiene (S039). */
    data class Rms(val horizontalM: Double?) : ActiveStatus

    /** El nombre del punto conocido del base shift, de la superficie o del eje. */
    data class Name(val text: String) : ActiveStatus

    /** Cuántas capas del mapa hay visibles de las que el proyecto ha añadido (S036). */
    data class Count(val visible: Int, val total: Int) : ActiveStatus
}

data class ActiveItem(val kind: ActiveKind, val status: ActiveStatus)

/** Lo que hay activo en el proyecto, ya reducido a datos simples (S059/RF-18). */
data class ActiveInputs(
    val terrainFactor: Double? = null,
    val localizationActive: Boolean = false,
    val localizationRmsM: Double? = null,
    val baseShiftName: String? = null,
    val surfaceName: String? = null,
    val alignmentName: String? = null,
    val layersVisible: Int = 0,
    val layersTotal: Int = 0,
)

object ActiveItems {
    /**
     * Los elementos activos en el orden terreno, localización, base shift, superficie, eje y capas (S059/RF-18). Las capas solo cuentan si el
     * proyecto ha añadido alguna; el mapa base no es una capa del proyecto (RF-21). Sin nada activo la lista queda vacía.
     */
    fun of(inputs: ActiveInputs): List<ActiveItem> = buildList {
        inputs.terrainFactor?.let { add(ActiveItem(ActiveKind.TERRAIN, ActiveStatus.Factor(it))) }
        if (inputs.localizationActive) add(ActiveItem(ActiveKind.LOCALIZATION, ActiveStatus.Rms(inputs.localizationRmsM)))
        inputs.baseShiftName?.let { add(ActiveItem(ActiveKind.BASE_SHIFT, ActiveStatus.Name(it))) }
        inputs.surfaceName?.let { add(ActiveItem(ActiveKind.SURFACE, ActiveStatus.Name(it))) }
        inputs.alignmentName?.let { add(ActiveItem(ActiveKind.ALIGNMENT, ActiveStatus.Name(it))) }
        if (inputs.layersTotal > 0) add(ActiveItem(ActiveKind.LAYERS, ActiveStatus.Count(inputs.layersVisible, inputs.layersTotal)))
    }
}
