package es.topophone.core.formats.crs

import es.topophone.core.formats.gpkg.GpkgCrs
import es.topophone.core.formats.gpkg.GpkgCrsCatalog
import es.topophone.core.formats.gpkg.GpkgSrsDefinitions
import es.topophone.core.formats.shp.ShpCrsCatalog
import es.topophone.core.formats.shp.export.ShpPrjDefinitions
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.CustomBuildResult
import es.topophone.core.geodesy.catalog.CustomCrsBuilder
import es.topophone.core.geodesy.catalog.CustomCrsDraft
import es.topophone.core.geodesy.catalog.CustomCrsWkt
import es.topophone.core.geodesy.catalog.CustomTransformation
import es.topophone.core.geodesy.catalog.GridKind
import es.topophone.core.geodesy.catalog.HelmertKind
import es.topophone.core.geodesy.catalog.HelmertValues
import es.topophone.core.geodesy.catalog.RotationConvention
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.isCustom
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Un proyecto con un SC personalizado declara su SC con un WKT1 generado en el GeoPackage y en el `.prj` (S035/RF-23). */
class CustomCrsFormatsTest {
    private val catalog = CrsCatalog.bundled

    private val base = CustomCrsDraft(
        name = "Obra norte",
        semiMajorAxisM = 6378388.0,
        inverseFlattening = 297.0,
        method = "tm",
        parameters = mapOf(
            "latitudeOfOriginDeg" to 0.0,
            "longitudeOfOriginDeg" to -3.0,
            "scaleFactor" to 0.9996,
            "falseEasting" to 500000.0,
            "falseNorthing" to 0.0,
        ),
        transformation = CustomTransformation.Helmert(
            HelmertKind.TRANSLATIONS_3,
            RotationConvention.POSITION_VECTOR,
            HelmertValues(-87.0, -98.0, -121.0),
        ),
    )

    private fun project(draft: CustomCrsDraft, corrections: CorrectionsDatum = CorrectionsDatum.ETRS89): LibraryCrs {
        val definition = when (val r = CustomCrsBuilder.build(draft, catalog) { true }) {
            is CustomBuildResult.Ready -> r.definition
            is CustomBuildResult.NeedsGrids -> r.definition
            is CustomBuildResult.Invalid -> error("${r.errors}")
        }
        return LibraryCrs(definition, corrections)
    }

    @Test
    fun `S035 RF-23 el GeoPackage de un SC personalizado lleva su WKT1 sin autoridad`() {
        val crs = project(base)

        val srs = GpkgSrsDefinitions.of(crs)

        srs.isCustom shouldBe true
        srs.srsId shouldBe GpkgSrsDefinitions.CUSTOM_SRS_ID
        srs.name shouldBe "Obra norte"
        srs.organization shouldBe "NONE"
        srs.definition shouldBe CustomCrsWkt.wkt1(crs.definition)
        srs.definition shouldStartWith "PROJCS[\"Obra norte\",GEOGCS["
        srs.definition shouldContain "TOWGS84[-87.0,-98.0,-121.0,0.0,0.0,0.0,0.0]"
        GpkgSrsDefinitions.isExpressible(crs) shouldBe true
    }

    @Test
    fun `S035 RF-23 el Shapefile de un SC personalizado lleva su WKT1 en el prj`() {
        val crs = project(base)

        ShpPrjDefinitions().of(crs) shouldBe CustomCrsWkt.wkt1(crs.definition)
    }

    @Test
    fun `S035 RF-23 con una rejilla la definicion no se escribe y las coordenadas se exportan igualmente`() {
        val grid = catalog.grids.values.first { it.kind == GridKind.HORIZONTAL }.name
        val crs = project(base.copy(transformation = CustomTransformation.Grid(grid)))

        GpkgSrsDefinitions.of(crs).definition shouldBe "undefined"
        GpkgSrsDefinitions.isExpressible(crs) shouldBe false
        ShpPrjDefinitions().of(crs).shouldBeNull()
    }

    @Test
    fun `S035 RF-23 las geograficas de los puntos siguen siendo las de las correcciones`() {
        GpkgSrsDefinitions.geographicOf(project(base)).organizationCode shouldBe 4258
        GpkgSrsDefinitions.geographicOf(project(base, CorrectionsDatum.ITRF_WGS84)).organizationCode shouldBe 4326
        ShpPrjDefinitions().geographicOf(project(base)).shouldNotBeNull() shouldContain "GCS_ETRS_1989"
    }

