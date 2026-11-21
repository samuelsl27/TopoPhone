package es.topophone.core.geodesy.catalog

import java.text.Normalizer

/**
 * Búsqueda en la biblioteca de SC por país, código EPSG y nombre, sin distinguir mayúsculas ni tildes (S034/RF-2, RF-3, RF-5). Cada palabra de la
 * consulta tiene que encajar con algo del SC: el código, el principio de una palabra del nombre o del nombre de uno de sus países (en español o
 * en inglés). Salen primero los SC de España y, dentro de cada grupo, los que encajan mejor.
 */
class CrsSearch(private val catalog: CrsCatalog) {

    private class Indexed(
        val crs: CrsEntry,
        val code: String,
        val nameWords: List<String>,
        val countryWords: List<String>,
        val isSpain: Boolean,
    )

    private val countryWordsByIso: Map<String, List<String>> =
        catalog.countries.associate { it.iso to (words(it.nameEs) + words(it.nameEn)).distinct() }

    private val index: List<Indexed> = catalog.crs.map { crs ->
        Indexed(
            crs = crs,
            code = crs.epsg.toString(),
            nameWords = words(crs.name),
            countryWords = crs.countries.flatMap { countryWordsByIso[it].orEmpty() },
            isSpain = SPAIN in crs.countries,
        )
    }

    /** Los países que tienen algún SC, para el filtro por país, ordenados por su nombre en español. */
    val countries: List<Country> = run {
        val used = catalog.crs.flatMapTo(HashSet()) { it.countries }
        catalog.countries.filter { it.iso in used }.sortedBy { normalize(it.nameEs) }
    }

    /**
     * Los SC que encajan con [query] (vacía = todos), de los que están en [countryIso] si se da, ordenados con los de España primero. [limit]
     * recorta la lista.
     */
    fun search(query: String, countryIso: String? = null, limit: Int = Int.MAX_VALUE): List<CrsEntry> {
        val queryWords = words(query)
        val candidates = if (countryIso == null) index else index.filter { countryIso in it.crs.countries }
        return candidates
            .mapNotNull { entry -> score(entry, queryWords)?.let { entry to it } }
            .sortedWith(
                compareByDescending<Pair<Indexed, Int>> {
                    it.first.isSpain
                }.thenByDescending { it.second }.thenBy { it.first.crs.name },
            )
            .take(limit)
            .map { it.first.crs }
    }

    /** `null` si alguna palabra no encaja; si no, la suma de lo bien que encaja cada una. */
    private fun score(entry: Indexed, queryWords: List<String>): Int? {
        var total = 0
        for (word in queryWords) {
            val best = maxOf(
                when {
                    entry.code == word -> CODE_EXACT
                    entry.code.startsWith(word) -> CODE_PREFIX
                    else -> 0
                },
                when {
                    entry.nameWords.any { it == word } -> NAME_EXACT
                    entry.nameWords.any { it.startsWith(word) } -> NAME_PREFIX
                    else -> 0
                },
                if (entry.countryWords.any { it.startsWith(word) }) COUNTRY else 0,
            )
            if (best == 0) return null
            total += best
        }
        return total
    }

    private companion object {
        const val SPAIN = "ES"
        const val CODE_EXACT = 100
        const val CODE_PREFIX = 60
        const val NAME_EXACT = 30
        const val NAME_PREFIX = 20
        const val COUNTRY = 10

        /** Minúsculas, sin tildes ni signos. */
        fun normalize(text: String): String = Normalizer.normalize(
            text.lowercase(),
            Normalizer.Form.NFD,
        ).replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

        fun words(text: String): List<String> = normalize(text).split(' ').filter { it.isNotEmpty() }
    }
}
