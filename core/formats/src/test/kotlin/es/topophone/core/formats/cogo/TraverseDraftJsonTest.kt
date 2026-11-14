package es.topophone.core.formats.cogo

import es.topophone.core.model.cogo.DraftPoint
import es.topophone.core.model.cogo.LegKind
import es.topophone.core.model.cogo.TraverseDraft
import es.topophone.core.model.cogo.TraverseLeg
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** El borrador de la poligonal en JSON: lo escrito se conserva tal cual, también lo que está a medias (S038/RF-26). */
class TraverseDraftJsonTest {

    private val draft = TraverseDraft(
        projectId = "proj-1",
        start = DraftPoint.OfProject("pt-1", "P1"),
        reference = DraftPoint.Typed(440_000.123456789012, 4_474_100.5),
        closure = null,
        legs = listOf(
            TraverseLeg(LegKind.AZIMUTH, 0.7853981633974483, 50.0, 0.4),
            TraverseLeg(LegKind.ANGLE_RIGHT, null, 35.5, null), // a medio escribir
            TraverseLeg(LegKind.DEFLECTION_LEFT, 1.5707963267948966, null, null),
        ),
        updatedAtMs = 1_700_000_000_000,
    )

    @Test
    fun `S038 RF-26 la ida y vuelta conserva los puntos y los tramos, tambien los incompletos`() {
        val text = TraverseDraftJson.encode(draft)

        TraverseDraftJson.decode(text, "proj-1", 1_700_000_000_000) shouldBe draft
    }

    @Test
    fun `S038 RF-26 el proyecto y la hora son los de la fila, no los del texto`() {
        val text = TraverseDraftJson.encode(draft)

        val back = TraverseDraftJson.decode(text, "otro", 5L)!!

        back.projectId shouldBe "otro"
        back.updatedAtMs shouldBe 5L
        text.contains("proj-1") shouldBe false
        text shouldContain "\"v\":1"
    }

    @Test
    fun `S038 RF-26 un borrador vacio se guarda y se lee, y se sabe que esta vacio`() {
        val empty = TraverseDraft("p", null, null, null, emptyList(), 1L)

        TraverseDraftJson.decode(TraverseDraftJson.encode(empty), "p", 1L) shouldBe empty
        empty.isEmpty shouldBe true
        draft.isEmpty shouldBe false
    }

    @Test
    fun `S038 RF-26 los Este y Norte escritos se guardan sin redondear`() {
        val back = TraverseDraftJson.decode(TraverseDraftJson.encode(draft), "proj-1", 1L)!!

        (back.reference as DraftPoint.Typed).easting shouldBe 440_000.123456789012
    }

    @Test
    fun `S038 RF-26 lo que no se puede leer da null`() {
        TraverseDraftJson.decode("", "p", 1L) shouldBe null
        TraverseDraftJson.decode("[]", "p", 1L) shouldBe null
        TraverseDraftJson.decode("{\"v\":9,\"legs\":[]}", "p", 1L) shouldBe null
        TraverseDraftJson.decode("{\"v\":1,\"start\":{\"pointId\":\"x\"},\"legs\":[]}", "p", 1L) shouldBe null // sin nombre
        TraverseDraftJson.decode("{\"v\":1,\"legs\":[{\"kind\":\"OTRA\"}]}", "p", 1L) shouldBe null
    }
}