    @Test
    fun `S035 RF-23 un SC de la biblioteca no cambia`() {
        val definition = (catalog.build(2154, CorrectionsDatum.ETRS89, null) as CrsBuildResult.Ready).definition

        definition.isCustom shouldBe false
        GpkgSrsDefinitions.of(LibraryCrs(definition, CorrectionsDatum.ETRS89)).organizationCode shouldBe 2154
    }

    private val own get() = project(base).definition

    @Test
    fun `S035 RF-24 el WKT que escribe la app se reconoce como el SC del proyecto`() {
        val wkt = CustomCrsWkt.wkt1(own)

        CustomCrsRecognition.sameAs(wkt, own) shouldBe true
    }

    @Test
    fun `S035 RF-24 un WKT con otro meridiano, otra escala, otra unidad u otro elipsoide no es el SC del proyecto`() {
        val other = listOf(
            own.copy(parameters = own.parameters + ("longitudeOfOriginDeg" to -2.999)),
            own.copy(parameters = own.parameters + ("scaleFactor" to 1.0)),
            own.copy(unitMetres = 0.3048),
            own.copy(ellipsoidSemiMajorM = 6378137.0, ellipsoidInverseFlattening = 298.257222101),
            own.copy(
                method = "lcc1",
                parameters = mapOf(
                    "latitudeOfOriginDeg" to 40.0,
                    "longitudeOfOriginDeg" to -3.0,
                    "scaleFactor" to 1.0,
                    "falseEasting" to 0.0,
                    "falseNorthing" to 0.0,
                ),
            ),
        )

        other.forEach { CustomCrsRecognition.sameAs(CustomCrsWkt.wkt1(it), own) shouldBe false }
    }

    @Test
    fun `S035 RF-24 un texto que no es un WKT no es el SC del proyecto`() {
        CustomCrsRecognition.sameAs(null, own) shouldBe false
        CustomCrsRecognition.sameAs("undefined", own) shouldBe false
        CustomCrsRecognition.sameAs("basura", own) shouldBe false
    }

    @Test
    fun `S035 RF-24 un GeoPackage exportado desde el proyecto llega con su WKT y se reconoce`() {
        val srs = GpkgSrsDefinitions.of(LibraryCrs(own, CorrectionsDatum.ETRS89))

        val crs = GpkgCrsCatalog.resolve(srs.srsId, srs.organization, srs.organizationCode, srs.definition)

        val unavailable = crs.shouldBeInstanceOf<GpkgCrs.Unavailable>()
        CustomCrsRecognition.sameAs(unavailable.wkt, own) shouldBe true
    }

    @Test
    fun `S035 RF-24 un srs_id sin autoridad y sin definicion o de otro rango sigue sin sistema`() {
        GpkgCrsCatalog.resolve(100_000, "NONE", 100_000, "undefined") shouldBe GpkgCrs.Undeclared
        GpkgCrsCatalog.resolve(100_000, "NONE", 100_000, null) shouldBe GpkgCrs.Undeclared
        GpkgCrsCatalog.resolve(5, "NONE", 5, CustomCrsWkt.wkt1(own)) shouldBe GpkgCrs.Undeclared
        GpkgCrsCatalog.resolve(0, "NONE", 0, "undefined") shouldBe GpkgCrs.Undeclared
    }

    @Test
    fun `S035 RF-24 un Shapefile exportado desde el proyecto llega con su prj y se reconoce`() {
        val prj = ShpPrjDefinitions().of(LibraryCrs(own, CorrectionsDatum.ETRS89))

        val crs = ShpCrsCatalog.resolve(prj)

        val unavailable = crs.shouldBeInstanceOf<GpkgCrs.Unavailable>()
        CustomCrsRecognition.sameAs(unavailable.wkt, own) shouldBe true
    }

    @Test
    fun `S035 RF-24 el prj de otro SC personalizado no se reconoce como el del proyecto`() {
        val prj = ShpPrjDefinitions().of(project(base.copy(name = "Otra", parameters = base.parameters + ("scaleFactor" to 0.9999))))

        val unavailable = ShpCrsCatalog.resolve(prj).shouldBeInstanceOf<GpkgCrs.Unavailable>()

        CustomCrsRecognition.sameAs(unavailable.wkt, own) shouldBe false
    }
}
