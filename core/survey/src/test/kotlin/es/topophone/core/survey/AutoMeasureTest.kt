package es.topophone.core.survey

import es.topophone.core.model.point.AutoTrigger
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * El modo automático decide cuándo medir y lleva la cuenta (S017/RF-19 a RF-21, RF-24, RF-25, RF-27, RF-28, RF-32).
 * Las épocas llegan cada 200 ms, con la hora UTC del receptor.
 */
class AutoMeasureTest {

    private val start = 36_000_000

    private fun byTime(intervalS: Int = 5) = AutoMeasure(AutoTrigger.TIME, intervalS = intervalS, distanceM = 1.0)

    private fun byDistance(distanceM: Double = 2.0) = AutoMeasure(AutoTrigger.DISTANCE, intervalS = 5, distanceM = distanceM)

    private fun utc(afterMs: Int) = start + afterMs

    /** Épocas de 200 ms desde [fromMs] hasta [toMs] (incluida), quietas en el origen; lo que sale de ellas, por época. */
    private fun AutoMeasure.run(fromMs: Int, toMs: Int, ground: GroundEN? = GroundEN(0.0, 0.0)): List<Pair<Int, List<AutoAction>>> =
        (fromMs..toMs step 200).map { it to onEpoch(utc(it), ground) }.filter { it.second.isNotEmpty() }

    private val saved = AutoOutcome.Saved(0.0, 0.0)

    private fun omitted() = AutoOutcome.Omitted("precisión")

    @Test
    fun `S017 RF-19 al iniciar mide un primer punto de inmediato`() {
        val auto = byTime()

        auto.start(utc(0)) shouldBe listOf(AutoAction.StartPoint)

        auto.running shouldBe true
        auto.inProgress shouldBe true
    }

    @Test
    fun `S017 RF-20 por tiempo mide un punto cada intervalo, contado desde el ultimo disparo`() {
        val auto = byTime(intervalS = 5)
        auto.start(utc(0))
        auto.onPointOutcome(saved)

        val first = auto.run(200, 6_000)
        first.map { it.first } shouldBe listOf(5_000)
        first.single().second shouldBe listOf(AutoAction.StartPoint)

        // El segundo punto se guarda a los 6 s, 1 s tarde: el siguiente disparo sigue siendo 5 s después del anterior.
        auto.onPointOutcome(saved)
        auto.run(6_200, 11_000).map { it.first } shouldBe listOf(10_000)
    }

    @Test
    fun `S017 RF-20 el intervalo se cuenta desde el ultimo disparo aunque su punto se omitiera`() {
        val auto = byTime(intervalS = 5)
        auto.start(utc(0))
        auto.onPointOutcome(omitted())

        auto.run(200, 11_000).map { it.first } shouldBe listOf(5_000, 10_000)
    }

    @Test
    fun `S017 RF-24 un punto sin terminar al llegar el siguiente disparo se omite y se avisa una sola vez`() {
        val auto = byTime(intervalS = 5)
        auto.start(utc(0)) // sigue midiendo: nadie da el resultado

        val first = auto.run(200, 5_000).single().second
        first shouldBe listOf(AutoAction.OmitCurrent(OmitCause.NOT_FINISHED, warn = true), AutoAction.StartPoint)
        auto.omitted shouldBe 1

        val second = auto.run(5_200, 10_000).single().second
        second shouldBe listOf(AutoAction.OmitCurrent(OmitCause.NOT_FINISHED, warn = false), AutoAction.StartPoint)
        auto.omitted shouldBe 2
    }

    @Test
    fun `S017 RF-24 el aviso vuelve a sonar cuando un punto se guarda y otro se omite`() {
        val auto = byTime(intervalS = 5)
        auto.start(utc(0))

        auto.onPointOutcome(omitted()).warn shouldBe true
        auto.run(200, 5_000)
        auto.onPointOutcome(omitted()).warn shouldBe false
        auto.run(5_200, 10_000)
        auto.onPointOutcome(saved)
        auto.run(10_200, 15_000)
        auto.onPointOutcome(omitted()).warn shouldBe true

        auto.saved shouldBe 1
        auto.omitted shouldBe 3
    }

