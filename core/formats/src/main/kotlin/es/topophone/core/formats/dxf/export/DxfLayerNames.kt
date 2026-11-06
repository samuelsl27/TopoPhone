package es.topophone.core.formats.dxf.export

import java.text.Normalizer
import java.util.Locale

/**
 * Los nombres de capa del DXF de cada código (S029/RF-4, RF-5, plan D6): en mayúsculas y sin acentos, solo letras, cifras, `_`, `-` y `$`
 * (el resto pasa a `_`), hasta 31 caracteres y sin repetir ninguno. Un código vacío o sin ningún carácter válido va a [NO_CODE]. Las capas
 * reservadas (la 0, la de sin código y las de etiquetas) no las puede tomar un código: se le añade `_2`, `_3`…
 *
 * Lleva estado: el mismo código da siempre la misma capa y [used] guarda el orden en que aparecen.
 */
class DxfLayerNames {
    private val byCode = HashMap<String, String>()
    private val taken = HashSet(RESERVED)
    private val order = ArrayList<String>()

    /** Las capas de código dadas hasta ahora (y [NO_CODE] si se pidió), en el orden de aparición. */
    val used: List<String> get() = order.toList()

    /** La capa del código [code] (null: sin código). */
    fun layerOf(code: String?): String {
        val key = code.orEmpty()
        byCode[key]?.let { return it }
        val cleaned = clean(key)
        val name = if (cleaned == null) NO_CODE else unique(cleaned)
        byCode[key] = name
        if (name !in order) order += name
        return name
    }

    private fun unique(base: String): String {
        if (taken.add(base)) return base
        var n = 2
        while (true) {
            val suffix = "_$n"
            val candidate = base.take(MAX_LENGTH - suffix.length) + suffix
            if (taken.add(candidate)) return candidate
            n++
        }
    }

    companion object {
        const val NO_CODE = "SIN_CODIGO"
        const val NAMES = "NOMBRES"
        const val ELEVATIONS = "COTAS"
        const val DESCRIPTIONS = "DESCRIPCIONES"
        const val ZERO = "0"
        const val MAX_LENGTH = 31
        private val RESERVED = setOf(ZERO, NO_CODE, NAMES, ELEVATIONS, DESCRIPTIONS)
        private val MARKS = Regex("\\p{M}+")
        private val VALID = Regex("[A-Z0-9_\\-$]")
        private val INVALID = Regex("[^A-Z0-9_\\-$]")

        /** El código limpio y cortado, o null si no tiene ni un carácter válido. */
        internal fun clean(code: String): String? {
            val upper = Normalizer.normalize(code.trim(), Normalizer.Form.NFD).replace(MARKS, "").uppercase(Locale.ROOT)
            if (!VALID.containsMatchIn(upper)) return null
            return upper.replace(INVALID, "_").take(MAX_LENGTH)
        }
    }
}
