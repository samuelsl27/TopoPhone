package es.topophone.core.model.surface

/** El nombre que se enseña de una superficie (S061/RF-1, RF-2). */
object SurfaceNames {
    /**
     * [name] tal cual o, si está vacío o solo tiene espacios, el nombre del fichero [fileName] sin su última extensión
     * (`explanacion.xml` → `explanacion`; un fichero sin extensión, o que solo la tiene, se deja entero).
     */
    fun display(name: String?, fileName: String): String {
        if (name != null && name.isNotBlank()) return name
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) fileName.substring(0, dot) else fileName
    }
}
