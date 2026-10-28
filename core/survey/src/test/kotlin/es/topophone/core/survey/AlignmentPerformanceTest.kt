package es.topophone.core.survey

import es.topophone.core.model.alignment.AlignmentElement
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.time.measureTime
import org.junit.jupiter.api.Test

/**
 * Un eje de 50 km hecho de 5.000 tramos (rectas, clotoides y arcos) se calcula sin frenar nada: un punto por PK, el PK de una posición
 * y el muestreo que dibuja el mapa caben de sobra en los 16 ms que no se pueden superar en el hilo principal (S049/RNF-T13).
 */
class AlignmentPerformanceTest {

    /** Cada 250 m: una recta de 100 m, una clotoide de 50 m, un arco de 50 m y otra clotoide de 50 m, alternando el sentido de giro. */
    private fun longAxis(): List<AlignmentElement> {
        val elements = ArrayList<AlignmentElement>()
        var chainage = 0.0
        var east = 300_000.0
        var north = 4_474_000.0
        var azimuth = PI / 2
        var sign = 1.0
        val k = 1.0 / 400
        fun add(element: AlignmentElement) {
            elements += element
            val end = ElementGeometry.stateAt(element, element.length)
            east = end.easting
            north = end.northing
            azimuth = end.azimuth
            chainage += element.length
        }
        repeat(200) {
            add(AlignmentElement.line(chainage, 100.0, east, north, azimuth))
            add(AlignmentElement.clothoid(chainage, 50.0, east, north, azimuth, 0.0, sign * k))
            add(AlignmentElement.arc(chainage, 50.0, east, north, azimuth, sign * k))
            add(AlignmentElement.clothoid(chainage, 50.0, east, north, azimuth, sign * k, 0.0))
            sign = -sign
        }
        // El resto hasta los 5.000 tramos, rectas de 10 m
        while (elements.size < 5_000) add(AlignmentElement.line(chainage, 10.0, east, north, azimuth))
        return elements
    }

    @Test
    fun `S049 RNF-T13 un punto por PK y el PK de una posicion en un eje de 5000 tramos tardan menos de 16 ms`() {
        val elements = longAxis()
        val geometry = AlignmentGeometry(elements)
        val length = elements.sumOf { it.length }
        val samples = (0 until 200).map { it * length / 200 }
        samples.forEach { geometry.pointAt(it, 3.5) } // calienta
        var worst = 0L

        for (pk in samples) {
            val point = geometry.pointAt(pk, 3.5).shouldNotBeNull()
            val elapsed = measureTime { geometry.locate(point.easting, point.northing).shouldNotBeNull() }
            worst = maxOf(worst, elapsed.inWholeMicroseconds)
        }

        println("Peor locate en un eje de ${elements.size} tramos y ${length / 1000} km: $worst µs")
        worst shouldBeLessThan 16_000L
        (length > 50_000.0) shouldBe true
    }

    @Test
    fun `S049 RNF-T13 muestrear los 50 km para el mapa tarda menos de un segundo en un hilo de fondo`() {
        val elements = longAxis()

        val elapsed = measureTime { AlignmentSampler.sample(elements, 0.5, 100.0).size.let { (it > 1000) shouldBe true } }

        println("Muestrear ${elements.size} tramos para el mapa: ${elapsed.inWholeMilliseconds} ms")
        elapsed.inWholeMilliseconds shouldBeLessThan 1_000L
    }
}
