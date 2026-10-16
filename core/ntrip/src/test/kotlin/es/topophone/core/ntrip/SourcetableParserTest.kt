package es.topophone.core.ntrip

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.time.measureTime
import org.junit.jupiter.api.Test

/** Lectura de la tabla de fuentes con las tablas reales de `fixtures/ntrip/sourcetables/` (S007/RF-14, RF-15). */
class SourcetableParserTest {

    private fun fixture(name: String): String {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        return File(root, "fixtures/ntrip/sourcetables/$name.sourcetable").readText(Charsets.ISO_8859_1)
    }

    @Test
    fun `S007 RF-14 la tabla de red del IGN da sus 8 puntos de montaje, sin coordenadas y con GGA`() {
        val streams = SourcetableParser.parse(fixture("ign-ergnss-2101"))

        streams.map { it.mountpoint } shouldBe listOf("CERCANA3", "CERCANA3M", "FKP3", "FKP3M", "MAC3", "MAC3M", "VRS3", "VRS3M")
        with(streams.single { it.mountpoint == "VRS3M" }) {
            identifier shouldBe "VRS MSM"
            format shouldBe "RTCM3.2"
            navSystems shouldBe "GNSS"
            latitude.shouldBeNull()
            longitude.shouldBeNull()
            needsGga shouldBe true
        }
    }

    @Test
    fun `S007 RF-15 cada punto de montaje trae su identificador, formato, constelaciones, coordenadas y si necesita GGA`() {
        val streams = SourcetableParser.parse(fixture("rgapa-2101"))

        streams shouldHaveSize 42
        with(streams.single { it.mountpoint == "AVL13M" }) {
            identifier shouldBe "Aviles"
            format shouldBe "RTCM 3.2"
            navSystems shouldBe "GPS+GLO+GAL+BDS"
            latitude shouldBe 43.56
            longitude shouldBe -5.91
            needsGga shouldBe false
        }
    }

    @Test
    fun `S007 RF-14 una linea con campos vacios se lee igual`() {
        val fude = SourcetableParser.parse(fixture("rgapa-2101")).single { it.mountpoint == "FUDE3" }

        fude.formatDetails shouldBe ""
        fude.country shouldBe ""
        fude.latitude shouldBe 43.47
    }

    @Test
    fun `S007 RF-14 la tabla de estaciones del IGN da sus 263 puntos de montaje`() {
        SourcetableParser.parse(fixture("ign-ergnss-2102")) shouldHaveSize 263
    }

    @Test
    fun `S007 RF-14 las lineas sin punto de montaje, CAS y NET se saltan y lo que sigue a ENDSOURCETABLE no se lee`() {
        val text = listOf(
            "SOURCETABLE 200 OK",
            "",
            "CAS;caster.example;2101;Ejemplo;ES;0;ESP;40.0;-3.0;0.0.0.0;0;",
            "NET;RED;Ejemplo;B;N;;;;",
            "STR;",
            "STR",
            "STR;SOLO",
            "STR;BUENO;Madrid;RTCM 3.2;1077(1);2;GPS;RED;ESP;40.42;-3.70;1;0;X;none;B;N;9600;",
            "ENDSOURCETABLE",
            "STR;DESPUES;Madrid;RTCM 3.2;;2;GPS;RED;ESP;40.42;-3.70;0;0;X;none;B;N;9600;",
        ).joinToString("\r\n")

        val streams = SourcetableParser.parse(text)

        streams.map { it.mountpoint } shouldBe listOf("SOLO", "BUENO")
        streams.first().needsGga.shouldBeNull() // sin campo nmea: no se sabe
        streams.first().latitude.shouldBeNull()
    }

    @Test
    fun `S007 RF-14 una tabla sin STR esta vacia`() {
        SourcetableParser.parse("SOURCETABLE 200 OK\r\n\r\nENDSOURCETABLE\r\n").shouldBeEmpty()
    }

    @Test
    fun `S007 RNF 5000 puntos de montaje se leen y se ordenan con mucho margen sobre los 2 s`() {
        val text = (0 until 5000).joinToString("\r\n", postfix = "\r\nENDSOURCETABLE\r\n") { i ->
            "STR;M$i;Estacion $i;RTCM 3.2;1077(1);2;GPS+GLO;RED;ESP;${36 + i % 8}.${i % 100};-${i % 9}.5;0;0;X;none;B;N;9600;"
        }
        repeat(3) { Mountpoints.sort(SourcetableParser.parse(text), 40.4, -3.7) } // calentamiento de la JVM

        val time = measureTime { Mountpoints.sort(SourcetableParser.parse(text), 40.4, -3.7) }

        time.inWholeMilliseconds shouldBeLessThan 500
    }
}
