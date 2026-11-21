package es.topophone.core.survey

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.GeoidSource
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.geodesy.datum.GridSource
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.SpainCrs
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Si las cotas de un ráster se pueden pasar al sistema de alturas del proyecto (S057/RF-19, RF-20). */
class SourceHeightsTest {
    private val definition = (
        CrsCatalog.bundled.build(
            2154,
            CorrectionsDatum.ETRS89,
            GeoPoint(48.0, 2.0),
        ) as CrsBuildResult.Ready
        ).definition

    private fun project(geoid: String?) = LibraryCrs(definition, CorrectionsDatum.ETRS89, geoid)

    private fun resources(installed: Set<String>) = object : LibraryResources {
        override val grids = GridSource { null }
        override val generation = 0

        override fun geoid(gridName: String): GeoidSource? = if (gridName in installed) {
            object : GeoidSource {
                override val name = gridName
                override val attribution = ""

                override fun undulationM(latitudeDeg: Double, longitudeDeg: Double) = 0.0
            }
        } else {
            null
        }
    }

    @Test
    fun `S057 RF-19 con el mismo sistema de alturas no hace falta geoide`() {
        SourceHeights.conversion(project(null), HeightSystem.ORTHOMETRIC, HeightSystem.ORTHOMETRIC, resources(emptySet())) shouldBe
            HeightConversion.Same
    }

    @Test
    fun `S057 RF-19 con el geoide del proyecto instalado las cotas se convierten`() {
        SourceHeights.conversion(project("egm"), HeightSystem.ELLIPSOIDAL, HeightSystem.ORTHOMETRIC, resources(setOf("egm"))) shouldBe
            HeightConversion.Available
    }

    @Test
    fun `S057 RF-20 un proyecto sin geoide no puede convertir las cotas ortometricas`() {
        SourceHeights.conversion(project(null), HeightSystem.ELLIPSOIDAL, HeightSystem.ORTHOMETRIC, resources(emptySet())) shouldBe
            HeightConversion.NoProjectGeoid
    }

    @Test
    fun `S057 RF-20 si el geoide del proyecto no esta instalado se dice cual falta`() {
        SourceHeights.conversion(project("egm"), HeightSystem.ORTHOMETRIC, HeightSystem.ELLIPSOIDAL, resources(emptySet())) shouldBe
            HeightConversion.MissingGeoid("egm")
    }

    @Test
    fun `S057 RF-19 un proyecto de Espana siempre convierte con su geoide`() {
        SourceHeights.conversion(SpainCrs.ETRS89_UTM30, HeightSystem.ORTHOMETRIC, HeightSystem.ELLIPSOIDAL, resources(emptySet())) shouldBe
            HeightConversion.Available
    }
}
