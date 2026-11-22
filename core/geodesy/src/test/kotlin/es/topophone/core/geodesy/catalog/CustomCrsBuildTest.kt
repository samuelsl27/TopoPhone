package es.topophone.core.geodesy.catalog

import es.topophone.core.geodesy.GeodeticPoint
import es.topophone.core.geodesy.datum.ClasspathGridSource
import es.topophone.core.geodesy.datum.TransformResult
import es.topophone.core.model.project.CUSTOM_EPSG
import es.topophone.core.model.project.CrsDefinition
import es.topophone.core.model.project.CustomHeight
import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** De un borrador válido a la definición que guarda el proyecto: los tres resultados y los tipos de transformación (S035/RF-3, RF-8). */
class CustomCrsBuildTest {
    private val catalog = CrsCatalog.bundled

    private val tm = CustomCrsDraft(
        name = "  Obra norte ",
        datumName = "Datum de la obra",
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

    private fun ready(draft: CustomCrsDraft): CrsDefinition =
        CustomCrsBuilder.build(draft, catalog).shouldBeInstanceOf<CustomBuildResult.Ready>().definition

    private fun steps(draft: CustomCrsDraft) = CrsCatalog.parseSteps(ready(draft).transformationSteps)

    @Test
    fun `S035 RF-3 un SC sin transformacion sale Ready con epsg 0 y el nombre recortado`() {
        val d = ready(tm)

        d.epsg shouldBe CUSTOM_EPSG
        d.name shouldBe "Obra norte"
        d.datumName shouldBe "Datum de la obra"
        d.projected shouldBe true
        d.method shouldBe "tm"
        d.transformationSteps shouldBe "null"
        d.frame shouldBe "ETRS89"
        d.unitMetres shouldBe 1.0
    }

    @Test
    fun `S035 RF-3 sin nombre de datum se llama Personalizado`() {
        ready(tm.copy(datumName = " ")).datumName shouldBe "Personalizado"
    }

    @Test
    fun `S035 RF-4 un SC sin proyeccion es geografico y no lleva parametros`() {
        val d = ready(tm.copy(method = CustomCrsDraft.GEOGRAPHIC, parameters = emptyMap()))

        d.projected shouldBe false
        d.method shouldBe "geo"
        d.parameters shouldBe emptyMap()
        CrsDefinitions.resolve(d, ClasspathGridSource("/shift/")).shouldNotBeNull().projection shouldBe null
    }

    @Test
    fun `S035 RF-5 las unidades en pies se guardan como metros por unidad`() {
        ready(tm.copy(unit = LinearUnit.US_SURVEY_FOOT)).unitMetres shouldBe LinearUnit.US_SURVEY_FOOT.metres
    }

    @Test
    fun `S035 RF-3 un Helmert de 3 parametros escribe solo las traslaciones`() {
        val t = CustomTransformation.Helmert(
            HelmertKind.TRANSLATIONS_3,
            RotationConvention.POSITION_VECTOR,
            HelmertValues(-199.87, 74.79, 246.64, 9.0, 9.0, 9.0, 9.0),
        )

        val step = steps(tm.copy(transformation = t)).single().shouldBeInstanceOf<StepSpec.Helmert>()

        step.translationM shouldBe Triple(-199.87, 74.79, 246.64)
        step.rotationArcSec shouldBe Triple(0.0, 0.0, 0.0)
        step.scalePpm shouldBe 0.0
        step.convention shouldBe "PV"
        step.inverse shouldBe false
        ready(tm.copy(transformation = t)).transformationName shouldBe "Custom Helmert 3-parameter (PV)"
    }

    @Test
    fun `S035 RF-3 un Helmert de 4 parametros usa las traslaciones y la escala y uno de 6 las rotaciones`() {
        val v = HelmertValues(1.0, 2.0, 3.0, 0.1, 0.2, 0.3, 4.0)
        val four =
            steps(
                tm.copy(
                    transformation = CustomTransformation.Helmert(HelmertKind.TRANSLATIONS_SCALE_4, RotationConvention.POSITION_VECTOR, v),
                ),
            )
        val six =
            steps(tm.copy(transformation = CustomTransformation.Helmert(HelmertKind.ROTATIONS_6, RotationConvention.COORDINATE_FRAME, v)))

        four.single().shouldBeInstanceOf<StepSpec.Helmert>().let {
            it.rotationArcSec shouldBe Triple(0.0, 0.0, 0.0)
            it.scalePpm shouldBe 4.0
        }
        six.single().shouldBeInstanceOf<StepSpec.Helmert>().let {
            it.rotationArcSec shouldBe Triple(0.1, 0.2, 0.3)
            it.scalePpm shouldBe 0.0
            it.convention shouldBe "CF"
        }
    }

    @Test
    fun `S035 RF-3 un Helmert de 7 parametros en convencion de marco de coordenadas lleva todo`() {
        val v = HelmertValues(595.48, 121.69, 515.35, -4.115, 2.9383, -0.853, -3.408)
        val d = tm.copy(
            semiMajorAxisM = 6377397.155,
            inverseFlattening = 299.1528128,
            transformation = CustomTransformation.Helmert(HelmertKind.SEVEN, RotationConvention.COORDINATE_FRAME, v),
        )

        val step = steps(d).single().shouldBeInstanceOf<StepSpec.Helmert>()

        step.before.semiMajorAxisM shouldBe 6377397.155
        step.after.inverseFlattening shouldBe 298.257222101
        step.rotationArcSec shouldBe Triple(-4.115, 2.9383, -0.853)
        step.scalePpm shouldBe -3.408
    }

    @Test
    fun `S035 RF-3 con las correcciones en WGS 84 el elipsoide de destino es el de WGS 84`() {
        val d = tm.copy(
            frame = CorrectionFrame.WGS84,
            transformation = CustomTransformation.Helmert(
                HelmertKind.TRANSLATIONS_3,
                RotationConvention.POSITION_VECTOR,
                HelmertValues(1.0, 2.0, 3.0),
            ),
        )

        ready(d).frame shouldBe "WGS84"
        steps(d).single().shouldBeInstanceOf<StepSpec.Helmert>().after.inverseFlattening shouldBe 298.257223563
    }

    @Test
    fun `S035 RF-3 Molodensky-Badekas lleva el punto de rotacion`() {
        val t = CustomTransformation.MolodenskyBadekas(
            RotationConvention.COORDINATE_FRAME,
            HelmertValues(1.0, 2.0, 3.0, 0.1, 0.2, 0.3, 4.0),
            4.0e6,
            1.0e5,
            4.8e6,
        )

        val step = steps(tm.copy(transformation = t)).single().shouldBeInstanceOf<StepSpec.Helmert>()

        step.pivotM shouldBe Triple(4.0e6, 1.0e5, 4.8e6)
        ready(tm.copy(transformation = t)).transformationName shouldBe "Custom Molodensky-Badekas 10-parameter (CF)"
    }

    @Test
    fun `S035 RF-3 una rejilla incluida en la app sale Ready y una descargable sale NeedsGrids hasta que se instala`() {
        val bundled = catalog.grids.values.first { it.kind == GridKind.HORIZONTAL && it.bundled }
        val remote = catalog.grids.values.first { it.kind == GridKind.HORIZONTAL && !it.bundled }

        CustomCrsBuilder.build(tm.copy(transformation = CustomTransformation.Grid(bundled.name)), catalog)
            .shouldBeInstanceOf<CustomBuildResult.Ready>()
        val needs = CustomCrsBuilder.build(tm.copy(transformation = CustomTransformation.Grid(remote.name)), catalog)
            .shouldBeInstanceOf<CustomBuildResult.NeedsGrids>()
        needs.grids.map { it.name } shouldBe listOf(remote.name)
        needs.definition.transformationSteps shouldBe "G;grid=${remote.name};inv=0"

        CustomCrsBuilder.build(tm.copy(transformation = CustomTransformation.Grid(remote.name)), catalog) { it == remote.name }
            .shouldBeInstanceOf<CustomBuildResult.Ready>()
    }

    @Test
    fun `S035 RF-8 un geoide no instalado tambien pide la descarga`() {
        val geoid = catalog.grids.values.first { it.kind == GridKind.GEOID && !it.bundled }

        val needs = CustomCrsBuilder.build(tm.copy(height = CustomHeight.Geoid(geoid.name)), catalog)
            .shouldBeInstanceOf<CustomBuildResult.NeedsGrids>()

        needs.grids.map { it.name } shouldBe listOf(geoid.name)
    }

    @Test
    fun `S035 RF-6 un borrador no valido da Invalid con sus errores`() {
        val result = CustomCrsBuilder.build(tm.copy(name = "", semiMajorAxisM = -1.0), catalog)

        result.shouldBeInstanceOf<CustomBuildResult.Invalid>().errors shouldBe mapOf(
            CustomFields.NAME to CustomError.REQUIRED,
            CustomFields.SEMI_MAJOR_AXIS to CustomError.NOT_POSITIVE,
        )
    }

    @Test
    fun `S035 RF-2 una esfera se guarda con aplanamiento 0 y se calcula sin el catalogo`() {
        val d = ready(tm.copy(semiMajorAxisM = 6371000.0, inverseFlattening = 0.0))

        val resolved = CrsDefinitions.resolve(CrsDefinition.parse(d.toText()), ClasspathGridSource("/shift/")).shouldNotBeNull()

        d.ellipsoidInverseFlattening shouldBe 0.0
        resolved.ellipsoid.inverseFlattening shouldBe Double.POSITIVE_INFINITY
        val p = resolved.projection.shouldNotBeNull().forward(40.0, -3.0).shouldNotBeNull()
        p.eastingM shouldBe (500000.0 plusOrMinus 1e-6)
    }

    @Test
    fun `S035 RF-3 la definicion vuelve de su texto y calcula con la transformacion escrita`() {
        val t = CustomTransformation.Helmert(
            HelmertKind.TRANSLATIONS_3,
            RotationConvention.POSITION_VECTOR,
            HelmertValues(-199.87, 74.79, 246.64),
        )
        val d = ready(tm.copy(semiMajorAxisM = 6378388.0, inverseFlattening = 297.0, transformation = t))

        val resolved = CrsDefinitions.resolve(CrsDefinition.parse(d.toText()), ClasspathGridSource("/shift/")).shouldNotBeNull()
        val moved = (resolved.transformation.forward(GeodeticPoint(40.0, -3.0, 0.0)) as TransformResult.Ok).point

        // Las traslaciones de unos 300 m mueven el punto del orden de una milesima de grado.
        val shift = kotlin.math.hypot(moved.latitudeDeg - 40.0, moved.longitudeDeg + 3.0)
        (shift > 1e-4 && shift < 1e-2) shouldBe true
    }

    @Test
    fun `S035 RF-1 partir de un SC de la biblioteca y no cambiar nada da la misma definicion salvo el nombre y el codigo`() {
        val library = (
            catalog.build(
                2154,
                es.topophone.core.model.project.CorrectionsDatum.ETRS89,
                null,
            ) as CrsBuildResult.Ready
            ).definition

        val custom = ready(CustomCrsDraft.of(library).copy(name = "Mi Lambert"))

        custom shouldBe library.copy(
            epsg = CUSTOM_EPSG,
            name = "Mi Lambert",
            datumCode = 0,
            transformationName = "No datum shift",
            transformationArea = null,
        )
    }
}
