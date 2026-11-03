package es.topophone.core.model.codes

/** Qué hacer con un código que ya está en la biblioteca o repetido en el fichero (S023/RF-16, D9). */
enum class DuplicatePolicy { SKIP, REPLACE }

/** Los códigos del fichero que chocan, tal como están escritos en él (S023/RF-16). */
data class MergeConflicts(val inFile: List<String>, val withLibrary: List<String>) {
    fun isEmpty() = inFile.isEmpty() && withLibrary.isEmpty()
}

/**
 * Lo que se aplicará al importar: [toAdd] son códigos nuevos, [toReplace] sustituyen a uno existente (con su id) y [skipped]
 * cuenta los códigos del fichero que no entran.
 */
data class MergePlan(val toAdd: List<SurveyCode>, val toReplace: List<SurveyCode>, val skipped: Int)

object CodeMerge {
    /** Los choques de [incoming] con [existing] y entre sí; un repetido del fichero se lista una vez (S023/RF-16). */
    fun conflicts(existing: List<SurveyCode>, incoming: List<SurveyCode>): MergeConflicts {
        val taken = existing.mapTo(HashSet()) { CodeKey.of(it.code) }
        val seen = HashSet<String>()
        val reported = HashSet<String>()
        val inFile = mutableListOf<String>()
        val withLibrary = mutableListOf<String>()
        for (code in incoming) {
            val key = CodeKey.of(code.code)
            if (key in taken) withLibrary += code.code.trim()
            if (!seen.add(key) && reported.add(key)) inFile += code.code.trim()
        }
        return MergeConflicts(inFile, withLibrary)
    }

    /**
     * Omitir conserva la primera aparición en el fichero y lo que ya había; Reemplazar deja la última aparición y sustituye lo que
     * ya había (S023/RF-16, D9). Nunca salen dos códigos con la misma clave (RF-6).
     */
    fun plan(existing: List<SurveyCode>, incoming: List<SurveyCode>, policy: DuplicatePolicy): MergePlan {
        val chosen = LinkedHashMap<String, SurveyCode>()
        for (code in incoming) {
            val key = CodeKey.of(code.code)
            if (policy == DuplicatePolicy.REPLACE || key !in chosen) chosen[key] = code
        }
        val existingByKey = existing.associateBy { CodeKey.of(it.code) }
        val toAdd = mutableListOf<SurveyCode>()
        val toReplace = mutableListOf<SurveyCode>()
        for ((key, code) in chosen) {
            val current = existingByKey[key]
            when {
                current == null -> toAdd += code
                policy == DuplicatePolicy.REPLACE -> toReplace += code.copy(id = current.id)
            }
        }
        return MergePlan(toAdd, toReplace, skipped = incoming.size - toAdd.size - toReplace.size)
    }
}
