package es.topophone.core.survey.localization

import es.topophone.core.model.localization.LocalizationPair
import es.topophone.core.model.localization.VerticalKind
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import kotlin.math.abs
import org.junit.jupiter.api.Test

/**
 * La reproducción sintética `rs3-localizacion` (S039): cuatro vértices con coordenadas conocidas y un punto medido después. Los vértices dan la
 * localización y el punto medido sale en el sistema de la obra a menos de 0,1 mm (constitución nº 2); los datos los calcula pyproj en
 * `tools/localizacion/generar_replay_localizacion.py`.
 */
class LocalizationReplayTest {

    private data class Stop(
        val name: String,
        val role: String,
        val e: Double,
        val n: Double,
        val h: Double,
        val knownE: Double,
        val knownN: Double,
        val knownH: Double,
    )

    private val stops: List<Stop> by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        File(root, "fixtures/nmea/sintetico/rs3-localizacion.pares.csv").readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .drop(1)
            .map { it.split(',') }
            .map {
                Stop(
                    it[0],
                    it[1],
                    it[3].toDouble(),
                    it[4].toDouble(),
                    it[5].toDouble(),
                    it[6].toDouble(),
                    it[7].toDouble(),
                    it[8].toDouble(),
                )
            }
    }

    private fun solution() = LocalizationSolver.solve(
        stops.filter { it.role == "par" }.map {
            LocalizationPair(it.name, it.name, it.name, it.e, it.n, it.h, it.knownE, it.knownN, it.knownH)
        },
        fixScale = false,
    ).shouldBeInstanceOf<LocalizationOutcome.Solved>().solution

    @Test
    fun `S039 RF-9 los cuatro vertices dan una localizacion sin residuos y con plano vertical`() {
        val s = solution()

        s.params.verticalKind shouldBe VerticalKind.PLANE
        s.residuals.forEach {
            ((it.horizontal ?: 0.0) < 1e-4) shouldBe true
            (abs(it.vertical ?: 0.0) < 1e-4) shouldBe true
        }
        (abs(s.params.rotationRad - 0.0035) < 1e-8) shouldBe true
        (abs(s.params.scale - 1.00002) < 1e-8) shouldBe true
    }

    @Test
    fun `S039 RF-17 el punto medido despues sale en el sistema de la obra a menos de 0,1 mm`() {
        val params = solution().params
        val p5 = stops.first { it.role == "medido" }

        val (e, n) = LocalizationApplier.horizontal(params, p5.e, p5.n)
        val h = LocalizationApplier.height(params, p5.e, p5.n, p5.h)

        (abs(e - p5.knownE) < 1e-4) shouldBe true
        (abs(n - p5.knownN) < 1e-4) shouldBe true
        (abs(h - p5.knownH) < 1e-4) shouldBe true
    }

    @Test
    fun `S039 RF-17 localizar y deslocalizar el punto medido cierra a menos de 0,1 mm`() {
        val params = solution().params
        val p5 = stops.first { it.role == "medido" }

        val (le, ln) = LocalizationApplier.horizontal(params, p5.e, p5.n)
        val lh = LocalizationApplier.height(params, p5.e, p5.n, p5.h)
        val (be, bn) = LocalizationApplier.delocalizeHorizontal(params, le, ln)
        val bh = LocalizationApplier.delocalizeHeight(params, be, bn, lh)

        (abs(be - p5.e) < 1e-4) shouldBe true
        (abs(bn - p5.n) < 1e-4) shouldBe true
        (abs(bh - p5.h) < 1e-4) shouldBe true
    }
}
