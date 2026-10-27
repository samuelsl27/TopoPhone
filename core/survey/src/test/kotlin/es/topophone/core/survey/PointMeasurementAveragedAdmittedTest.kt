package es.topophone.core.survey

import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.EpochAssembler
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.GstAccuracy
import es.topophone.core.gnss.nmea.NmeaFramer
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.AdmittedSolutions
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.SolutionType
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.jupiter.api.Test

/** El promediado con los tipos de solución admitidos: el mejor tipo visto, lo peor se descarta (S047/RF-9, RF-10, RF-15). */
class PointMeasurementAveragedAdmittedTest {

    private fun epoch(index: Int, status: SolutionStatus, lat: Double = 40.416_80): Epoch = Epoch(
        utcMillisOfDay = 36_930_000 + index * 1_000,
        date = null,
        gga = GgaFix("t$index", lat, -3.703_71, status, 24, 0.6, 650.123, 51.234, 1.0, "\$GNGGA,$index*00"),
        gst = GstAccuracy("t$index", 0.008, 0.006, 0.016, "\$GNGST,$index*00"),
        rmc = null,
        vtg = null,
        zda = null,
        basePosition = null,
        tilt = null,
        gsa = emptyMap(),
        gsv = emptyMap(),
        completedAtMs = 7L,
    )

    private fun averaged(admitted: AdmittedSolutions, seconds: Int, intervalS: Double = 1.0) = PointMeasurement(
        MeasurementMode.AVERAGED,
        seconds,
        admitted,
        poleHeightM = 1.8,
        crs = ProjectCrs.ETRS89_UTM30,
        heightSystem = HeightSystem.ELLIPSOIDAL,
        epochIntervalS = intervalS,
        startedAtMs = 1_000_000L,
    )

    private fun PointMeasurement.feed(epoch: Epoch) = onEpoch(epoch, 1_000_000L + (epoch.utcMillisOfDay - 36_930_000))

    private fun PointMeasurement.run(statuses: List<SolutionStatus>, lats: List<Double>? = null): List<MeasurementUpdate> =
        statuses.withIndex().flatMap { (i, status) -> feed(epoch(i, status, lats?.get(i) ?: 40.416_80)) }

    private fun List<MeasurementUpdate>.done() = filterIsInstance<MeasurementUpdate.Done>().single().result

    private fun List<MeasurementUpdate>.degraded() = filterIsInstance<MeasurementUpdate.SolutionDegraded>().size

    private val fix = SolutionStatus.FIX
    private val float = SolutionStatus.FLOAT
    private val single = SolutionStatus.SINGLE

    @Test
    fun `S047 RF-9 llega un tipo mejor y se descartan las reunidas y se sigue solo con el nuevo`() {
        val updates = averaged(AdmittedSolutions.ALL, 3).run(listOf(float, float, fix, fix, fix))

        val result = updates.done()
        result.solution shouldBe SolutionStatus.FIX
        result.epochsUsed shouldBe 3
        result.epochsDiscardedByType shouldBe 2
        result.epochsDiscarded shouldBe 2
        updates.degraded() shouldBe 0
    }

    @Test
    fun `S047 RF-9 las epocas descartadas por llegar un tipo mejor no entran en la media`() {
        val lats = listOf(40.4170, 40.4170, 40.4168, 40.4168, 40.4168)
        val result = averaged(AdmittedSolutions.ALL, 3).run(listOf(float, float, fix, fix, fix), lats).done()

        result.antenna.latitudeDeg shouldBe (40.4168 plusOrMinus 1e-12)
    }

    @Test
    fun `S047 RF-10 una epoca peor que el mejor visto se descarta, se cuenta y se avisa una vez`() {
        val updates = averaged(AdmittedSolutions.ALL, 3).run(listOf(fix, fix, float, float, fix))

        updates.degraded() shouldBe 1
        val result = updates.done()
        result.solution shouldBe SolutionStatus.FIX
        result.epochsUsed shouldBe 3
        result.epochsDiscardedByType shouldBe 2
        result.epochsDiscarded shouldBe 2
    }

    @Test
    fun `S047 RF-10 el aviso vuelve a salir si se pierde otra vez tras volver el mejor tipo`() {
        val updates = averaged(AdmittedSolutions.ALL, 5).run(listOf(fix, float, fix, float, fix, fix))

        updates.degraded() shouldBe 2
    }

