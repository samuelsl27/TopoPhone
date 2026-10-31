package es.topophone.core.model.point

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PhotoDestinationTest {

    @Test
    fun `S017b RF-2 con una medida en marcha la foto va al punto en medida`() {
        PhotoDestination.resolve(measuring = true, autoRunning = false, lastSavedPointId = null) shouldBe PhotoDestination.Measuring
        PhotoDestination.resolve(measuring = true, autoRunning = false, lastSavedPointId = "p1") shouldBe PhotoDestination.Measuring
    }

    @Test
    fun `S017b RF-5 sin medida ni modo automatico la foto va al ultimo punto guardado`() {
        PhotoDestination.resolve(measuring = false, autoRunning = false, lastSavedPointId = "p1") shouldBe PhotoDestination.Saved("p1")
    }

    @Test
    fun `S017b RF-6 con el modo automatico va al ultimo guardado aunque haya otro en medida`() {
        PhotoDestination.resolve(measuring = true, autoRunning = true, lastSavedPointId = "p7") shouldBe PhotoDestination.Saved("p7")
        PhotoDestination.resolve(measuring = false, autoRunning = true, lastSavedPointId = "p7") shouldBe PhotoDestination.Saved("p7")
    }

    @Test
    fun `S017b RF-7 sin ningun punto no hay destino`() {
        PhotoDestination.resolve(measuring = false, autoRunning = false, lastSavedPointId = null) shouldBe PhotoDestination.None
        PhotoDestination.resolve(measuring = false, autoRunning = true, lastSavedPointId = null) shouldBe PhotoDestination.None
        PhotoDestination.resolve(measuring = true, autoRunning = true, lastSavedPointId = null) shouldBe PhotoDestination.None
    }

    @Test
    fun `S017b RF-8 si el ultimo punto desaparece el destino pasa a ninguno`() {
        val before = PhotoDestination.resolve(measuring = false, autoRunning = false, lastSavedPointId = "p1")
        val after = PhotoDestination.resolve(measuring = false, autoRunning = false, lastSavedPointId = null)

        before shouldBe PhotoDestination.Saved("p1")
        after shouldBe PhotoDestination.None
    }
}
