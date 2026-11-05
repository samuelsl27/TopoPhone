package es.topophone.core.model.point

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El punto replanteado de una línea del proyecto guarda su identificador y su nombre (S027/RF-11, RF-12, RF-16). */
class StakeSourceProjectLineTest {

    private val design = PointCoordinates(GeodeticPosition(40.4168, -3.7038, 699.423), 440290.45805, 4474257.38189, 648.19)

    private fun ref(source: StakeSource) = StakeoutRef("line:l1", "BORD_1", design, 0.0, 0.0, 0.0, null, source = source)

    @Test
    fun `S027 RF-12 la fuente guarda el identificador y el nombre de la linea`() {
        val source = StakeSource.ProjectLine("l1", "BORD_1")

        source.lineId shouldBe "l1"
        source.lineName shouldBe "BORD_1"
        source shouldBe StakeSource.ProjectLine("l1", "BORD_1")
    }

    @Test
    fun `S027 RF-13 el replanteo de una linea del proyecto no es de un dibujo ni de un eje`() {
        val ref = ref(StakeSource.ProjectLine("l1", "BORD_1"))

        ref.fromDrawing shouldBe false
        ref.fromAlignment shouldBe false
        ref.fromProjectLine shouldBe true
    }

    @Test
    fun `S027 RF-13 los demas origenes no son de una linea del proyecto`() {
        ref(StakeSource.ProjectPoint).fromProjectLine shouldBe false
        ref(StakeSource.Alignment("Eje A")).fromProjectLine shouldBe false
    }
}