    @Test
    fun `S017 RF-21 por distancia mide cuando el suelo esta a esa distancia o mas, en planta, del ultimo guardado`() {
        val auto = byDistance(distanceM = 5.0)
        auto.start(utc(0))
        auto.onPointOutcome(AutoOutcome.Saved(100.0, 200.0))

        auto.onEpoch(utc(200), GroundEN(101.0, 200.0)).shouldBeEmpty()
        auto.onEpoch(utc(400), GroundEN(104.9, 200.0)).shouldBeEmpty()
        // 3-4-5: hipot(3, 4) = 5 m exactos.
        auto.onEpoch(utc(600), GroundEN(103.0, 204.0)) shouldBe listOf(AutoAction.StartPoint)
    }

    @Test
    fun `S017 RF-21 sin posicion no toca y el siguiente punto cuenta desde el ultimo guardado`() {
        val auto = byDistance(distanceM = 2.0)
        auto.start(utc(0))
        auto.onPointOutcome(AutoOutcome.Saved(0.0, 0.0))

        auto.onEpoch(utc(200), null).shouldBeEmpty()
        auto.onEpoch(utc(400), GroundEN(2.5, 0.0)) shouldBe listOf(AutoAction.StartPoint)
        auto.onPointOutcome(AutoOutcome.Saved(2.5, 0.0))
        auto.onEpoch(utc(600), GroundEN(4.0, 0.0)).shouldBeEmpty() // a 1,5 m del último
        auto.onEpoch(utc(800), GroundEN(4.5, 0.0)) shouldBe listOf(AutoAction.StartPoint)
    }

    @Test
    fun `S017 RF-24 por distancia un punto sin terminar se omite si el jalon ya ha avanzado esa distancia`() {
        val auto = byDistance(distanceM = 2.0)
        auto.start(utc(0))
        auto.onEpoch(utc(200), GroundEN(0.0, 0.0)).shouldBeEmpty() // el primer punto sigue en curso
        auto.onPointOutcome(AutoOutcome.Saved(0.0, 0.0))
        auto.onEpoch(utc(400), GroundEN(2.0, 0.0)) shouldBe listOf(AutoAction.StartPoint)

        auto.onEpoch(utc(600), GroundEN(3.0, 0.0)).shouldBeEmpty()
        auto.onEpoch(utc(800), GroundEN(4.0, 0.0)) shouldBe
            listOf(AutoAction.OmitCurrent(OmitCause.NOT_FINISHED, warn = true), AutoAction.StartPoint)
    }

    @Test
    fun `S017 RF-25 por distancia un punto omitido se reintenta con la siguiente epoca y cuenta como uno solo`() {
        val auto = byDistance(distanceM = 2.0)
        auto.start(utc(0))
        auto.onPointOutcome(omitted()).warn shouldBe true
        auto.omitted shouldBe 1

        auto.onEpoch(utc(200), GroundEN(0.0, 0.0)) shouldBe listOf(AutoAction.StartPoint) // reintento
        auto.onPointOutcome(omitted()).warn shouldBe false
        auto.onEpoch(utc(400), GroundEN(0.1, 0.0)) shouldBe listOf(AutoAction.StartPoint)
        auto.onPointOutcome(omitted())
        auto.omitted shouldBe 1

        auto.onEpoch(utc(600), GroundEN(0.2, 0.0)) shouldBe listOf(AutoAction.StartPoint)
        auto.onPointOutcome(AutoOutcome.Saved(0.2, 0.0))
        auto.onEpoch(utc(800), GroundEN(0.3, 0.0)).shouldBeEmpty()

        // Otra racha, otra omisión.
        auto.onEpoch(utc(1_000), GroundEN(2.5, 0.0)) shouldBe listOf(AutoAction.StartPoint)
        auto.onPointOutcome(omitted())
        auto.omitted shouldBe 2
        auto.saved shouldBe 1
    }

    @Test
    fun `S017 RF-28 en pausa no se mide y por tiempo el intervalo cuenta de nuevo desde la reanudacion`() {
        val auto = byTime(intervalS = 5)
        auto.start(utc(0))
        auto.onPointOutcome(saved)
        auto.run(200, 2_000)

        auto.pause()
        auto.paused shouldBe true
        // Un corte de 60 s: al volver, el intervalo empieza de nuevo.
        auto.resume(utc(62_000))
        auto.paused shouldBe false

        auto.run(62_200, 66_800).shouldBeEmpty()
        auto.run(67_000, 67_000).map { it.first } shouldBe listOf(67_000)
    }

    @Test
    fun `S017 RF-28 en pausa las epocas no disparan`() {
        val auto = byTime(intervalS = 5)
        auto.start(utc(0))
        auto.onPointOutcome(saved)

        auto.pause()

        auto.run(200, 20_000).shouldBeEmpty()
    }

