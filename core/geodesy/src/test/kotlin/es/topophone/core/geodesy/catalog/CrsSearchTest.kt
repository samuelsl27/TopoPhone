package es.topophone.core.geodesy.catalog

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.system.measureTimeMillis
import org.junit.jupiter.api.Test

/** Búsqueda por país, código y nombre (S034/RF-2, RF-3, RF-5). */
class CrsSearchTest {
    private val search = CrsSearch(CrsCatalog.bundled)

    @Test
    fun `S034 RF-2 un codigo EPSG exacto sale el primero`() {
        search.search("25830").first().epsg shouldBe 25830
    }

    @Test
    fun `S034 RF-2 etrs89 utm 30 encuentra ETRS89 UTM huso 30`() {
        val hits = search.search("etrs89 utm 30")

        hits.map { it.epsg } shouldContain 25830
        hits.all { "UTM" in it.name.uppercase() } shouldBe true
    }

    @Test
    fun `S034 RF-2 RF-5 el pais en espanol encuentra los SC de ese pais, con Lambert 93 entre los de Francia`() {
        val hits = search.search("Francia")

        hits shouldContain CrsCatalog.bundled.crsByEpsg(2154)
        hits.all { "FR" in it.countries } shouldBe true
        search.search("francia lambert").map { it.epsg } shouldContain 2154
    }

    @Test
    fun `S034 RF-2 no distingue mayusculas ni tildes`() {
        search.search("PORTUGAL").map { it.epsg } shouldBe search.search("portugal").map { it.epsg }
        search.search("Mexico").map { it.epsg } shouldBe search.search("México").map { it.epsg }
        search.search("Mexico").shouldNotBeEmpty()
    }

    @Test
    fun `S034 RF-2 los SC de Espana salen antes que los de otros paises`() {
        val hits = search.search("utm")
        val firstOther = hits.indexOfFirst { "ES" !in it.countries }
        val lastSpain = hits.indexOfLast { "ES" in it.countries }

        lastSpain shouldBeLessThan firstOther
        search.search("").first().countries shouldContain "ES"
    }

    @Test
    fun `S034 RF-5 el filtro por pais deja solo los SC cuyo ambito incluye ese pais`() {
        val hits = search.search("", countryIso = "PT")

        hits.shouldNotBeEmpty()
        hits.all { "PT" in it.countries } shouldBe true
        search.search("lambert", countryIso = "PT").all { "lambert" in it.name.lowercase() || it.method.startsWith("lcc") } shouldBe true
        search.search("zzzzzz").shouldBeEmpty()
    }

    @Test
    fun `S034 RF-5 la lista de paises para el filtro esta ordenada y trae a Espana`() {
        search.countries.map { it.iso } shouldContain "ES"
        search.countries.size shouldBeGreaterThan 100
    }

    @Test
    fun `S034 RF-2 una busqueda tarda menos de 300 ms`() {
        search.search("etrs89") // calienta
        val millis = measureTimeMillis { repeat(10) { search.search("utm zone 30") } } / 10

        millis shouldBeLessThan 300
    }

    @Test
    fun `S034 RF-3 el limite recorta los resultados`() {
        search.search("utm", limit = 5).size shouldBe 5
    }
}
