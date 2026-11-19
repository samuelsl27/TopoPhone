package es.topophone.buildlogic.quality

/**
 * El flavor `standard` no puede llevar el módulo experimental ni nada de su flavor (S044/RF-1, ADR-18, ADR-21):
 * ni `:receiver-experimental` en el classpath de ejecución ni componentes, permisos o identificador del flavor
 * en el manifiesto fusionado.
 */
object ExperimentalGuard {

    const val MODULE = ":receiver-experimental"

    private val FLAVOR_NAME = Regex("""android:name\s*=\s*"([^"]*\.experimental(?:\.[^"]*)?)"""")
    private val PACKAGE = Regex("""\bpackage\s*=\s*"([^"]*\.experimental)"""")

    /**
     * @param projectPaths módulos del proyecto (`:app`, `:receiver`…) del classpath de ejecución de la variante.
     * @param manifest texto del manifiesto fusionado de la variante.
     */
    fun findViolations(projectPaths: Collection<String>, manifest: String): List<String> = buildList {
        if (MODULE in projectPaths) add("el classpath de ejecución lleva el módulo $MODULE")
        FLAVOR_NAME.findAll(manifest).forEach { add("el manifiesto lleva '${it.groupValues[1]}', del flavor experimental") }
        PACKAGE.findAll(manifest).forEach { add("el manifiesto lleva el identificador '${it.groupValues[1]}', del flavor experimental") }
    }.distinct().sorted()
}
