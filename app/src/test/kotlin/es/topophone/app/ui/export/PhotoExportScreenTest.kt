package es.topophone.app.ui.export

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.projects.CONFIRM_TAG
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La pantalla «Exportar fotos»: recuento, aviso de 1 GB, guardar, compartir y resumen (S056/RF-3, RF-8 a RF-13; RNF-T8). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class PhotoExportScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun state(
        photos: Int = 8,
        points: Int = 3,
        bytes: Long = 426_000,
        estimated: Long = 430_000,
        confirm: Boolean = false,
        report: PhotoExportReport? = null,
        result: ExportResult? = null,
        noSpace: Boolean = false,
    ) = PhotoExportUiState(
        loaded = true,
        projectName = "Calle Mayor 12",
        photoCount = photos,
        pointCount = points,
        photoBytes = bytes,
        estimatedBytes = estimated,
        needsSizeConfirmation = confirm,
        report = report,
        result = result,
        noSpace = noSpace,
    )

    private fun show(state: PhotoExportUiState) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            PhotoExportScreen(
                ThemeState.DEFAULT,
                {},
                state,
                PhotoExportActions(onSave = { calls += "save:$it" }, onShare = { calls += "share:$it" }),
                onBack = {},
            )
        }
    }

    @Test
    fun `S056 RF-3 dice cuantas fotos de cuantos puntos y lo que ocupan`() {
        show(state(photos = 8, points = 3, bytes = 426_000))

        compose.onNodeWithTag(PHOTO_COUNT_TAG).assertTextContains("Se exportarán 8 fotos de 3 puntos (426 kB)")
    }

    @Test
    fun `S056 RF-3 con una foto de un punto lo dice en singular`() {
        show(state(photos = 1, points = 1, bytes = 850_000))

        compose.onNodeWithTag(PHOTO_COUNT_TAG).assertTextContains("Se exportará 1 foto de 1 punto (850 kB)")
    }

    @Test
    fun `S056 RF-8 sin fotos lo dice y no deja guardar ni compartir`() {
        show(state(photos = 0, points = 0, bytes = 0))

        compose.onNodeWithTag(PHOTO_COUNT_TAG).assertTextContains("No hay fotos que exportar.")
        compose.onNodeWithTag(PHOTO_SAVE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(PHOTO_SHARE_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S056 RF-4 la pantalla avisa de que van como estan guardadas y del indice`() {
        show(state())

        compose.onNodeWithTag(PHOTO_HINT_TAG).assertTextContains("tal como están guardadas", substring = true)
        compose.onNodeWithTag(PHOTO_HINT_TAG).assertTextContains("fotos.csv", substring = true)
    }

    @Test
    fun `S056 RF-12 muestra el nombre propuesto con el proyecto, fotos y la fecha`() {
        show(state())

        compose.onNodeWithTag(PHOTO_FILE_NAME_TAG).assertTextContains("Calle Mayor 12_fotos_", substring = true)
        compose.onNodeWithTag(PHOTO_FILE_NAME_TAG).assertTextContains(".zip", substring = true)
    }

    @Test
    fun `S056 RF-12 guardar y compartir llevan el nombre propuesto a las acciones`() {
        show(state())

        compose.onNodeWithTag(PHOTO_SAVE_TAG).assertIsEnabled().performClick()
        compose.onNodeWithTag(PHOTO_SHARE_TAG).performClick()

        calls.size shouldBe 2
        calls[0] shouldStartWith "save:Calle Mayor 12_fotos_"
        calls[1] shouldStartWith "share:Calle Mayor 12_fotos_"
    }

    @Test
    fun `S056 RF-10 un ZIP de mas de 1 GB pide confirmar antes de guardar y al continuar guarda`() {
        show(state(confirm = true, estimated = 1_200_000_000))

        compose.onNodeWithTag(PHOTO_SAVE_TAG).performScrollTo().performClick()

        calls shouldContainExactly emptyList()
        compose.onNodeWithText("El ZIP ocupará unos 1,2 GB", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(CONFIRM_TAG).performClick()
        calls.size shouldBe 1
        calls[0] shouldStartWith "save:"
    }

    @Test
    fun `S056 RF-10 en el aviso de 1 GB se puede cancelar y no se escribe nada`() {
        show(state(confirm = true, estimated = 1_200_000_000))

        compose.onNodeWithTag(PHOTO_SHARE_TAG).performScrollTo().performClick()
        compose.onNodeWithText("Cancelar").performClick()

        calls shouldContainExactly emptyList()
        compose.onAllNodesWithTag(CONFIRM_TAG).assertCountEquals(0)
    }

    @Test
    fun `S056 RF-10 un ZIP de menos de 1 GB no pide confirmar`() {
        show(state(confirm = false))

        compose.onNodeWithTag(PHOTO_SAVE_TAG).performScrollTo().performClick()

        calls.size shouldBe 1
        compose.onAllNodesWithTag(CONFIRM_TAG).assertCountEquals(0)
    }

    @Test
    fun `S056 RF-11 sin espacio libre lo dice con lo que hace falta`() {
        show(state(estimated = 850_000, noSpace = true))

        compose.onNodeWithTag(PHOTO_NO_SPACE_TAG).performScrollTo()
            .assertTextContains("No hay espacio libre para escribir el ZIP: hacen falta unos 850 kB", substring = true)
    }

    @Test
    fun `S056 RF-11 tras guardar dice Fichero guardado y si falla que no se ha podido`() {
        show(state(result = ExportResult.SAVED))
        compose.onNodeWithTag(PHOTO_RESULT_TAG).performScrollTo().assertTextContains("Fichero guardado.")
    }

    @Test
    fun `S056 RF-11 si no se pudo exportar lo dice`() {
        show(state(result = ExportResult.FAILED))
        compose.onNodeWithTag(PHOTO_RESULT_TAG).performScrollTo().assertTextContains("No se ha podido exportar el fichero.")
    }

    @Test
    fun `S056 RF-13 el resumen dice las fotos y los puntos escritos y lo que pesa el ZIP`() {
        show(state(report = PhotoExportReport(photos = 8, points = 3, omitted = 0, zipBytes = 429_492)))

        compose.onNodeWithTag(PHOTO_SUMMARY_TAG).performScrollTo().assertTextContains("Se han exportado 8 fotos de 3 puntos.")
        compose.onNodeWithTag(PHOTO_SUMMARY_SIZE_TAG).performScrollTo().assertTextContains("El ZIP pesa 429 kB.")
        compose.onAllNodesWithTag(PHOTO_OMITTED_TAG).assertCountEquals(0)
    }

    @Test
    fun `S056 RF-13 con una foto de un punto el resumen va en singular`() {
        show(state(report = PhotoExportReport(photos = 1, points = 1, omitted = 0, zipBytes = 11_871)))

        compose.onNodeWithTag(PHOTO_SUMMARY_TAG).performScrollTo().assertTextContains("Se ha exportado 1 foto de 1 punto.")
    }

    @Test
    fun `S056 RF-9 el resumen cuenta las fotos omitidas por no poder leerse`() {
        show(state(report = PhotoExportReport(photos = 7, points = 3, omitted = 1, zipBytes = 353_651)))
        compose.onNodeWithTag(PHOTO_OMITTED_TAG).performScrollTo()
            .assertTextContains("1 foto no se ha incluido porque no se puede leer.")
    }

    @Test
    fun `S056 RF-9 con varias omitidas lo dice en plural`() {
        show(state(report = PhotoExportReport(photos = 5, points = 2, omitted = 3, zipBytes = 100_000)))
        compose.onNodeWithTag(PHOTO_OMITTED_TAG).performScrollTo()
            .assertTextContains("3 fotos no se han incluido porque no se pueden leer.")
    }

    @Test
    fun `S056 RF-9 si no se pudo leer ninguna solo dice las omitidas y no que se exportaron`() {
        show(state(result = ExportResult.FAILED, report = PhotoExportReport(photos = 0, points = 0, omitted = 2, zipBytes = 0)))

        compose.onAllNodesWithTag(PHOTO_SUMMARY_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(PHOTO_SUMMARY_SIZE_TAG).assertCountEquals(0)
        compose.onNodeWithTag(PHOTO_OMITTED_TAG).performScrollTo()
            .assertTextContains("2 fotos no se han incluido porque no se pueden leer.")
    }

    @Test
    fun `S056 RNF-T8 los botones miden 56 dp o mas`() {
        show(state())

        compose.onNodeWithTag(PHOTO_SAVE_TAG).assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(PHOTO_SHARE_TAG).assertHeightIsAtLeast(56.dp)
    }

    @Test
    fun `S056 RF-3 mientras no esta cargado no se muestra nada`() {
        show(PhotoExportUiState())

        compose.onAllNodesWithTag(PHOTO_COUNT_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(PHOTO_SAVE_TAG).assertCountEquals(0)
    }
}
