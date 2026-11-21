package es.topophone.core.model.project

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Los formatos de intercambio con SC todavía solo funcionan con los de España (S034/RF-29). */
class FormatsSupportTest {
    private val definition = CrsDefinition(
        epsg = 2154, name = "n", projected = true, datumCode = 1, datumName = "d", ellipsoidSemiMajorM = 6378137.0,
        ellipsoidInverseFlattening = 298.257222101, primeMeridianDeg = 0.0, method = "lcc2", parameters = emptyMap(), unitMetres = 1.0,
        area = null, frame = "ETRS89", transformationName = "t", transformationAccuracyM = null, transformationArea = null,
        transformationSteps = "null",
    )

    @Test
    fun `S034 RF-29 los SC de Espana admiten los formatos y los de la biblioteca todavia no`() {
        SpainCrs.entries.all { it.supportsFormats } shouldBe true
        LibraryCrs(definition).supportsFormats shouldBe false
        ProjectCrs.ETRS89_UTM30.supportsFormats shouldBe true
    }
}
