package es.topophone.core.model.codes

/** Los códigos que ofrece el recolector en un proyecto (S024/RF-1). */
object ProjectCodes {
    /**
     * Los códigos de la copia de la biblioteca vinculada ([library]) más los creados en el proyecto ([own]),
     * solo de geometría Punto, por orden alfabético de su clave y sin repetir clave.
     */
    fun available(library: List<SurveyCode>, own: List<SurveyCode>): List<SurveyCode> = (library + own)
        .filter { it.geometry == CodeGeometry.POINT }
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
