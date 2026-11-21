package es.topophone.core.model.project

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** `ProjectCrs` pasa a interfaz con los diez SC de España y los de la biblioteca, sin cambiar nada de los de España (S034/RF-25, RF-13). */
class SpainCrsUnchangedTest {
    private val library = LibraryCrs(
        CrsDefinition(
            epsg = 2154,
            name = "RGF93 v1 / Lambert-93",
            projected = true,
            datumCode = 4171,
            datumName = "RGF93 v1",
            ellipsoidSemiMajorM = 6378137.0,
            ellipsoidInverseFlattening = 298.257222101,
            primeMeridianDeg = 0.0,
            method = "lcc2",
            parameters = emptyMap(),
            unitMetres = 1.0,
            area = GeoBox(41.15, -9.86, 51.56, 10.38),
            frame = "ETRS89",
            transformationName = "n",
            transformationAccuracyM = null,
            transformationArea = null,
            transformationSteps = "null",
        ),
    )

    @Test
    fun `S034 RF-25 los diez SC de Espana conservan nombre, datum, huso y EPSG`() {
        SpainCrs.entries.map { Triple(it.name, it.utmZone, it.epsg) } shouldContainExactly listOf(
            Triple("ETRS89_UTM29", 29, 25829),
            Triple("ETRS89_UTM30", 30, 25830),
            Triple("ETRS89_UTM31", 31, 25831),
            Triple("ETRS89_GEOGRAPHIC", null, 4258),
            Triple("REGCAN95_UTM27", 27, 4082),
            Triple("REGCAN95_UTM28", 28, 4083),
            Triple("REGCAN95_GEOGRAPHIC", null, 4081),
            Triple("ED50_UTM29", 29, 23029),
            Triple("ED50_UTM30", 30, 23030),
            Triple("ED50_UTM31", 31, 23031),
        )
        SpainCrs.ED50_UTM30.heightSystems shouldContainExactly listOf(HeightSystem.ORTHOMETRIC)
        SpainCrs.REGCAN95_UTM28.correctionsDatum shouldBe CorrectionsDatum.REGCAN95
        SpainCrs.ETRS89_UTM30.area shouldBe CrsArea.PENINSULA_BALEARES
    }

    @Test
    fun `S034 RF-25 el companion de ProjectCrs conserva los nombres y funciones de S006 y S008`() {
        ProjectCrs.ETRS89_UTM30 shouldBe SpainCrs.ETRS89_UTM30
        ProjectCrs.entries shouldContainExactly SpainCrs.entries
        ProjectCrs.valueOf("ED50_UTM31") shouldBe SpainCrs.ED50_UTM31
        ProjectCrs.of(GeodeticDatum.REGCAN95, 28) shouldBe SpainCrs.REGCAN95_UTM28
        ProjectCrs.suggested(ProjectCrs.ETRS89_UTM30, 28.1, -15.4) shouldBe SpainCrs.REGCAN95_UTM28
        ProjectCrs.suggested(ProjectCrs.ETRS89_UTM30, 40.4, -3.7) shouldBe SpainCrs.ETRS89_UTM30
        ProjectCrs.ETRS89_UTM30.isLibrary shouldBe false
        ProjectCrs.ETRS89_UTM30.isProjected shouldBe true
        ProjectCrs.ETRS89_GEOGRAPHIC.isProjected shouldBe false
    }

    @Test
    fun `S034 RF-13 un SC de la biblioteca da su EPSG, su nombre de guardado y los datos que usa el resto de la app`() {
        library.epsg shouldBe 2154
        library.name shouldBe "LIBRARY:2154"
        library.isLibrary shouldBe true
        library.isProjected shouldBe true
        library.isUtm shouldBe false
        library.utmZone.shouldBeNull()
        library.heightSystems shouldContainExactly HeightSystem.entries
        library.correctionsDatum shouldBe CorrectionsDatum.ETRS89
        LibraryCrs(library.definition.copy(frame = "WGS84")).correctionsDatum shouldBe CorrectionsDatum.ITRF_WGS84
        (library == LibraryCrs(library.definition)) shouldBe true
    }

    @Test
    fun `S034 RF-13 el ambito de un SC de la biblioteca sale de su definicion y sin ambito vale todo el mundo`() {
        library.containsPosition(48.85, 2.35) shouldBe true
        library.containsPosition(40.4, 30.0) shouldBe false
        LibraryCrs(library.definition.copy(area = null)).containsPosition(-33.0, 151.0) shouldBe true
        SpainCrs.ETRS89_UTM30.containsPosition(40.4, -3.7) shouldBe true
        SpainCrs.ETRS89_UTM30.containsPosition(48.85, 2.35) shouldBe false
    }

    @Test
    fun `S034 RF-25 un SC de la biblioteca como SC por defecto no propone huso de Espana`() {
        ProjectCrs.suggested(library, 40.4, -3.7).shouldBeNull()
    }
}
