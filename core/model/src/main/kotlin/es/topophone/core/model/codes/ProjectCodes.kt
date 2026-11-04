package es.topophone.core.model.codes

/** Los códigos que ofrece el recolector en un proyecto (S024/RF-1). */
object ProjectCodes {
    /** Las geometrías del selector del recolector con líneas: todas (S025/RF-1). */
    val ALL_GEOMETRIES: Set<CodeGeometry> = CodeGeometry.entries.toSet()

    /** Las geometrías de «Cambiar código» de una línea: Línea y Polígono (S025/RF-20). */
    val LINE_GEOMETRIES: Set<CodeGeometry> = setOf(CodeGeometry.LINE, CodeGeometry.POLYGON)

    /**
     * Los códigos de la copia de la biblioteca vinculada ([library]) más los creados en el proyecto ([own]),
     * solo de las [geometries] pedidas (Punto por defecto, S024/RF-1; Línea y Polígono para S025/RF-1, RF-20), por
     * orden alfabético de su clave y sin repetir clave.
     */
    fun available(
        library: List<SurveyCode>,
        own: List<SurveyCode>,
        geometries: Set<CodeGeometry> = setOf(CodeGeometry.POINT),
    ): List<SurveyCode> = (library + own)
        .filter { it.geometry in geometries }
        .distinctBy { CodeKey.of(it.code) }
        .sortedBy { CodeKey.of(it.code) }
}

/** Los códigos recientes de un proyecto (S024/RF-1). */
object RecentCodes {
    const val LIMIT = 10

    /** De los códigos de los puntos, del más reciente al más antiguo, los [LIMIT] primeros distintos (por clave). */
    fun distinct(usedNewestFirst: List<String>): List<String> = usedNewestFirst.distinctBy { CodeKey.of(it) }.take(LIMIT)

    /** Los [recent] que siguen entre los [available], en el orden de [recent]. */
    fun resolve(recent: List<String>, available: List<SurveyCode>): List<SurveyCode> {
        val byKey = available.associateBy { CodeKey.of(it.code) }
        return recent.mapNotNull { byKey[CodeKey.of(it)] }
    }
}

/**
 * Lo que muestra el selector del recolector: los [recent] primero, en su orden, y después el resto por orden
 * alfabético (S024/RF-1, RF-2). El índice se calcula una vez.
 */
class CodeSelector(available: List<SurveyCode>, recent: List<String>) {
    private val ordered: List<SurveyCode>
    private val index: CodeIndex = CodeSearch.index(available)

    init {
        val first = RecentCodes.resolve(recent, available)
        val firstIds = first.map { it.id }.toSet()
        ordered = first + index.filter("").filter { it.id !in firstIds }
    }

    /** Los códigos cuyo código o descripción contienen [query], sin distinguir mayúsculas ni acentos (S024/RF-2). */
    fun filter(query: String): List<SurveyCode> {
        if (CodeKey.of(query).isEmpty()) return ordered
        val ids = index.filter(query).map { it.id }.toSet()
        return ordered.filter { it.id in ids }
    }
}

/** La descripción que propone el recolector al elegir un código (S024/RF-4). */
object DescriptionProposal {
    /** La del [code] si la [current] está vacía; si no, la [current]. */
    fun propose(current: String, code: SurveyCode?): String =
        if (current.isBlank() && code != null && code.description.isNotBlank()) code.description else current
}
