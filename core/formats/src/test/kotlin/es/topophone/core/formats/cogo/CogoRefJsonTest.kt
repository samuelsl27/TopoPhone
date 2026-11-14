package es.topophone.core.formats.cogo

import es.topophone.core.model.cogo.CogoEntry
import es.topophone.core.model.cogo.CogoOperation
import es.topophone.core.model.cogo.CogoRef
import es.topophone.core.model.cogo.CogoValue
import es.topophone.core.model.cogo.IntersectionSide
import es.topophone.core.model.cogo.LegKind
import es.topophone.core.model.cogo.TraverseLeg
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.Locale
import org.junit.jupiter.api.Test

/** El rastro de un punto COGO en JSON: ida y vuelta exacta, con punto decimal y con versión (S038/RF-22, RNF-T9). */
class CogoRefJsonTest {

    private val traverse = CogoRef(
        operation = CogoOperation.TRAVERSE,
        entries = listOf(
            CogoEntry("start", "P1", 440_036.123456789012, 4_474_048.987654321098, 650.2),
            CogoEntry("reference", null, 440_000.0, 4_474_100.0, null),
        ),
        legs = listOf(
            TraverseLeg(LegKind.AZIMUTH, 0.7853981633974483, 50.0, 0.4),
            TraverseLeg(LegKind.DEFLECTION_RIGHT, 0.1 + 0.2, 35.5, null),
            TraverseLeg(LegKind.ANGLE_LEFT, 1e-9, 1.0e7, -0.0),
        ),
    )

    private val intersection = CogoRef(
        operation = CogoOperation.INTERSECTION_DISTANCE_DIRECTION,
        entries = listOf(CogoEntry("first", "A", 1.5, 2.5, 3.5), CogoEntry("second", "B", 4.5, 5.5, null)),
        values = listOf(CogoValue("distance1", 60.123456789), CogoValue("azimuth2", 2.718281828459045)),
        solution = IntersectionSide.SECOND,
    )

    @Test
    fun `S038 RF-22 la ida y vuelta de una poligonal y de una interseccion es exacta`() {
        CogoRefJson.decode(CogoRefJson.encode(traverse)) shouldBe traverse
        CogoRefJson.decode(CogoRefJson.encode(intersection)) shouldBe intersection
    }

    @Test
    fun `S038 RF-22 los Double se guardan sin redondear y los valores que faltan quedan null`() {
        val back = CogoRefJson.decode(CogoRefJson.encode(traverse))!!

        back.entries[0].easting shouldBe 440_036.123456789012
        back.entries[0].northing shouldBe 4_474_048.987654321098
        back.entries[1].name shouldBe null
        back.entries[1].height shouldBe null
        back.legs[1].angleRad shouldBe 0.30000000000000004
        back.legs[1].dHeightM shouldBe null
        back.legs[2].dHeightM.toString() shouldBe "-0.0"
    }

    @Test
    fun `S038 RNF-T9 el texto lleva la version y el punto decimal aunque el idioma use coma`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("es-ES"))
            val text = CogoRefJson.encode(traverse)

            text shouldContain "\"v\":1"
            text shouldContain "\"e\":440036.123456789"
            text shouldContain "\"op\":\"TRAVERSE\""
            text.contains("440036,1") shouldBe false
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun `S038 RF-22 lo que no se puede leer da null`() {
        CogoRefJson.decode("") shouldBe null
        CogoRefJson.decode("no es json") shouldBe null
        CogoRefJson.decode("{\"v\":2,\"op\":\"TRAVERSE\",\"entries\":[],\"legs\":[],\"values\":[]}") shouldBe null // otra version
        CogoRefJson.decode("{\"v\":1,\"op\":\"INVENTADA\",\"entries\":[],\"legs\":[],\"values\":[]}") shouldBe null
        CogoRefJson.decode("{\"v\":1,\"op\":\"TRAVERSE\",\"entries\":[{\"role\":\"start\"}],\"legs\":[],\"values\":[]}") shouldBe null
    }

    @Test
    fun `S038 RF-22 un campo desconocido de una version posterior compatible se ignora`() {
        val text = CogoRefJson.encode(intersection).dropLast(1) + ",\"extra\":{\"a\":1}}"

        CogoRefJson.decode(text) shouldBe intersection
    }
}
