package es.topophone.core.model.point

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Lo que cambió en cada versión del historial de un punto (S011/RF-10, RF-11). */
class PointHistoryTest {

    private val measured = PointCoordinates(GeodeticPosition(40.41668, -3.70371, 699.423), 440_297.988, 4_474_244.001, 648.311)
    private val edited = measured.copy(easting = 440_300.0)

    private val v1 = Point.measured(
        id = "id-1",
        projectId = "p1",
        name = "P1",
        description = "arqueta",
        measurement = Measurement(
            antenna = GeodeticPosition(40.41668, -3.70371, 701.357),
            poleHeightM = 1.8,
            quality = PointQuality("FIX", 24, 0.6, 1.0, 0.009, 0.014, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
            rawNmea = "",
            outOfTolerance = emptySet(),
            measuredFromMs = 1_000,
            measuredToMs = 1_000,
            coordinates = measured,
        ),
        createdAtMs = 1_000,
    )
    private val v2 = v1.copy(name = "P1a", description = "pozo")
    private val v3 = v2.copy(measurement = v2.measurement!!.copy(poleHeightM = 2.0))
    private val v4 = v3.copy(coordinates = edited, edited = true)
    private val v5 = v4.copy(coordinates = measured, edited = false)

    private fun entry(at: Long, change: PointChange, previous: Point) =
        PointHistoryEntry("h$at", "id-1", at, change, PointSnapshot.of(previous))

    @Test
    fun `S011 RF-11 cada version dice lo que cambio respecto a la siguiente, del cambio mas reciente al mas antiguo`() {
        val entries = listOf(
            entry(5_000, PointChange.REVERT, v4),
            entry(4_000, PointChange.EDIT, v3),
            entry(3_000, PointChange.POLE, v2),
            entry(2_000, PointChange.EDIT, v1),
        )

        val items = PointHistory.items(entries, current = v5)

        items.map { it.changedAtMs } shouldBe listOf(5_000L, 4_000L, 3_000L, 2_000L)
        items[0].lines shouldBe listOf(HistoryLine.Reverted)
        items[1].lines shouldBe listOf(HistoryLine.Coordinates)
        items[2].lines shouldBe listOf(HistoryLine.Pole(1.8, 2.0))
        items[3].lines shouldBe listOf(HistoryLine.Name("P1", "P1a"), HistoryLine.Description("arqueta", "pozo"))
    }

    @Test
    fun `S011 RF-11 sin cambios el historial esta vacio`() {
        PointHistory.items(emptyList(), current = v1).shouldBeEmpty()
    }

    @Test
    fun `S017b RF-23 los sucesos de foto se mezclan con las versiones del mas reciente al mas antiguo`() {
        val items = PointHistory.items(
            listOf(entry(4_000, PointChange.EDIT, v1)),
            current = v2,
        )
        val events = listOf(
            PhotoEvent(5_000, PhotoAction.DELETED, "P1_a.jpg", 900),
            PhotoEvent(3_000, PhotoAction.ADDED, "P1_a.jpg", 900),
        )

        val timeline = HistoryRecord.timeline(items, events)

        timeline.map { it.atMs } shouldBe listOf(5_000L, 4_000L, 3_000L)
        (timeline[0] as HistoryRecord.Photo).event.action shouldBe PhotoAction.DELETED
        (timeline[1] as HistoryRecord.Version).item.change shouldBe PointChange.EDIT
        (timeline[2] as HistoryRecord.Photo).event.photoName shouldBe "P1_a.jpg"
    }

    @Test
    fun `S017b RF-23 un suceso de foto no es una version ni marca el punto como editado`() {
        PointHistory.items(emptyList(), current = v1).shouldBeEmpty()
        v1.edited shouldBe false
        HistoryRecord.timeline(emptyList(), listOf(PhotoEvent(1, PhotoAction.ADDED, "x.jpg", 0))).size shouldBe 1
    }

    @Test
    fun `S017b RF-26 un resumen sin fotos tiene cero`() {
        PointSummary("a", "P", "", PointOrigin.MEASURED, 0.0, 0.0, true, false, false, false, 0).photoCount shouldBe 0
    }
}
