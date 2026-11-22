package es.topophone.core.geodesy.catalog

import es.topophone.core.geodesy.datum.ClasspathGridSource
import es.topophone.core.geodesy.datum.GridSource
import es.topophone.core.model.project.CrsDefinition
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Las coordenadas de prueba del editor en los dos sentidos, con el cierre y la rejilla que falta (S035/RF-7, RF-8). */
class CrsProbeTest {
    private val catalog = CrsCatalog.bundled
    private val grids = ClasspathGridSource("/shift/")

    private val utm30 = CustomCrsDraft(
        name = "UTM 30 propio",
        semiMajorAxisM = 6378137.0,
        inverseFlattening = 298.257222101,
        method = "tm",
        parameters = mapOf(
            "latitudeOfOriginDeg" to 0.0,
            "longitudeOfOriginDeg" to -3.0,
            "scaleFactor" to 0.9996,
            "falseEasting" to 500000.0,
            "falseNorthing" to 0.0,
        ),
    )

    private fun definition(draft: CustomCrsDraft): CrsDefinition = CustomCrsBuilder.build(draft, catalog, isInstalled = { true }).let {
        when (it) {
            is CustomBuildResult.Ready -> it.definition
            is CustomBuildResult.NeedsGrids -> it.definition
            is CustomBuildResult.Invalid -> error("${it.errors}")
        }
    }

    @Test
    fun `S035 RF-7 la posicion de Madrid da el UTM 30 conocido en los dos sentidos`() {
        val d = definition(utm30)

        val to = CrsProbe.toCrs(d, grids, 40.4, -3.7).shouldBeInstanceOf<ProbeResult.ToCrs>()
        to.first shouldBe (440_598.0788513809 plusOrMinus 0.001) // pyproj, EPSG:4258 a EPSG:25830
        to.second shouldBe (4_472_390.031014141 plusOrMinus 0.001)
        to.closureM shouldBe (0.0 plusOrMinus 0.0001)

        val from = CrsProbe.fromCrs(d, grids, to.first, to.second).shouldBeInstanceOf<ProbeResult.FromCrs>()
        from.latitudeDeg shouldBe (40.4 plusOrMinus 1e-9)
        from.longitudeDeg shouldBe (-3.7 plusOrMinus 1e-9)
        from.closureM shouldBe (0.0 plusOrMinus 0.0001)
    }

    @Test
    fun `S035 RF-7 las coordenadas en pies se muestran en pies`() {
        val feet = definition(utm30.copy(unit = es.topophone.core.model.project.LinearUnit.INTERNATIONAL_FOOT))

        val to = CrsProbe.toCrs(feet, grids, 40.4, -3.7).shouldBeInstanceOf<ProbeResult.ToCrs>()

        // El falso Este se escribe en pies: 500.000 ft, y el resto del Este (-59.401,92 m) tambien sale en pies.
        to.first shouldBe (500_000.0 + (440_598.0788513809 - 500_000.0) / 0.3048 plusOrMinus 0.001)
    }

    @Test
    fun `S035 RF-7 un SC geografico devuelve latitud y longitud en el datum del SC`() {
        val geo = definition(utm30.copy(method = CustomCrsDraft.GEOGRAPHIC, parameters = emptyMap()))

        val to = CrsProbe.toCrs(geo, grids, 40.4, -3.7).shouldBeInstanceOf<ProbeResult.ToCrs>()

        to.first shouldBe (40.4 plusOrMinus 1e-9)
        to.second shouldBe (-3.7 plusOrMinus 1e-9)
        CrsProbe.fromCrs(geo, grids, 40.4, -3.7).shouldBeInstanceOf<ProbeResult.FromCrs>().longitudeDeg shouldBe (-3.7 plusOrMinus 1e-9)
    }

    @Test
    fun `S035 RF-7 con un Helmert la posicion en el datum del SC no es la de las correcciones`() {
        val shifted = definition(
            utm30.copy(
                semiMajorAxisM = 6378388.0,
                inverseFlattening = 297.0,
                transformation = CustomTransformation.Helmert(
                    HelmertKind.TRANSLATIONS_3,
                    RotationConvention.POSITION_VECTOR,
                    HelmertValues(-87.0, -98.0, -121.0),
                ),
            ),
        )

        val to = CrsProbe.toCrs(shifted, grids, 40.4, -3.7).shouldBeInstanceOf<ProbeResult.ToCrs>()

        (to.datumLatitudeDeg != 40.4) shouldBe true
        to.closureM shouldBe (0.0 plusOrMinus 0.0001)
    }

    @Test
    fun `S035 RF-8 con una rejilla que falta la prueba dice cual y no calcula`() {
        val remote = catalog.grids.values.first { it.kind == GridKind.HORIZONTAL && !it.bundled }
        val d = definition(utm30.copy(transformation = CustomTransformation.Grid(remote.name)))
        val noGrids = GridSource { null }

        CrsProbe.toCrs(d, noGrids, 40.4, -3.7) shouldBe ProbeResult.Failed(ProbeFailure.MissingGrid(remote.name))
        CrsProbe.fromCrs(d, noGrids, 440_000.0, 4_470_000.0) shouldBe ProbeResult.Failed(ProbeFailure.MissingGrid(remote.name))
    }

    @Test
    fun `S035 RF-7 una definicion que no se construye da NotComputable`() {
        val broken = definition(utm30).copy(parameters = emptyMap())

        CrsProbe.toCrs(broken, grids, 40.4, -3.7) shouldBe ProbeResult.Failed(ProbeFailure.NotComputable)
    }
}
