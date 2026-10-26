package es.topophone.core.survey

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El catálogo de modelos Reach con su desplazamiento al centro de fase (S046/RF-1, RF-2). */
class AntennaCatalogTest {

    @Test
    fun `S046 RF-1 el catalogo trae los desplazamientos de la documentacion de Emlid`() {
        val offsets = AntennaCatalog.ALL.associate { it.name to it.offsetM }
        offsets["Reach RS3"] shouldBe 0.134
        offsets["Reach RS2/RS2+"] shouldBe 0.134
        offsets["Reach RS/RS+"] shouldBe 0.065
        offsets["Reach RS4/RS4 Pro"] shouldBe 0.095
        offsets["Reach RX"] shouldBe 0.1471
        AntennaCatalog.ALL shouldHaveSize 5
    }

    @Test
    fun `S046 RF-1 ningun modelo del catalogo trae radio porque Emlid no lo publica`() {
        AntennaCatalog.ALL.forEach { it.radiusM.shouldBeNull() }
        AntennaCatalog.ALL.all { it.builtIn } shouldBe true
    }

    @Test
    fun `S046 RF-2 el RS3 es el perfil por defecto con 0,134 m, el mismo que usa S002 RF-32`() {
        AntennaProfile.RS3.offsetM shouldBe 0.134
        AntennaProfile.RS3.ref shouldBe "catalog:RS3"
        AntennaCatalog.byRef("catalog:RS3") shouldBe AntennaProfile.RS3
    }

    @Test
    fun `S046 RF-1 los identificadores del catalogo son unicos y se resuelven por su referencia`() {
        AntennaCatalog.ALL.map { it.ref }.toSet() shouldHaveSize AntennaCatalog.ALL.size
        AntennaCatalog.ALL.forEach { AntennaCatalog.byRef(it.ref).shouldNotBeNull() shouldBe it }
        AntennaCatalog.byRef("catalog:NOPE").shouldBeNull()
        AntennaCatalog.byRef("own:1234").shouldBeNull()
    }
}
