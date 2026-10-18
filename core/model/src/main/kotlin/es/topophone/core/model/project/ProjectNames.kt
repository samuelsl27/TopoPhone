package es.topophone.core.model.project

import java.text.Normalizer
import java.util.Locale

/** Por qué no vale un nombre de proyecto o de carpeta (S006/RF-7, RF-17). */
enum class NameError { EMPTY, DUPLICATE }

/**
 * Reglas de los nombres de proyectos y carpetas: se comparan sin espacios a los lados, sin
 * mayúsculas y sin tildes. Las palabras de los sufijos ("copia", "restaurado") son textos de la
 * interfaz y llegan como parámetro.
 */
object ProjectNames {

    private val combiningMarks = Regex("\\p{Mn}+")

    /** Clave de comparación: "  Camino de Ávila " y "camino de avila" son el mismo nombre. */
    fun key(text: String): String = Normalizer.normalize(text.trim(), Normalizer.Form.NFD)
        .replace(combiningMarks, "")
        .lowercase(Locale.ROOT)

    fun same(a: String, b: String): Boolean = key(a) == key(b)

    /** S006/RF-7, RF-17. */
    fun validate(name: String, existing: Collection<String>): NameError? = when {
        name.isBlank() -> NameError.EMPTY
        existing.any { same(it, name) } -> NameError.DUPLICATE
        else -> null
    }

    /** S006/RF-4: el texto en cualquier parte, sin mayúsculas ni tildes; vacío encuentra todo. */
    fun matches(text: String, query: String): Boolean = key(text).contains(key(query))

    /** S006/RF-16: "N (copia)", "N (copia 2)", "N (copia 3)"… el primero libre. */
    fun copyName(name: String, existing: Collection<String>, label: String): String =
        firstFree(existing, "${name.trim()} ($label)") { n -> "${name.trim()} ($label $n)" }

    /** S006/RF-25: el mismo nombre si está libre; si no, "N (restaurado)", "N (restaurado 2)"… */
    fun restoredName(name: String, existing: Collection<String>, label: String): String =
        if (existing.none { same(it, name) }) name else copyName(name, existing, label)

    private fun firstFree(existing: Collection<String>, first: String, numbered: (Int) -> String): String {
        val taken = existing.mapTo(HashSet(), ::key)
        if (key(first) !in taken) return first
        return generateSequence(2) { it + 1 }.map(numbered).first { key(it) !in taken }
    }
}

/** Datum de las correcciones frente al del SC del proyecto (S006/RF-11, S008/RF-18). */
object DatumCheck {
    /** Las correcciones no están en el datum del SC (ETRS89 para ETRS89 y ED50, REGCAN95 para REGCAN95). */
    fun mismatch(crs: ProjectCrs, corrections: CorrectionsDatum): Boolean = corrections != crs.correctionsDatum

    /** El caso de S006/RF-11: ITRF/WGS84 y ETRS89 difieren unos 0,9 m en 2026. */
    fun itrfOverEtrs89(crs: ProjectCrs, corrections: CorrectionsDatum): Boolean =
        crs.correctionsDatum == CorrectionsDatum.ETRS89 && corrections == CorrectionsDatum.ITRF_WGS84
}
