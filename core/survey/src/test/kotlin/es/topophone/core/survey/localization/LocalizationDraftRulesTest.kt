package es.topophone.core.survey.localization

import es.topophone.core.model.localization.LocalizationLimits
import es.topophone.core.model.localization.LocalizationPair
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.point.PrecisionFailure
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Formar los pares de un borrador: admisión, límite de 50 y calidad del punto (S039/RF-2 a RF-4, RF-6). */
class LocalizationDraftRulesTest {

    private val known = KnownCoordinates(441100.0, 4472850.0, 650.25)

    private fun measured(
        id: String = "id-1",
        solution: String = "FIX",
        outOfTolerance: Set<PrecisionFailure> = emptySet(),
        easting: Double? = 441000.0,
    ): Point = Point.measured(
        id = id,
        projectId = "p1",
        name = "P-$id",
        description = "",
        measurement = Measurement(
            antenna = GeodeticPosition(40.41668, -3.70371, 701.357),
            poleHeightM = 1.8,
            quality = PointQuality(solution, 24, 0.6, 1.0, 0.009, 0.014, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
            rawNmea = "",
            outOfTolerance = outOfTolerance,
            measuredFromMs = 0,
            measuredToMs = 0,
            coordinates = PointCoordinates(GeodeticPosition(40.41668, -3.70371, 699.4), easting, 4472900.0, 650.0),
        ),
        createdAtMs = 1,
    )

    private fun pair(i: Int) = LocalizationPair("q$i", "other$i", "O$i", 1.0 + i, 2.0, null, 3.0, 4.0, null)

    @Test
    fun `S039 RF-2 un punto medido forma un par con su posicion del SC y las coordenadas conocidas`() {
        val result = LocalizationDraftRules.formPair(emptyList(), measured(), known, "new").shouldBeInstanceOf<PairResult.Added>()

        result.needsConfirmation shouldBe false
        result.pair.pointId shouldBe "id-1"
        result.pair.pointName shouldBe "P-id-1"
        result.pair.scE shouldBe 441000.0
        result.pair.scN shouldBe 4472900.0
        result.pair.scH shouldBe 650.0
        result.pair.knownE shouldBe 441100.0
        result.pair.knownH shouldBe 650.25
        result.pair.confirmed shouldBe true
    }

    @Test
    fun `S039 RF-3 un punto manual, importado o calculado no puede ser punto GNSS`() {
        for (origin in listOf(PointOrigin.MANUAL, PointOrigin.IMPORTED, PointOrigin.COGO, PointOrigin.ON_ALIGNMENT)) {
            val point = measured().copy(origin = origin, measurement = null)

            LocalizationDraftRules.formPair(emptyList(), point, known, "x") shouldBe PairResult.Rejected(PairRejection.NO_MEASUREMENT)
        }
    }

    @Test
    fun `S039 RF-3 un punto que ya esta en el borrador no se anade otra vez`() {
        val existing = listOf(pair(1).copy(pointId = "id-1"))

        LocalizationDraftRules.formPair(existing, measured(), known, "x") shouldBe PairResult.Rejected(PairRejection.ALREADY_IN_DRAFT)
    }

    @Test
    fun `S039 RF-4 el par 51 se rechaza y el 50 se admite`() {
        val forty9 = List(LocalizationLimits.MAX_PAIRS - 1) { pair(it) }
        val fifty = forty9 + pair(99)

        LocalizationDraftRules.formPair(forty9, measured(), known, "x").shouldBeInstanceOf<PairResult.Added>()
        LocalizationDraftRules.formPair(fifty, measured(), known, "x") shouldBe PairResult.Rejected(PairRejection.LIMIT_REACHED)
    }

    @Test
    fun `S039 RF-6 un punto en FLOAT o fuera de precision queda sin confirmar`() {
        val float = LocalizationDraftRules.formPair(emptyList(), measured(solution = "FLOAT"), known, "x")
            .shouldBeInstanceOf<PairResult.Added>()
        val inaccurate = LocalizationDraftRules.formPair(
            emptyList(),
            measured(outOfTolerance = setOf(PrecisionFailure.HORIZONTAL)),
            known,
            "x",
        )
            .shouldBeInstanceOf<PairResult.Added>()

        float.needsConfirmation shouldBe true
        float.pair.confirmed shouldBe false
        float.pair.countsHorizontal shouldBe false
        inaccurate.needsConfirmation shouldBe true
        inaccurate.pair.confirmed shouldBe false
    }

    @Test
    fun `S039 RF-3 un punto medido sin Este y Norte en el SC no forma par`() {
        LocalizationDraftRules.formPair(emptyList(), measured(easting = null), known, "x") shouldBe
            PairResult.Rejected(PairRejection.NO_POSITION)
    }
}
