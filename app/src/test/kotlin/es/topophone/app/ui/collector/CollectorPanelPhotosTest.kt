package es.topophone.app.ui.collector

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.PhotoDestination
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.LinearUnit
import es.topophone.data.photo.PhotoFailure
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** «Foto» y «Galería» del recolector: el destino, el recuento, el límite y la pregunta al cancelar (S017b/RF-1, RF-3, RF-7, RF-8). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CollectorPanelPhotosTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = CollectorActions(
        onPhoto = { calls += "photo" },
        onGallery = { calls += "gallery" },
        onDiscardPhotos = { calls += "discard" },
        onKeepPhotos = { calls += "keep" },
    )
    private val ready =
        CollectorUiState(loaded = true, name = "12", poleHeightText = "1,800", live = LiveQuality(SolutionStatus.FIX, 0.01, 0.015))

    private fun show(photos: PhotoUi) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { CollectorPanel(ready.copy(photos = photos), LinearUnit.METRE, actions, onClose = {}) }
        }
    }

    private val withDestination = PhotoUi(PhotoDestination.Measuring, destinationName = "12", count = 3, canAdd = true)

    @Test
    fun `S017b RF-1 Foto y Galeria llaman a la camara y a la galeria`() {
        show(withDestination)

        compose.onNodeWithTag(COLLECTOR_PHOTO_TAG).assertIsEnabled().performClick()
        compose.onNodeWithTag(COLLECTOR_GALLERY_TAG).assertIsEnabled().performClick()

        calls shouldBe listOf("photo", "gallery")
    }

    @Test
    fun `S017b RF-8 el panel dice a que punto irá la foto y cuantas lleva de 10`() {
        show(withDestination)

        compose.onNodeWithTag(COLLECTOR_PHOTO_DESTINATION_TAG).assertIsDisplayed()
        compose.onNodeWithText("Foto para el punto 12 · 3 de 10").assertIsDisplayed()
    }

    @Test
    fun `S017b RF-7 y S060 RF-21 sin punto no hay botones desactivados y el panel dice el motivo`() {
        show(PhotoUi())

        compose.onAllNodesWithTag(COLLECTOR_PHOTO_TAG).fetchSemanticsNodes().size shouldBe 0
        compose.onAllNodesWithTag(COLLECTOR_GALLERY_TAG).fetchSemanticsNodes().size shouldBe 0
        compose.onNodeWithText("Mide un punto primero para añadirle fotos").assertIsDisplayed()
    }

    @Test
    fun `S017b RF-14 con 10 fotos los botones se desactivan y se indica el limite`() {
        show(PhotoUi(PhotoDestination.Saved("p"), destinationName = "12", count = 10, canAdd = false, error = PhotoError.Full))

        compose.onNodeWithTag(COLLECTOR_PHOTO_TAG).assertIsNotEnabled()
        compose.onNodeWithText("Máximo de 10 fotos por punto").assertIsDisplayed()
    }

    @Test
    fun `S017b RF-13 el aviso dice cuantas fotos se han quedado fuera`() {
        show(withDestination.copy(error = PhotoError.NotAllFit(2)))

        compose.onNodeWithTag(COLLECTOR_PHOTO_ERROR_TAG)
        compose.onNodeWithText("2 fotos no caben y no se han añadido").assertIsDisplayed()
    }

    @Test
    fun `S017b RF-15 el aviso de error dice el motivo`() {
        show(withDestination.copy(error = PhotoError.Failed(PhotoFailure.NO_SPACE)))

        compose.onNodeWithTag(COLLECTOR_PHOTO_ERROR_TAG)
        compose.onNodeWithText("No se ha podido guardar la foto: no hay espacio").assertIsDisplayed()
    }

    @Test
    fun `S017b RF-3 cancelar con fotos pregunta cuantas se descartan`() {
        show(withDestination.copy(pendingCancel = 3))

        compose.onNodeWithText("¿Descartar 3 fotos?").assertIsDisplayed()
        compose.onNodeWithTag(COLLECTOR_PHOTO_DISCARD_TAG).performClick()
        compose.onNodeWithTag(COLLECTOR_PHOTO_KEEP_TAG).performClick()

        calls shouldBe listOf("discard", "keep")
    }

    @Test
    fun `S017b RF-3 con una sola foto la pregunta va en singular`() {
        show(withDestination.copy(pendingCancel = 1))

        compose.onNode(hasText("¿Descartar 1 foto?")).assertIsDisplayed()
    }

    @Test
    fun `RNF-T8 las zonas tactiles de Foto y Galeria miden 56 dp`() {
        show(withDestination)

        compose.onNodeWithTag(COLLECTOR_PHOTO_TAG).assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(COLLECTOR_GALLERY_TAG).assertHeightIsAtLeast(56.dp)
    }
}
