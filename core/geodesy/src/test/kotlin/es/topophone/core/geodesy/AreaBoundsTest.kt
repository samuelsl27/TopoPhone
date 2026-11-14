package es.topophone.core.geodesy

import es.topophone.core.model.offline.OfflineArea
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AreaBoundsTest {

    /** La caja de la app contiene la exacta y no pasa de un 2 % de más en ningún lado, medido desde [base] (el punto o el rectángulo). */
    private fun shouldWrapTightly(box: GeoBox, exact: GeoBox, base: GeoBox, case: String) {
        listOf(box.south to exact.south, box.west to exact.west).forEach { (mine, exactEdge) -> mine shouldBeLessThanOrEqual exactEdge }
        listOf(box.north to exact.north, box.east to exact.east).forEach { (mine, exactEdge) -> mine shouldBeGreaterThanOrEqual exactEdge }
        val extension = listOf(
            (base.south - box.south) / (base.south - exact.south),
            (base.west - box.west) / (base.west - exact.west),
            (box.north - base.north) / (exact.north - base.north),
            (box.east - base.east) / (exact.east - base.east),
        )
        extension.forEach { ratio -> withClue("$case: $extension") { (ratio in 1.0..1.02) shouldBe true } }
    }

    private fun exact(row: Map<String, String>) = GeoBox(
        row.getValue("south").toDouble(),
        row.getValue("west").toDouble(),
        row.getValue("north").toDouble(),
        row.getValue("east").toDouble(),
    )

    @Test
    fun `S037 RF-7 la caja de un radio contiene todo punto a esa distancia y se pasa menos de un 2 por ciento`() {
        val rows = OfflineFixtures.rows("cajas.csv").filter { it.getValue("kind") == "radius" }
        rows.size shouldBe 12
        rows.forEach { row ->
            val lat = row.getValue("lat").toDouble()
            val lon = row.getValue("lon").toDouble()
            val box = AreaBounds.of(OfflineArea.Radius(lat, lon, row.getValue("radius_m").toDouble()))

            shouldWrapTightly(box, exact(row), GeoBox(lat, lon, lat, lon), row.getValue("case"))
        }
    }

    @Test
    fun `S037 RF-4 la caja de todo el proyecto con margen contiene el margen de cada esquina y se pasa menos de un 2 por ciento`() {
        val rows = OfflineFixtures.rows("cajas.csv").filter { it.getValue("kind") == "project" }
        rows.size shouldBe 12
        rows.forEach { row ->
            val base = GeoBox(
                row.getValue("base_south").toDouble(),
                row.getValue("base_west").toDouble(),
                row.getValue("base_north").toDouble(),
                row.getValue("base_east").toDouble(),
            )
            val area = OfflineArea.ProjectExtent(base.south, base.west, base.north, base.east, row.getValue("margin_m").toDouble())

            shouldWrapTightly(AreaBounds.of(area), exact(row), base, row.getValue("case"))
        }
    }

    @Test
    fun `S037 RF-2 el encuadre visible es la caja tal cual`() {
        AreaBounds.of(OfflineArea.Viewport(40.4160, -3.7050, 40.4180, -3.7020)) shouldBe GeoBox(40.4160, -3.7050, 40.4180, -3.7020)
    }

    @Test
    fun `S037 RF-3 el radio crece la caja con el radio`() {
        val small = AreaBounds.of(OfflineArea.Radius(40.4, -3.7, 500.0))
        val large = AreaBounds.of(OfflineArea.Radius(40.4, -3.7, 5_000.0))

        (large.north - large.south) shouldBeGreaterThanOrEqual (small.north - small.south) * 9.9
        (large.east - large.west) shouldBeGreaterThanOrEqual (small.east - small.west) * 9.9
    }

    @Test
    fun `S037 RF-3 un radio cerca del polo o del antimeridiano no se sale del mundo`() {
        val polar = AreaBounds.of(OfflineArea.Radius(89.9, 179.9, 20_000.0))
        val south = AreaBounds.of(OfflineArea.Radius(-89.9, -179.9, 20_000.0))

        listOf(polar.north, south.south).forEach { (it in -90.0..90.0) shouldBe true }
        listOf(polar.east, south.west).forEach { (it in -180.0..180.0) shouldBe true }
        polar.north shouldBe 90.0
        south.south shouldBe -90.0
    }

    @Test
    fun `S037 RF-6 un proyecto con un solo punto da una caja de un margen por lado`() {
        val point = OfflineArea.ProjectExtent(40.4, -3.7, 40.4, -3.7, 200.0)

        val box = AreaBounds.of(point)

        (box.north - 40.4) shouldBe ((40.4 - box.south) plusOrMinus 1e-12)
        (box.east > -3.7 && box.west < -3.7) shouldBe true
    }
}