    @Test
    fun `S017 RF-28 por distancia al volver se mide un unico punto si ya se cumple la distancia`() {
        val auto = byDistance(distanceM = 2.0)
        auto.start(utc(0))
        auto.onPointOutcome(AutoOutcome.Saved(0.0, 0.0))

        auto.pause()
        auto.resume(utc(30_000))

        auto.onEpoch(utc(30_000), GroundEN(50.0, 0.0)) shouldBe listOf(AutoAction.StartPoint)
        auto.onEpoch(utc(30_200), GroundEN(50.2, 0.0)).shouldBeEmpty() // en curso: no hay otro
    }

    @Test
    fun `S017 RF-28 un punto perdido por un corte no cuenta como omitido`() {
        val auto = byTime(intervalS = 5)
        auto.start(utc(0))
        auto.onPointOutcome(saved)
        auto.run(200, 5_000) // el segundo punto empieza
        auto.inProgress shouldBe true

        auto.onPointCancelled()

        auto.inProgress shouldBe false
        auto.omitted shouldBe 0
        auto.saved shouldBe 1
    }

    @Test
    fun `S017 RF-27 parar da lo guardado y lo omitido y no mide mas`() {
        val auto = byTime(intervalS = 5)
        auto.start(utc(0))
        auto.onPointOutcome(saved)
        auto.run(200, 5_000)
        auto.onPointOutcome(omitted())
        auto.run(5_200, 10_000)
        auto.onPointOutcome(saved)

        val summary = auto.stop()

        summary shouldBe AutoSummary(saved = 2, omitted = 1)
        auto.running shouldBe false
        auto.run(10_200, 30_000).shouldBeEmpty()
    }

    @Test
    fun `S017 RF-20 el intervalo cruza la medianoche`() {
        val auto = byTime(intervalS = 1)
        val ground = GroundEN(0.0, 0.0)
        auto.start(86_399_400)
        auto.onPointOutcome(saved)

        auto.onEpoch(86_399_800, ground).shouldBeEmpty() // 400 ms
        auto.onEpoch(86_399_999, ground).shouldBeEmpty() // 599 ms
        auto.onEpoch(399, ground).shouldBeEmpty() // ya de otro día: 999 ms
        auto.onEpoch(400, ground) shouldBe listOf(AutoAction.StartPoint) // 1.000 ms
    }

    @Test
    fun `S017 RF-20 una reproduccion que vuelve a empezar no hace saltar el reloj un dia ni adelanta el disparo`() {
        val auto = byTime(intervalS = 5)
        auto.start(utc(0))
        auto.onPointOutcome(saved)
        auto.run(200, 3_000) // 3 s desde el disparo

        // El bucle de la reproducción vuelve 30 s atrás: esa época no suma tiempo y las siguientes siguen de 200 en 200 ms.
        auto.onEpoch(utc(-27_000), GroundEN(0.0, 0.0)).shouldBeEmpty()
        auto.run(-26_800, -25_400).shouldBeEmpty() // 4,6 s
        auto.run(-25_200, -24_000).map { it.first } shouldBe listOf(-25_000) // 5,0 s
    }

    @Test
    fun `S017 RF-26 lo que falta para el siguiente punto`() {
        val time = byTime(intervalS = 5)
        time.start(utc(0))
        time.onPointOutcome(saved)
        time.run(200, 1_000)
        time.nextInS().shouldNotBeNull() shouldBe (4.0 plusOrMinus 1e-9)
        time.distanceToNextM(GroundEN(1.0, 1.0)).shouldBeNull()

        val distance = byDistance(distanceM = 2.0)
        distance.start(utc(0))
        distance.onPointOutcome(AutoOutcome.Saved(10.0, 10.0))
        distance.nextInS().shouldBeNull()
        distance.distanceToNextM(GroundEN(10.0, 11.5)).shouldNotBeNull() shouldBe (0.5 plusOrMinus 1e-9)
        distance.distanceToNextM(GroundEN(10.0, 15.0)).shouldNotBeNull() shouldBe (0.0 plusOrMinus 1e-9)
        distance.distanceToNextM(null).shouldBeNull()
    }

    @Test
    fun `S017 RF-27 sin iniciar no hay nada que parar ni medir`() {
        val auto = byTime()

        auto.running shouldBe false
        auto.onEpoch(utc(0), GroundEN(0.0, 0.0)).shouldBeEmpty()
        auto.stop() shouldBe AutoSummary(0, 0)
    }
}
