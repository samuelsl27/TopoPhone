package es.topophone.core.model.codes

/** Un índice de los códigos de una biblioteca para ordenarlos y buscar sin recalcular las claves (S023/RF-11). */
class CodeIndex internal constructor(private val entries: List<Entry>) {
    internal class Entry(val code: SurveyCode, val codeKey: String, val descriptionKey: String)

    /** Los códigos cuyo código o descripción contienen [query], sin distinguir mayúsculas ni acentos; vacío muestra todos. */
    fun filter(query: String): List<SurveyCode> {
        val key = CodeKey.of(query)
        if (key.isEmpty()) return entries.map { it.code }
        return entries.filter { it.codeKey.contains(key) || it.descriptionKey.contains(key) }.map { it.code }
    }
}

object CodeSearch {
    /** El índice de [codes], ordenados por su clave (S023/RF-11). */
    fun index(codes: List<SurveyCode>): CodeIndex =
        CodeIndex(codes.map { CodeIndex.Entry(it, CodeKey.of(it.code), CodeKey.of(it.description)) }.sortedBy { it.codeKey })
}
