package es.topophone.core.geodesy.catalog

import es.topophone.core.model.project.CustomHeight
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** La validación de un SC personalizado campo a campo (S035/RF-2, RF-3, RF-4, RF-5, RF-6). */
class CustomCrsValidationTest {
    private val catalog = CrsCatalog.bundled

    private fun tm(centralMeridian: Double = -3.0) = CustomCrsDraft(
        name = "Obra",
        semiMajorAxisM = 6378137.0,
        inverseFlattening = 298.257222101,
        method = "tm",
        parameters = mapOf(
            "latitudeOfOriginDeg" to 0.0,
            "longitudeOfOriginDeg" to centralMeridian,
            "scaleFactor" to 0.9996,
            "falseEasting" to 500000.0,
            "falseNorthing" to 0.0,
        ),
    )

    private fun errors(draft: CustomCrsDraft) = CustomCrsBuilder.validate(draft, catalog)

    @Test
    fun `S035 RF-6 un SC correcto no tiene errores`() {
        errors(tm()).keys.shouldBeEmpty()
    }

    @Test
    fun `S035 RF-6 el nombre es obligatorio`() {
        errors(tm().copy(name = "  ")) shouldBe mapOf(CustomFields.NAME to CustomError.REQUIRED)
    }

    @Test
    fun `S035 RF-2 el elipsoide pide semieje positivo y aplanamiento 0 o mayor que 1`() {
        errors(tm().copy(semiMajorAxisM = null)) shouldBe mapOf(CustomFields.SEMI_MAJOR_AXIS to CustomError.REQUIRED)
        errors(tm().copy(semiMajorAxisM = -1.0)) shouldBe mapOf(CustomFields.SEMI_MAJOR_AXIS to CustomError.NOT_POSITIVE)
        errors(tm().copy(semiMajorAxisM = Double.NaN)) shouldBe mapOf(CustomFields.SEMI_MAJOR_AXIS to CustomError.NOT_FINITE)
        errors(tm().copy(inverseFlattening = null)) shouldBe mapOf(CustomFields.INVERSE_FLATTENING to CustomError.REQUIRED)
        errors(tm().copy(inverseFlattening = 0.5)) shouldBe mapOf(CustomFields.INVERSE_FLATTENING to CustomError.OUT_OF_RANGE)
        errors(tm().copy(inverseFlattening = -298.0)) shouldBe mapOf(CustomFields.INVERSE_FLATTENING to CustomError.OUT_OF_RANGE)
    }

    @Test
    fun `S035 RF-2 una esfera es un aplanamiento 0 y se acepta`() {
        errors(tm().copy(inverseFlattening = 0.0)).keys.shouldBeEmpty()
    }

    @Test
    fun `S035 RF-6 el meridiano de origen del SC va entre -180 y 180`() {
        errors(tm().copy(primeMeridianDeg = 181.0)) shouldBe mapOf(CustomFields.PRIME_MERIDIAN to CustomError.OUT_OF_RANGE)
        errors(tm().copy(primeMeridianDeg = -3.7)).keys.shouldBeEmpty() // Madrid
    }

    @Test
    fun `S035 RF-4 la Transversa de Mercator valida sus cinco parametros`() {
        val e = errors(
            tm().copy(
                parameters = mapOf(
                    "latitudeOfOriginDeg" to 91.0,
                    "longitudeOfOriginDeg" to 200.0,
                    "scaleFactor" to 0.0,
                    "falseEasting" to Double.POSITIVE_INFINITY,
                    "falseNorthing" to null,
                ),
            ),
        )

        e shouldBe mapOf(
            CustomFields.parameter("latitudeOfOriginDeg") to CustomError.OUT_OF_RANGE,
            CustomFields.parameter("longitudeOfOriginDeg") to CustomError.OUT_OF_RANGE,
            CustomFields.parameter("scaleFactor") to CustomError.NOT_POSITIVE,
            CustomFields.parameter("falseEasting") to CustomError.NOT_FINITE,
            CustomFields.parameter("falseNorthing") to CustomError.REQUIRED,
        )
    }

    @Test
    fun `S035 RF-4 LCC 2SP rechaza dos paralelos opuestos y marca el que falla`() {
        val lcc2 = CustomCrsDraft(
            name = "L",
            semiMajorAxisM = 6378137.0,
            inverseFlattening = 298.257222101,
            method = "lcc2",
            parameters = mapOf(
                "latitudeOfFalseOriginDeg" to 46.5,
                "longitudeOfFalseOriginDeg" to 3.0,
                "standardParallel1Deg" to 30.0,
                "standardParallel2Deg" to -30.0,
                "eastingAtFalseOrigin" to 700000.0,
                "northingAtFalseOrigin" to 6600000.0,
            ),
        )

        errors(lcc2) shouldBe mapOf(CustomFields.parameter("standardParallel2Deg") to CustomError.OPPOSITE_PARALLELS)
        errors(lcc2.copy(parameters = lcc2.parameters + ("standardParallel1Deg" to 91.0))) shouldContainKey
            CustomFields.parameter("standardParallel1Deg")
    }

