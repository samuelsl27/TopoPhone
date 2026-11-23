package es.topophone.app.ui.collector

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.baseshift.BASE_SHIFT_BANNER_TAG
import es.topophone.app.ui.baseshift.BASE_SHIFT_BASE_CHANGED_TAG
import es.topophone.app.ui.baseshift.BASE_SHIFT_DATUM_NOTICE_TAG
import es.topophone.app.ui.field.FieldSheet
import es.topophone.app.ui.field.FieldSheetValue
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.point.PhotoDestination
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.survey.TiltGuide
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** El recolector expandido: nombre, descripción, código, fotos y líneas sin filas cortadas, y un solo aviso (S060/RF-20 a RF-22). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "es-rES-w360dp-h640dp")
class CollectorExpandedTest {

    @get:Rule
    val compose = createComposeRule()

    private val ready = CollectorUiState(loaded = true, name = "P1", poleHeightText = "1,800")
    private val withPoint = PhotoUi(PhotoDestination.Saved("p"), destinationName = "12", count = 3, canAdd = true)

    private fun show(state: CollectorUiState, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                TopoPhoneTheme(EffectiveTheme.LIGHT) {
                    Box(Modifier.fillMaxSize()) {
                        FieldSheet(
                            value = FieldSheetValue.Expanded,
                            onValueChange = {},
                            onClose = {},
                            modifier = Modifier.align(Alignment.BottomCenter),
                            folded = {},
                            expanded = { CollectorExpanded(state, LinearUnit.METRE, CollectorActions()) },
                        )
                    }
                }
            }
        }
    }

    private val rows = listOf(COLLECTOR_NAME_TAG, COLLECTOR_DESCRIPTION_TAG, COLLECTOR_CODE_TAG, COLLECTOR_PHOTO_DESTINATION_TAG)

    @Test
    fun `S060 RF-20 la hoja expandida muestra nombre descripcion codigo y fotos`() {
        show(ready.copy(photos = withPoint))

        rows.forEach { compose.onNodeWithTag(it).performScrollTo().assertIsDisplayed() }
        compose.onNodeWithTag(COLLECTOR_PHOTO_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(COLLECTOR_GALLERY_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S060 RF-20 con la letra al 130 por cien ninguna fila se sale del ancho ni queda tapada`() {
        show(ready.copy(photos = withPoint), fontScale = 1.3f)

        val width = compose.onRoot().getUnclippedBoundsInRoot().width
        (rows + COLLECTOR_PHOTO_TAG + COLLECTOR_GALLERY_TAG).forEach { tag ->
            val node = compose.onNodeWithTag(tag).performScrollTo()
            node.assertIsDisplayed()
            val bounds = node.getUnclippedBoundsInRoot()
            (bounds.left >= 0.dp && bounds.right <= width) shouldBe true
        }
    }

    @Test
    fun `S060 RF-21 sin punto no hay botones de foto desactivados sino el texto que lo dice`() {
        show(ready.copy(photos = PhotoUi()))

        compose.onAllNodesWithTag(COLLECTOR_PHOTO_TAG).fetchSemanticsNodes().size shouldBe 0
        compose.onAllNodesWithTag(COLLECTOR_GALLERY_TAG).fetchSemanticsNodes().size shouldBe 0
        compose.onNodeWithTag(COLLECTOR_PHOTO_DESTINATION_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S060 RF-22 con varios avisos de base y de inclinacion solo se dibuja una alerta`() {
        show(
            ready.copy(
                baseShiftName = "Vértice 4",
                baseShiftBaseChanged = true,
                baseShiftDatumNotice = "Vértice 4",
                tiltGuide = TiltGuide.ALIGNMENT,
                tiltMode = es.topophone.core.model.point.TiltMode.APP,
            ),
        )

        val alerts = listOf(BASE_SHIFT_DATUM_NOTICE_TAG, BASE_SHIFT_BASE_CHANGED_TAG, TILT_GUIDE_TAG)
        alerts.count { compose.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() } shouldBe 1
        // El aviso fijo de que el base shift está activo, con su «Desactivar», no es una alerta y se conserva (S020/RF-11).
        compose.onNodeWithTag(BASE_SHIFT_BANNER_TAG).performScrollTo().assertIsDisplayed()
    }
}
