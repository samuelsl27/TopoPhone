package es.topophone.app.ui.export

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
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
import es.topophone.core.formats.kml.export.KmlExportSummary
import es.topophone.core.formats.kml.export.KmlFormat
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La pantalla «Exportar KML/KMZ»: formato, altura, fotos, aviso de 1 GB, guardar, compartir y resumen (S031/RF-20 a RF-32; RNF-T8). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class KmlExportScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun state(
        count: Int = 3,
        lines: Int = 0,
        polygons: Int = 0,
        format: KmlFormat = KmlFormat.KML,
        includeHeight: Boolean = false,
        includePhotos: Boolean = false,
        photoCount: Int = 0,
        photoBytes: Long = 0,
        report: KmlExportReport? = null,
        result: ExportResult? = null,
        noSpace: Boolean = false,
    ) = KmlExportUiState(
        loaded = true,
        projectName = "Calle Mayor 12",
        pointCount = count,
        lineCount = lines,
        polygonCount = polygons,
        photoCount = photoCount,
        photoBytes = photoBytes,
        format = format,
        includeHeight = includeHeight,
        includePhotos = includePhotos,
        report = report,
        result = result,
        noSpace = noSpace,
    )

    private fun show(state: KmlExportUiState) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            KmlExportScreen(
                ThemeState.DEFAULT,
                {},
                state,
                KmlExportActions(
                    onFormat = { calls += "format:${it.name}" },
                    onIncludeHeight = { calls += "height:$it" },
                    onIncludePhotos = { calls += "photos:$it" },
                    onSave = { calls += "save:$it" },
                    onShare = { calls += "share:$it" },
                ),
                onBack = {},
            )
        }
    }

    private fun report(
        points: Int = 3,
        lines: Int = 0,
        polygons: Int = 0,
        folders: Int = 1,
        withoutPosition: Int = 0,
        withoutHeight: Int = 0,
        format: KmlFormat = KmlFormat.KML,
        withPhotos: Boolean = false,
        included: Int = 0,
        omitted: Int = 0,
    ) = KmlExportReport(
        summary = KmlExportSummary(points, lines, polygons, folders, withoutPosition, withoutHeight),
        format = format,
        withPhotos = withPhotos,
        photosIncluded = included,
        photosOmitted = omitted,
    )

    @Test
    fun `S031 RF-20 dice cuantos puntos se exportan en singular y en plural`() {
        show(state(count = 1))
        compose.onNodeWithTag(KML_COUNT_TAG).assertTextContains("Se exportará 1 punto")
    }

    @Test
    fun `S031 RF-20 con varios puntos lo dice en plural`() {
        show(state(count = 3))
        compose.onNodeWithTag(KML_COUNT_TAG).assertTextContains("Se exportarán 3 puntos")
    }

    @Test
    fun `S031 RF-22 dice cuantas lineas y poligonos van y no dice nada si no hay`() {
        show(state(lines = 1, polygons = 2))
        compose.onNodeWithTag(KML_LINES_TAG).assertTextContains("1 línea · 2 polígonos")
    }

    @Test
    fun `S031 RF-22 sin lineas ni poligonos no aparece la linea de recuento`() {
        show(state())
        compose.onAllNodesWithTag(KML_LINES_TAG).assertCountEquals(0)
    }

    @Test
    fun `S031 RF-31 sin puntos lo dice y no deja guardar ni compartir`() {
        show(state(count = 0))

        compose.onNodeWithTag(KML_COUNT_TAG).assertTextContains("No hay puntos que exportar.")
        compose.onNodeWithTag(KML_SAVE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(KML_SHARE_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S031 RF-20 se elige entre KML y KMZ y el elegido aparece seleccionado`() {
        show(state(format = KmlFormat.KML))

        compose.onNodeWithTag(KML_FORMAT_KML_TAG).assertIsSelected()
        compose.onNodeWithTag(KML_FORMAT_KMZ_TAG).performClick()

        calls shouldContainExactly listOf("format:KMZ")
    }

    @Test
    fun `S031 RF-20 con KMZ elegido aparece seleccionado`() {
        show(state(format = KmlFormat.KMZ))
        compose.onNodeWithTag(KML_FORMAT_KMZ_TAG).assertIsSelected()
    }

    @Test
    fun `S031 RF-26 Incluir altura esta desmarcada de fabrica y al tocarla se marca`() {
        show(state())

        compose.onNodeWithTag(KML_HEIGHT_TAG).assertIsOff().performClick()

        calls shouldContainExactly listOf("height:true")
    }

    @Test
    fun `S031 RF-26 con la altura marcada la casilla lo muestra`() {
        show(state(includeHeight = true))
        compose.onNodeWithTag(KML_HEIGHT_TAG).assertIsOn()
    }

    @Test
    fun `S031 RF-28 Incluir fotos solo se ofrece con KMZ`() {
        show(state(format = KmlFormat.KML, includePhotos = true))

        compose.onAllNodesWithTag(KML_PHOTOS_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(KML_PHOTOS_INFO_TAG).assertCountEquals(0)
    }

    @Test
    fun `S031 RF-28 con KMZ Incluir fotos se marca y dice cuantas fotos hay y lo que ocupan`() {
        show(state(format = KmlFormat.KMZ, photoCount = 2, photoBytes = 1_500_000))

        compose.onNodeWithTag(KML_PHOTOS_TAG).assertIsOff().performClick()
        compose.onNodeWithTag(KML_PHOTOS_INFO_TAG).assertTextContains("2 fotos (1,5 MB)", substring = true)

        calls shouldContainExactly listOf("photos:true")
    }

    @Test
    fun `S031 RF-28 con KMZ y fotos marcadas la casilla lo muestra y sin fotos lo dice`() {
        show(state(format = KmlFormat.KMZ, includePhotos = true))

        compose.onNodeWithTag(KML_PHOTOS_TAG).assertIsOn()
        compose.onNodeWithTag(KML_PHOTOS_INFO_TAG).assertTextContains("Ningún punto tiene fotos.")
    }

    @Test
    fun `S031 RF-24 la pantalla avisa de que las coordenadas van en WGS84 como las del datum del proyecto`() {
        show(state())

        compose.onNodeWithTag(KML_DATUM_TAG).assertIsDisplayed()
        compose.onNodeWithTag(KML_DATUM_TAG).assertTextContains("WGS84", substring = true)
        compose.onNodeWithTag(KML_DATUM_TAG).assertTextContains("0,5 a 1 m", substring = true)
    }

    @Test
    fun `S031 RF-31 muestra el nombre propuesto con el proyecto, kml y la fecha y la extension del formato`() {
        show(state(format = KmlFormat.KML))
        compose.onNodeWithTag(KML_FILE_NAME_TAG).assertTextContains("Calle Mayor 12_kml_", substring = true)
        compose.onNodeWithTag(KML_FILE_NAME_TAG).assertTextContains(".kml", substring = true)
    }

    @Test
    fun `S031 RF-31 con KMZ el nombre acaba en kmz`() {
        show(state(format = KmlFormat.KMZ))
        compose.onNodeWithTag(KML_FILE_NAME_TAG).assertTextContains(".kmz", substring = true)
    }

    @Test
    fun `S031 RF-31 guardar y compartir llevan el nombre propuesto a las acciones`() {
        show(state())

        compose.onNodeWithTag(KML_SAVE_TAG).assertIsEnabled().performClick()
        compose.onNodeWithTag(KML_SHARE_TAG).performClick()

        calls.size shouldBe 2
        calls[0] shouldStartWith "save:Calle Mayor 12_kml_"
        calls[1] shouldStartWith "share:Calle Mayor 12_kml_"
    }

    @Test
    fun `S031 RF-30 un KMZ de mas de 1 GB pide confirmar antes de guardar y al continuar guarda`() {
        show(state(format = KmlFormat.KMZ, includePhotos = true, photoCount = 3, photoBytes = 1_200_000_000))

        compose.onNodeWithTag(KML_SAVE_TAG).performScrollTo().performClick()

        calls shouldContainExactly emptyList()
        compose.onNodeWithText("El KMZ ocupará unos 1,2 GB", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(CONFIRM_TAG).performClick()
        calls.size shouldBe 1
        calls[0] shouldStartWith "save:"
    }

    @Test
    fun `S031 RF-30 en el aviso de 1 GB se puede cancelar y no se escribe nada`() {
        show(state(format = KmlFormat.KMZ, includePhotos = true, photoCount = 3, photoBytes = 1_200_000_000))

        compose.onNodeWithTag(KML_SHARE_TAG).performScrollTo().performClick()
        compose.onNodeWithText("Cancelar").performClick()

        calls shouldContainExactly emptyList()
        compose.onAllNodesWithTag(CONFIRM_TAG).assertCountEquals(0)
    }

    @Test
    fun `S031 RF-30 un KMZ de menos de 1 GB y un KML con muchas fotos no piden confirmar`() {
        show(state(format = KmlFormat.KMZ, includePhotos = true, photoCount = 3, photoBytes = 900_000_000))

        compose.onNodeWithTag(KML_SAVE_TAG).performScrollTo().performClick()

        calls.size shouldBe 1
        compose.onAllNodesWithTag(CONFIRM_TAG).assertCountEquals(0)
    }

    @Test
    fun `S031 RF-30 sin espacio libre lo dice con lo que hace falta`() {
        show(state(format = KmlFormat.KMZ, includePhotos = true, photoCount = 1, photoBytes = 850_000_000, noSpace = true))

        compose.onNodeWithTag(KML_NO_SPACE_TAG).assertTextContains("No hay espacio libre", substring = true)
        compose.onNodeWithTag(KML_NO_SPACE_TAG).assertTextContains("850", substring = true)
    }

    @Test
    fun `S031 RNF-T8 los botones y las opciones miden al menos 56 dp`() {
        show(state(format = KmlFormat.KMZ))

        compose.onNodeWithTag(KML_SAVE_TAG).assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(KML_SHARE_TAG).assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(KML_FORMAT_KML_TAG).assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(KML_FORMAT_KMZ_TAG).assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(KML_HEIGHT_TAG).assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(KML_PHOTOS_TAG).assertHeightIsAtLeast(56.dp)
    }

    @Test
    fun `S031 RF-31 si no se ha podido exportar lo dice y si se guardo tambien`() {
        show(state(result = ExportResult.FAILED))
        compose.onNodeWithTag(KML_RESULT_TAG).assertTextContains("No se ha podido exportar el fichero.")
    }

    @Test
    fun `S031 RF-31 un fichero guardado lo dice`() {
        show(state(result = ExportResult.SAVED))
        compose.onNodeWithTag(KML_RESULT_TAG).assertTextContains("Fichero guardado.")
    }

    @Test
    fun `S031 RF-32 el resumen dice los puntos, las lineas, los poligonos y las carpetas`() {
        show(state(result = ExportResult.SAVED, report = report(points = 3, lines = 1, polygons = 2, folders = 2)))

        compose.onNodeWithTag(KML_SUMMARY_TAG).assertTextContains("3 puntos", substring = true)
        compose.onNodeWithTag(KML_SUMMARY_LINES_TAG).assertTextContains("1 línea · 2 polígonos")
        compose.onNodeWithTag(KML_SUMMARY_FOLDERS_TAG).assertTextContains("2 carpetas", substring = true)
    }

    @Test
    fun `S031 RF-32 el resumen dice que WGS84 y el datum del proyecto se han tomado como iguales`() {
        show(state(result = ExportResult.SAVED, report = report()))
        compose.onNodeWithTag(KML_SUMMARY_DATUM_TAG).assertTextContains("se han tomado como iguales", substring = true)
    }

    @Test
    fun `S031 RF-32 un resumen sin avisos no lleva lineas de avisos ni de fotos`() {
        show(state(result = ExportResult.SAVED, report = report()))

        compose.onAllNodesWithTag(KML_NO_POSITION_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(KML_NO_HEIGHT_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(KML_SUMMARY_LINES_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(KML_PHOTOS_DONE_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(KML_PHOTOS_OMITTED_TAG).assertCountEquals(0)
    }

    @Test
    fun `S031 RF-25 el resumen avisa de los puntos sin posicion que no se han escrito`() {
        show(state(result = ExportResult.SAVED, report = report(withoutPosition = 2)))
        compose.onNodeWithTag(KML_NO_POSITION_TAG).assertTextContains("2 puntos sin posición no se han escrito", substring = true)
    }

    @Test
    fun `S031 RF-27 el resumen avisa de las marcas sin altura que van pegadas al suelo`() {
        show(state(result = ExportResult.SAVED, report = report(withoutHeight = 1)))
        compose.onNodeWithTag(KML_NO_HEIGHT_TAG).assertTextContains("1 marca sin altura va pegada al suelo", substring = true)
    }

    @Test
    fun `S031 RF-32 con fotos el resumen dice las incluidas y las omitidas`() {
        show(
            state(
                format = KmlFormat.KMZ,
                includePhotos = true,
                result = ExportResult.SAVED,
                report = report(format = KmlFormat.KMZ, withPhotos = true, included = 4, omitted = 1),
            ),
        )

        compose.onNodeWithTag(KML_PHOTOS_DONE_TAG).assertTextContains("4 fotos incluidas en el KMZ", substring = true)
        compose.onNodeWithTag(KML_PHOTOS_OMITTED_TAG).assertTextContains("1 foto no se ha incluido", substring = true)
    }

    @Test
    fun `S031 RF-32 con fotos y ninguna omitida no hay aviso de omitidas`() {
        show(
            state(
                format = KmlFormat.KMZ,
                includePhotos = true,
                result = ExportResult.SAVED,
                report = report(format = KmlFormat.KMZ, withPhotos = true, included = 2),
            ),
        )

        compose.onNodeWithTag(KML_PHOTOS_DONE_TAG).assertTextContains("2 fotos incluidas en el KMZ", substring = true)
        compose.onAllNodesWithTag(KML_PHOTOS_OMITTED_TAG).assertCountEquals(0)
    }
}
