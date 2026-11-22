package es.topophone.core.model.project

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** El modelo de un SC personalizado y el texto del SC del proyecto para el historial (S035/RF-5, RF-11, RF-20). */
class CustomCrsModelTest {
    private val definition = CrsDefinition(
        epsg = CUSTOM_EPSG,
        name = "Obra norte",
        projected = true,
        datumCode = 0,
        datumName = "Personalizado",
        ellipsoidSemiMajorM = 6378137.0,
        ellipsoidInverseFlattening = 298.257222101,
        primeMeridianDeg = 0.0,
        method = "tm",
        parameters = mapOf(
            "latitudeOfOriginDeg" to 0.0,
            "longitudeOfOriginDeg" to -3.123456789012345,
            "scaleFactor" to 0.9996,
            "falseEasting" to 500000.0,
            "falseNorthing" to 0.0,
        ),
        unitMetres = 1.0,
        area = null,
        frame = "ETRS89",
        transformationName = "Helmert",
        transformationAccuracyM = null,
        transformationArea = null,
        transformationSteps = "null|G;grid=es_ign_SPED2ETV2.tif;inv=0",
    )

    @Test
    fun `S035 RF-5 un SC con epsg 0 es personalizado y uno de la biblioteca no`() {
        definition.isCustom shouldBe true
        definition.copy(epsg = 25830).isCustom shouldBe false
    }

    @Test
    fun `S035 RF-5 geoidGrid y geoidConstantM no pueden estar a la vez`() {
        assertThrows<IllegalArgumentException> { LibraryCrs(definition, CorrectionsDatum.ETRS89, "grid.tif", 1.0) }
        LibraryCrs(definition, CorrectionsDatum.ETRS89, null, 3.25).geoidConstantM shouldBe 3.25
        LibraryCrs(definition, CorrectionsDatum.ETRS89, "grid.tif").geoidConstantM.shouldBeNull()
    }

    @Test
    fun `S035 RF-11 elegir un SC de Mis SC da un SC de proyecto con su copia de la definicion y su altura`() {
        val mine = CustomCrs("c1", "Obra norte 2", definition, CustomHeight.ConstantGeoid(48.5))

        val crs = mine.toProjectCrs(CorrectionsDatum.ETRS89)

        crs.definition.name shouldBe "Obra norte 2"
        crs.geoidConstantM shouldBe 48.5
        crs.geoidGrid.shouldBeNull()
        CustomCrs("c2", "X", definition, CustomHeight.Geoid("g.tif")).toProjectCrs(CorrectionsDatum.ETRS89).geoidGrid shouldBe "g.tif"
    }

    @Test
    fun `S035 RF-20 el SC del proyecto vuelve igual de su texto, con los pasos de la transformacion con barras`() {
        val crs = LibraryCrs(definition, CorrectionsDatum.ITRF_WGS84, null, 12.345678901234567)

        ProjectCrsText.decodeOrNull(ProjectCrsText.encode(crs)) shouldBe crs
        ProjectCrsText.decodeOrNull(ProjectCrsText.encode(LibraryCrs(definition, CorrectionsDatum.ETRS89, "g.tif"))) shouldBe
            LibraryCrs(definition, CorrectionsDatum.ETRS89, "g.tif")
    }

    @Test
    fun `S035 RF-20 los diez SC de Espana vuelven por su nombre y un texto raro da null`() {
        SpainCrs.entries.forEach { ProjectCrsText.decodeOrNull(ProjectCrsText.encode(it)) shouldBe it }
        ProjectCrsText.decodeOrNull("SPAIN|NO_EXISTE").shouldBeNull()
        ProjectCrsText.decodeOrNull("basura").shouldBeNull()
        ProjectCrsText.decodeOrNull("LIB|ETRS89|||v9").shouldBeNull()
    }
}
