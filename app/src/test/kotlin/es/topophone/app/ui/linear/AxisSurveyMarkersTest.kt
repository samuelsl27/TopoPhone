package es.topophone.app.ui.linear

import es.topophone.core.model.survey.linear.LinearSurvey
import es.topophone.core.model.survey.linear.LinearSurveyKind
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

/** La línea de la toma de un eje en el mapa (S052/RF-3). */
class AxisSurveyMarkersTest {
    private val survey = LinearSurvey("t1", "p1", LinearSurveyKind.AXIS, null, "", createdAtMs = 1)

    private fun row(i: Int, included: Boolean = true) =
        AxisSurveyRow("id$i", "T$i", 440_000.0, 4_474_000.0, null, 40.0 + i / 1000.0, -3.7 - i / 1000.0, included)

    private fun state(rows: List<AxisSurveyRow>, withSurvey: Boolean = true) =
        AxisSurveyUiState(loading = false, survey = survey.takeIf { withSurvey }, rows = rows)

    @Test
    fun `S052 RF-3 sin panel abierto o sin toma o sin puntos no se dibuja nada`() {
        AxisSurveyMarkers.of(null).shouldBeNull()
        AxisSurveyMarkers.of(state(emptyList(), withSurvey = false)).shouldBeNull()
        AxisSurveyMarkers.of(state(emptyList())).shouldBeNull()
        AxisSurveyMarkers.of(state(listOf(row(1, included = false)))).shouldBeNull()
    }

    @Test
    fun `S052 RF-3 los puntos incluidos se unen en el orden de medida sin guia de replanteo`() {
        val marker = AxisSurveyMarkers.of(state(listOf(row(1), row(2), row(3)))).shouldNotBeNull()

        marker.chosen.map { Math.round((it.latitudeDeg - 40.0) * 1000) } shouldBe listOf(1L, 2L, 3L)
        marker.chosen.map { Math.round((it.longitudeDeg + 3.7) * -1000) } shouldBe listOf(1L, 2L, 3L)
        marker.design.shouldBeNull()
        marker.tip.shouldBeNull()
        marker.line shouldBe false
    }

    @Test
    fun `S052 RF-4 un punto quitado de la toma deja de estar en la linea`() {
        val marker = AxisSurveyMarkers.of(state(listOf(row(1), row(2), row(3, included = false)))).shouldNotBeNull()

        marker.chosen.size shouldBe 2
    }
}
