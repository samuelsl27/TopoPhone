package es.topophone.core.ntrip

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Orden por distancia y búsqueda de puntos de montaje (S007/RF-16 a RF-18). */
class MountpointsTest {

    private fun stream(mountpoint: String, identifier: String = mountpoint, lat: Double? = null, lon: Double? = null) = SourcetableStream(
        mountpoint = mountpoint,
        identifier = identifier,
        format = "RTCM 3.2",
        formatDetails = "",
        carrier = "2",
        navSystems = "GPS",
        network = "RED",
        country = "ESP",
        latitude = lat,
        longitude = lon,
        needsGga = false,
    )

    // Coordenadas de la tabla de RGAPA; receptor en Oviedo.
    private val oviedo = 43.3614 to -5.8494
    private val streams = listOf(
        stream("AVL13M", "Aviles", 43.56, -5.91),
        stream("VRS3M", "VRS MSM"), // de red: sin coordenadas
        stream("SIRO3M", "Siero", 43.39, -5.66),
        stream("OVIE3M", "Oviedo", 43.36, -5.85),
        stream("OVIE2", "Oviedo", 43.36, -5.85),
        stream("cercana3m", "CERCANA MSM"),
    )

    @Test
    fun `S007 RF-16 con posicion se ordenan de mas cercano a mas lejano y los que no tienen coordenadas van al final por nombre`() {
        val ranked = Mountpoints.sort(streams, oviedo.first, oviedo.second)

        ranked.map { it.stream.mountpoint } shouldBe listOf("OVIE2", "OVIE3M", "SIRO3M", "AVL13M", "cercana3m", "VRS3M")
        ranked.first().distanceKm.shouldNotBeNull() shouldBe (0.15 plusOrMinus 0.05)
        ranked.single { it.stream.mountpoint == "AVL13M" }.distanceKm.shouldNotBeNull() shouldBe (22.6 plusOrMinus 0.1)
        ranked.last().distanceKm.shouldBeNull()
    }

    @Test
    fun `S007 RF-17 sin posicion se ordenan por nombre sin distinguir mayusculas y sin distancias`() {
        val ranked = Mountpoints.sort(streams, latitude = null, longitude = null)

        ranked.map { it.stream.mountpoint } shouldBe listOf("AVL13M", "cercana3m", "OVIE2", "OVIE3M", "SIRO3M", "VRS3M")
        ranked.all { it.distanceKm == null } shouldBe true
    }

    @Test
    fun `S007 RF-18 la busqueda mira el nombre y el identificador sin distinguir mayusculas`() {
        val ranked = Mountpoints.sort(streams, oviedo.first, oviedo.second)

        Mountpoints.filter(ranked, "ovie").map { it.stream.mountpoint } shouldBe listOf("OVIE2", "OVIE3M")
        Mountpoints.filter(ranked, "AVILES").map { it.stream.mountpoint } shouldBe listOf("AVL13M")
        Mountpoints.filter(ranked, "msm").map { it.stream.mountpoint } shouldBe listOf("cercana3m", "VRS3M")
        Mountpoints.filter(ranked, "  ") shouldBe ranked
    }

    @Test
    fun `S007 RF-16 la distancia sale de la formula del semiverseno sobre la esfera de 6371 km`() {
        // Un grado de meridiano: 6371 · π / 180.
        Mountpoints.distanceKm(40.0, -3.0, 41.0, -3.0) shouldBe (111.195 plusOrMinus 0.001)
        Mountpoints.distanceKm(40.0, -3.0, 40.0, -3.0) shouldBe 0.0
    }
}
