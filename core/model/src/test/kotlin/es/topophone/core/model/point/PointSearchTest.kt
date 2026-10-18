package es.topophone.core.model.point

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Búsqueda, marcas y resumen de la lista de objetos (S011/RF-1 a RF-3). */
class PointSearchTest {

    private fun summary(name: String, description: String = "") = PointSummary(
        id = "id-$name",
        name = name,
        description = description,
        origin = PointOrigin.MEASURED,
        latitudeDeg = 40.0,
        longitudeDeg = -3.7,
        hasHeight = true,
        outOfTolerance = false,
        edited = false,
        hidden = false,
        createdAtMs = 0,
    )

    private val measurement = Measurement(
        antenna = GeodeticPosition(40.41668, -3.70371, 701.357),
        poleHeightM = 1.8,
        quality = PointQuality("FLOAT", 24, 0.6, 1.0, 0.138, 0.21, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
        rawNmea = "",
        outOfTolerance = setOf(PrecisionFailure.NOT_FIX),
        measuredFromMs = 1_000,
        measuredToMs = 1_000,
        coordinates = PointCoordinates(GeodeticPosition(40.41668, -3.70371, 699.423), 440_297.988, 4_474_244.001, 648.311),
    )

    @Test
    fun `S011 RF-2 se busca por nombre o descripcion sin distinguir mayusculas ni acentos`() {
        PointSearch.matches(summary("P12", "Pozo de registro"), "pozo") shouldBe true
        PointSearch.matches(summary("Camión", ""), "camion") shouldBe true
        PointSearch.matches(summary("Peña", ""), "PENA") shouldBe true
        PointSearch.matches(summary("p12", ""), " P1 ") shouldBe true
        PointSearch.matches(summary("P12", "arqueta"), "farola") shouldBe false
    }

    @Test
    fun `S011 RF-2 una busqueda vacia los muestra todos`() {
        PointSearch.matches(summary("P12"), "") shouldBe true
        PointSearch.matches(summary("P12"), "   ") shouldBe true
    }

    @Test
    fun `S011 RF-1 y RF-3 el resumen lleva el origen y las marcas de fuera de tolerancia, editado y sin altura`() {
        val point = Point.measured("id-4", "p1", "4", "farola", measurement, createdAtMs = 2_000)

        point.summary() shouldBe PointSummary(
            id = "id-4",
            name = "4",
            description = "farola",
            origin = PointOrigin.MEASURED,
            latitudeDeg = 40.41668,
            longitudeDeg = -3.70371,
            hasHeight = true,
            outOfTolerance = true,
            edited = false,
            hidden = false,
            createdAtMs = 2_000,
        )
        val flat = point.copy(
            origin = PointOrigin.MANUAL,
            measurement = null,
            coordinates = PointCoordinates(GeodeticPosition(40.0, -3.0, null), 1.0, 2.0, null),
        )
        flat.summary().hasHeight shouldBe false
        flat.summary().outOfTolerance shouldBe false
        point.copy(edited = true).summary().edited shouldBe true
    }
}
