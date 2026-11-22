package es.topophone.core.formats.crs

import es.topophone.core.model.project.CUSTOM_EPSG
import es.topophone.core.model.project.CrsDefinition
import es.topophone.core.model.project.CustomCrs
import es.topophone.core.model.project.CustomHeight
import es.topophone.core.model.project.GeoBox
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.Locale
import kotlin.random.Random
import org.junit.jupiter.api.Test

/** El JSON de un SC personalizado: ida y vuelta exacta de la definición (S035/RF-12, RF-13) y los motivos de rechazo (RF-14). */
class CustomCrsJsonTest {
    private val definition = CrsDefinition(
        epsg = CUSTOM_EPSG,
        name = "Obra norte",
        projected = true,
        datumCode = 0,
        datumName = "Datum de la obra",
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
        transformationName = "Custom Helmert 7-parameter (PV)",
        transformationAccuracyM = null,
        transformationArea = null,
        transformationSteps = "H;A=6378388.0,297.0;B=6378137.0,298.257222101;inv=0;conv=PV;p=-87.0,-98.0,-121.0,0.0,0.0,0.0,0.0",
    )
    private val crs = CustomCrs("", "Obra norte", definition, CustomHeight.Ellipsoidal)

    private fun roundTrip(c: CustomCrs): CustomCrs =
        CustomCrsJson.read(CustomCrsJson.write(c)).shouldBeInstanceOf<CustomCrsReadResult.Ok>().crs

    @Test
    fun `S035 RF-12 un SC vuelve igual de su JSON`() {
        roundTrip(crs) shouldBe crs
    }

    @Test
    fun `S035 RF-12 los tres tipos de altura y el ambito vuelven iguales`() {
        listOf(
            CustomHeight.Ellipsoidal,
            CustomHeight.Geoid("es_ign_egm08-rednap.tif"),
            CustomHeight.ConstantGeoid(48.123456789),
        ).forEach { h ->
            val c = crs.copy(
                height = h,
                definition = definition.copy(area = GeoBox(35.5, -9.5, 44.25, 4.75), transformationAccuracyM = 0.25),
            )
            roundTrip(c) shouldBe c
        }
    }

    @Test
    fun `S035 RF-12 una esfera, un SC geografico y una cadena con barras vuelven iguales`() {
        val sphere = crs.copy(definition = definition.copy(ellipsoidSemiMajorM = 6371000.0, ellipsoidInverseFlattening = 0.0))
        val geo = crs.copy(definition = definition.copy(projected = false, method = "geo", parameters = emptyMap()))
        val chain = crs.copy(definition = definition.copy(transformationSteps = "null|" + definition.transformationSteps))

        listOf(sphere, geo, chain).forEach { roundTrip(it) shouldBe it }
    }

    @Test
    fun `S035 RNF 10000 numeros aleatorios vuelven exactos`() {
        val random = Random(35)
        val failures = mutableListOf<String>()
        repeat(10_000) { i ->
            val c = crs.copy(
                definition = definition.copy(
                    ellipsoidSemiMajorM = 6_000_000.0 + random.nextDouble() * 1_000_000.0,
                    ellipsoidInverseFlattening = 250.0 + random.nextDouble() * 100.0,
                    primeMeridianDeg = random.nextDouble() * 360.0 - 180.0,
                    parameters = mapOf(
                        "latitudeOfOriginDeg" to random.nextDouble() * 178.0 - 89.0,
                        "longitudeOfOriginDeg" to random.nextDouble() * 360.0 - 180.0,
                        "scaleFactor" to 0.5 + random.nextDouble(),
                        "falseEasting" to random.nextDouble() * 2e7 - 1e7,
                        "falseNorthing" to random.nextDouble() * 2e7 - 1e7,
                    ),
                    unitMetres = 1.0,
                ),
                height = CustomHeight.ConstantGeoid(random.nextDouble() * 200.0 - 100.0),
            )
            val back = (CustomCrsJson.read(CustomCrsJson.write(c)) as? CustomCrsReadResult.Ok)?.crs
            if (back != c) failures += "#$i"
        }
        failures.shouldBeEmpty()
    }

    @Test
    fun `S035 RF-12 los numeros salen con punto decimal aunque la configuracion regional use coma`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("es-ES"))
        try {
            val text = CustomCrsJson.write(crs)

            text shouldContain "-3.123456789012345"
            text shouldNotContain "-3,123"
            roundTrip(crs) shouldBe crs
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `S035 RF-12 el JSON lleva el formato y la version`() {
        val text = CustomCrsJson.write(crs)

        text shouldContain "\"format\":\"topophone-crs\""
        text shouldContain "\"version\":1"
    }
}