    @Test
    fun `S035 RF-4 Oblique Mercator A y B y LCC 1SP validan sus parametros`() {
        val om = CustomCrsDraft(
            name = "O",
            semiMajorAxisM = 6378137.0,
            inverseFlattening = 298.257222101,
            method = "omerca",
            parameters = mapOf(
                "latitudeOfCentreDeg" to 4.0,
                "longitudeOfCentreDeg" to 115.0,
                "azimuthDeg" to 53.3158,
                "rectifiedGridAngleDeg" to 53.1301,
                "scaleFactor" to 0.99984,
                "falseEasting" to 590476.87,
                "falseNorthing" to 442857.65,
            ),
        )
        errors(om).keys.shouldBeEmpty()
        errors(om.copy(parameters = om.parameters + ("azimuthDeg" to 400.0))) shouldBe
            mapOf(CustomFields.parameter("azimuthDeg") to CustomError.OUT_OF_RANGE)
        errors(om.copy(method = "omercb", parameters = om.parameters - "falseEasting" - "falseNorthing")) shouldBe
            mapOf(
                CustomFields.parameter("eastingAtCentre") to CustomError.REQUIRED,
                CustomFields.parameter("northingAtCentre") to CustomError.REQUIRED,
            )
    }

    @Test
    fun `S035 RF-4 sin proyeccion no hay parametros que validar`() {
        errors(tm().copy(method = CustomCrsDraft.GEOGRAPHIC, parameters = emptyMap())).keys.shouldBeEmpty()
    }

    @Test
    fun `S035 RF-4 un metodo desconocido se rechaza`() {
        errors(tm().copy(method = "xyz")) shouldBe mapOf(CustomFields.METHOD to CustomError.UNSUPPORTED_METHOD)
    }

    @Test
    fun `S035 RF-3 el Helmert pide valores finitos y rangos razonables`() {
        val values = HelmertValues(tx = Double.NaN, rx = 4000.0, scalePpm = 2e6)
        val h = CustomTransformation.Helmert(HelmertKind.SEVEN, RotationConvention.POSITION_VECTOR, values)

        errors(tm().copy(transformation = h)) shouldBe mapOf(
            CustomFields.helmert("tx") to CustomError.NOT_FINITE,
            CustomFields.helmert("rx") to CustomError.OUT_OF_RANGE,
            CustomFields.helmert("scalePpm") to CustomError.OUT_OF_RANGE,
        )
    }

    @Test
    fun `S035 RF-3 un Helmert de 3 parametros ignora los valores que no usa`() {
        val values = HelmertValues(1.0, 2.0, 3.0, rx = Double.NaN)
        val h = CustomTransformation.Helmert(HelmertKind.TRANSLATIONS_3, RotationConvention.POSITION_VECTOR, values)

        errors(tm().copy(transformation = h)).keys.shouldBeEmpty()
    }

    @Test
    fun `S035 RF-3 Molodensky-Badekas pide su punto de rotacion finito`() {
        val mb = CustomTransformation.MolodenskyBadekas(
            RotationConvention.COORDINATE_FRAME,
            HelmertValues(1.0, 2.0, 3.0),
            pivotX = Double.NaN,
            pivotY = 4.0e6,
            pivotZ = 4.0e6,
        )

        errors(tm().copy(transformation = mb)) shouldBe mapOf(CustomFields.pivot("x") to CustomError.NOT_FINITE)
    }

    @Test
    fun `S035 RF-3 una rejilla tiene que estar en el catalogo de rejillas horizontales`() {
        val known = catalog.grids.values.first { it.kind == GridKind.HORIZONTAL }.name
        val geoid = catalog.grids.values.first { it.kind == GridKind.GEOID }.name

        errors(tm().copy(transformation = CustomTransformation.Grid(known))).keys.shouldBeEmpty()
        val unknown = mapOf(CustomFields.GRID to CustomError.UNKNOWN_GRID)
        errors(tm().copy(transformation = CustomTransformation.Grid("no_existe.tif"))) shouldBe unknown
        errors(tm().copy(transformation = CustomTransformation.Grid(geoid))) shouldBe unknown
    }

    @Test
    fun `S035 RF-3 una cadena conservada de la biblioteca tiene que ser legible`() {
        errors(tm().copy(transformation = CustomTransformation.Kept("null"))).keys.shouldBeEmpty()
        errors(tm().copy(transformation = CustomTransformation.Kept("basura;;"))) shouldBe
            mapOf(CustomFields.TRANSFORMATION to CustomError.INVALID)
    }

    @Test
    fun `S035 RF-5 la altura valida su geoide y su ondulacion constante`() {
        val geoid = catalog.grids.values.first { it.kind == GridKind.GEOID }.name

        errors(tm().copy(height = CustomHeight.Geoid(geoid))).keys.shouldBeEmpty()
        errors(tm().copy(height = CustomHeight.Geoid("no_existe.tif"))) shouldBe mapOf(CustomFields.HEIGHT to CustomError.UNKNOWN_GRID)
        errors(tm().copy(height = CustomHeight.ConstantGeoid(48.5))).keys.shouldBeEmpty()
        errors(tm().copy(height = CustomHeight.ConstantGeoid(Double.NaN))) shouldBe mapOf(CustomFields.HEIGHT to CustomError.NOT_FINITE)
        errors(tm().copy(height = CustomHeight.ConstantGeoid(900.0))) shouldBe mapOf(CustomFields.HEIGHT to CustomError.OUT_OF_RANGE)
    }

    @Test
    fun `S035 RF-6 la precision declarada no puede ser negativa`() {
        errors(tm().copy(transformationAccuracyM = -1.0)) shouldBe mapOf(CustomFields.ACCURACY to CustomError.NOT_POSITIVE)
        errors(tm().copy(transformationAccuracyM = 0.0)).keys.shouldBeEmpty()
    }
}
