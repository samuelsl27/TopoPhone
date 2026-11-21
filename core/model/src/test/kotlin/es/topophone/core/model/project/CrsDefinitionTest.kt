package es.topophone.core.model.project

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** El texto de la definición de un SC de la biblioteca que se guarda en el proyecto (S034/RF-13). */
class CrsDefinitionTest {
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
        parameters = mapOf("latitudeOfFalseOriginDeg" to 46.5, "eastingAtFalseOrigin" to 700000.0, "standardParallel1Deg" to 49.0),
        unitMetres = 1.0,
        area = GeoBox(41.15, -9.86, 51.56, 10.38),
        frame = "ETRS89",
        transformationName = "RGF93 v1 to ETRS89 (1)",
        transformationAccuracyM = 0.1,
        transformationArea = null,
        transformationSteps = "null",
    )

    @Test
    fun `S034 RF-13 la definicion vuelve igual de su texto, de una sola linea`() {
        val text = definition.toText()

        text.contains('\n') shouldBe false
        CrsDefinition.parse(text) shouldBe definition
        text.length shouldBe text.length.coerceAtMost(600)
    }

    @Test
    fun `S034 RF-13 los numeros no se redondean al guardarlos`() {
        val awkward = definition.copy(primeMeridianDeg = 2.5969213 * 0.9, parameters = mapOf("scaleFactor" to 0.9996012717))

        CrsDefinition.parse(awkward.toText()) shouldBe awkward
    }

    @Test
    fun `S034 RF-13 una esfera, una precision desconocida y un recuadro que cruza el antimeridiano se conservan`() {
        val odd = definition.copy(
            ellipsoidInverseFlattening = 0.0,
            transformationAccuracyM = null,
            area = GeoBox(-20.0, 170.0, -10.0, -170.0),
            transformationArea = GeoBox(-20.0, 170.0, -10.0, -170.0),
        )

        val back = CrsDefinition.parse(odd.toText())

        back shouldBe odd
        back.area!!.contains(-15.0, 175.0) shouldBe true
        back.area!!.contains(-15.0, 0.0) shouldBe false
    }

    @Test
    fun `S034 RF-13 los nombres con tabuladores o saltos de linea no rompen el texto`() {
        val messy = definition.copy(name = "Un\tnombre\ncon cosas", transformationName = "otro\r\nnombre")

        CrsDefinition.parse(messy.toText()).name shouldBe "Un nombre con cosas"
    }

    @Test
    fun `S034 RF-13 los nombres de las rejillas salen de los pasos`() {
        definition.copy(transformationSteps = "G;grid=es_ign_SPED2ETV2.tif;inv=0").gridNames shouldBe listOf("es_ign_SPED2ETV2.tif")
        definition.gridNames shouldBe emptyList()
    }

    @Test
    fun `S034 RF-13 un texto desconocido o mal formado se rechaza`() {
        CrsDefinition.parseOrNull("").shouldBeNull()
        CrsDefinition.parseOrNull("v2\t1").shouldBeNull()
        CrsDefinition.parseOrNull(definition.toText().replace("2154", "dos")).shouldBeNull()
        assertThrows<IllegalArgumentException> { CrsDefinition.parse("v1\ttrozo") }
    }
}
