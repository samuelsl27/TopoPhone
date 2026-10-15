package es.topophone.core.model.project

import es.topophone.core.model.format.LengthFormat
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.util.Locale
import org.junit.jupiter.api.Test

/** Unidades, datum de las correcciones y SC del proyecto (S006/RF-8, RF-9, RF-10; RNF-T9). */
class ProjectUnitsTest {

    private val es = Locale.forLanguageTag("es-ES")
    private val en = Locale.US

    @Test
    fun `S006 RF-9 unidades lineales metro, pie internacional y pie topografico de EE UU con sus factores exactos`() {
        LinearUnit.entries shouldContainExactly listOf(LinearUnit.METRE, LinearUnit.INTERNATIONAL_FOOT, LinearUnit.US_SURVEY_FOOT)
        LinearUnit.METRE.metres shouldBe 1.0
        LinearUnit.INTERNATIONAL_FOOT.metres shouldBe 0.3048
        LinearUnit.US_SURVEY_FOOT.metres shouldBe 1200.0 / 3937.0
    }

    @Test
    fun `S006 RF-9 unidades angulares grados decimales, grados-minutos-segundos y gon`() {
        AngularUnit.entries shouldContainExactly listOf(AngularUnit.DECIMAL_DEGREES, AngularUnit.DMS, AngularUnit.GON)
    }

    @Test
    fun `S006 RF-9 una longitud en metros se pasa a cada unidad`() {
        LinearUnit.METRE.fromMetres(440_290.434) shouldBe 440_290.434
        LinearUnit.INTERNATIONAL_FOOT.fromMetres(304.8) shouldBe (1000.0 plusOrMinus 1e-9)
        // 1 ft EE. UU. = 1200/3937 m: 1200 m son exactamente 3937 ft EE. UU.
        LinearUnit.US_SURVEY_FOOT.fromMetres(1200.0) shouldBe (3937.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S006 RF-9 las longitudes se formatean en la unidad del proyecto con 3 decimales y la configuracion regional`() {
        LengthFormat.inUnit(440_290.434, LinearUnit.METRE, es) shouldBe "440.290,434"
        LengthFormat.inUnit(440_290.434, LinearUnit.METRE, en) shouldBe "440,290.434"
        LengthFormat.inUnit(304.8, LinearUnit.INTERNATIONAL_FOOT, es) shouldBe "1.000,000"
        LengthFormat.inUnit(1200.0, LinearUnit.US_SURVEY_FOOT, es) shouldBe "3.937,000"
        LengthFormat.inUnit(-0.0004, LinearUnit.METRE, es) shouldBe "0,000"
    }

    @Test
    fun `S006 RF-10 datum de las correcciones ETRS89, REGCAN95 o ITRF-WGS84`() {
        CorrectionsDatum.entries shouldContainExactly
            listOf(CorrectionsDatum.ETRS89, CorrectionsDatum.REGCAN95, CorrectionsDatum.ITRF_WGS84)
    }

    @Test
    fun `S006 RF-8 SC del proyecto ETRS89 UTM husos 29, 30 y 31 con altitudes EGM08-REDNAP`() {
        ProjectCrs.entries.map { it.utmZone } shouldContainExactly listOf(29, 30, 31)
        ProjectCrs.entries.forEach {
            it.datum shouldBe GeodeticDatum.ETRS89
            it.heights shouldBe HeightModel.EGM08_REDNAP
        }
        ProjectCrs.ETRS89_UTM30.epsg shouldBe 25830
    }
}