    @Test
    fun `S047 RF-6 un tipo no admitido nunca entra en el promedio`() {
        val updates = averaged(AdmittedSolutions.of(setOf(SolutionType.FIX, SolutionType.FLOAT)), 2)
            .run(listOf(fix, single, single, fix))

        updates.degraded() shouldBe 1
        val result = updates.done()
        result.solution shouldBe SolutionStatus.FIX
        result.epochsDiscardedByType shouldBe 2
    }

    @Test
    fun `S047 RF-10 antes de reunir nada, una epoca mejor que la admitida se descarta sin avisar y una peor avisa`() {
        val updates = averaged(AdmittedSolutions.of(setOf(SolutionType.FLOAT)), 2).run(listOf(fix, fix, float, float))

        updates.degraded() shouldBe 0
        val result = updates.done()
        result.solution shouldBe SolutionStatus.FLOAT
        result.epochsDiscardedByType shouldBe 2
        result.admitted shouldBe AdmittedSolutions.of(setOf(SolutionType.FLOAT))
        averaged(AdmittedSolutions.FIX_ONLY, 2).run(listOf(float)).degraded() shouldBe 1
    }

    @Test
    fun `S047 RF-18 una epoca DGPS cuenta como SINGLE y la solucion guardada es la peor de las usadas`() {
        val result = averaged(AdmittedSolutions.of(setOf(SolutionType.SINGLE)), 3)
            .run(listOf(SolutionStatus.DGPS, single, SolutionStatus.DGPS)).done()

        result.solution shouldBe SolutionStatus.SINGLE
        result.epochsUsed shouldBe 3
    }

    // La reproducción con FIX, FLOAT, SINGLE y DGPS alternados, contra el resultado calculado aparte (tools/nmea).

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        ?: error("No se encuentra la raíz del repositorio")

    private fun fixture(name: String) = File(root, "fixtures/nmea/sintetico/$name")

    private fun replayEpochs(): List<Epoch> {
        val assembler = EpochAssembler()
        val epochs = mutableListOf<Epoch>()
        var now = 0L
        NmeaFramer().feed(fixture("tipos-mezclados.nmea").readBytes()).forEach { sentence ->
            assembler.feed(sentence, now)?.let(epochs::add)
            now += 2
        }
        assembler.tick(now + 10_000)?.let(epochs::add)
        return epochs
    }

    @Test
    fun `S047 RF-9 y RF-10 la reproduccion mezclada da lo mismo que el resultado calculado aparte`() {
        val epochs = replayEpochs()
        epochs.size shouldBe 400
        val rows = fixture("tipos-mezclados.promedios.csv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val header = rows.first().split(',')
        val scenarios = rows.drop(1).map { header.zip(it.split(',')).toMap() }
        scenarios.size shouldBe 5

        for (row in scenarios) {
            val admitted = AdmittedSolutions.of(row.getValue("admitidos").split('+').map(SolutionType::valueOf).toSet())
            val start = row.getValue("inicio").toInt()
            val m = averaged(admitted, row.getValue("pedidos_s").toInt(), intervalS = 0.2)
            val updates = mutableListOf<MeasurementUpdate>()
            var end = -1
            for (i in start until epochs.size) {
                updates += m.onEpoch(epochs[i], 1_000_000L + i * 200L)
                if (updates.any { it is MeasurementUpdate.Done }) {
                    end = i
                    break
                }
            }
            val result = updates.done()
            end shouldBe row.getValue("epoca_fin").toInt()
            result.solution.name shouldBe row.getValue("solucion")
            result.epochsUsed shouldBe row.getValue("usadas").toInt()
            result.epochsDiscardedByType shouldBe row.getValue("descartadas_tipo").toInt()
            updates.degraded() shouldBe row.getValue("avisos").toInt()
            val tolerance = 1e-4 // 0,1 mm
            result.position.easting.shouldNotBeNull() shouldBe (row.getValue("media_e").toDouble() plusOrMinus tolerance)
            result.position.northing.shouldNotBeNull() shouldBe (row.getValue("media_n").toDouble() plusOrMinus tolerance)
            result.ground.ellipsoidalHeightM.shouldNotBeNull() shouldBe (row.getValue("media_h").toDouble() plusOrMinus tolerance)
        }
    }
}
