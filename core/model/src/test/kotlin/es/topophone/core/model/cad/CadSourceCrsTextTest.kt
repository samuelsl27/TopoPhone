package es.topophone.core.model.cad

import es.topophone.core.model.project.CrsDefinition
import es.topophone.core.model.project.GeoBox
import es.topophone.core.model.project.GeodeticDatum
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/** El texto con el que se guarda el SC de origen de un dibujo o una superficie (S057/RF-2, RF-27). */
class CadSourceCrsTextTest {
    private val definition = CrsDefinition(
        epsg = 2154,
        name = "RGF93 v1 / Lambert-93",
        projected = true,
        datumCode = 4171,
        datumName = "RGF93 v1",
        ellipsoidSemiMajorM = 6378137.0,
        ellipsoidInverseFlattening = 298.257222101,
        primeMeridianDeg = 0.0,
        method = "lcc2",
        parameters = mapOf("latitudeOfFalseOriginDeg" to 46.5, "eastingAtFalseOrigin" to 700000.0),
        unitMetres = 1.0,
        area = GeoBox(41.15, -9.86, 51.56, 10.38),
        frame = "ETRS89",
        transformationName = "RGF93 v1 to ETRS89 (1)",
        transformationAccuracyM = 0.1,
        transformationArea = null,
        transformationSteps = "null",
    )

    @Test
    fun `S057 RF-27 los valores de antes de S057 se guardan con el mismo texto`() {
        CadSourceCrs.Project.encode() shouldBe "PROJECT"
        CadSourceCrs.Utm(GeodeticDatum.ED50, 30).encode() shouldBe "UTM:ED50:30"
        CadSourceCrs.Geographic(GeodeticDatum.ETRS89).encode() shouldBe "GEO:ETRS89"
    }

    @Test
    fun `S057 RF-2 un SC de la biblioteca se guarda con su definicion completa y vuelve igual`() {
        val source = CadSourceCrs.Library(definition)

        val text = source.encode()

        text shouldStartWith "LIB:"
        text.contains('\n') shouldBe false
        CadSourceCrs.decode(text) shouldBe source
    }

    @Test
    fun `S057 RF-27 los cuatro tipos vuelven iguales de su texto`() {
        listOf(
            CadSourceCrs.Project,
            CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29),
            CadSourceCrs.Geographic(GeodeticDatum.ED50),
            CadSourceCrs.Library(definition),
        ).forEach { CadSourceCrs.decode(it.encode()) shouldBe it }
    }
}
