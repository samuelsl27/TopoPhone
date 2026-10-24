package es.topophone.data.point

import es.topophone.core.model.point.AutoTrigger
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.PrecisionControl
import es.topophone.data.settings.SettingsDataStore
import io.kotest.matchers.shouldBe
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Modo de medida, promediado y control de precisión, para todos los proyectos (S010/RF-9, RF-13). */
class CollectorPreferencesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file: File by lazy { File(folder.root, "collector.preferences_pb") }

    private fun <T> withPreferences(block: suspend (DataStoreCollectorPreferences) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            block(DataStoreCollectorPreferences(SettingsDataStore.create(scope) { file }))
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `S010 RF-13 de fabrica instantaneo, 5 s de promediado, solo FIX y sin limites`() {
        withPreferences { it.settings.first() } shouldBe CollectorSettings()
    }

    @Test
    fun `S010 RF-9 y RF-13 lo elegido se recuerda al volver a abrir la app, tambien quitar un limite`() {
        val chosen =
            CollectorSettings(
                MeasurementMode.AVERAGED,
                30,
                PrecisionControl(fixOnly = false, horizontalLimitM = 0.02, verticalLimitM = 0.035),
            )
        withPreferences { it.save(chosen) }
        withPreferences { it.settings.first() } shouldBe chosen

        withPreferences { it.save(chosen.copy(precision = chosen.precision.copy(horizontalLimitM = null))) }

        withPreferences { it.settings.first() }.precision.horizontalLimitM shouldBe null
    }

    @Test
    fun `S016 RF-20 la inclinacion maxima esta activa con 60 grados y se recuerda su valor o desactivada`() {
        withPreferences { it.settings.first() }.precision.maxTiltRad shouldBe Math.toRadians(60.0)

        withPreferences { it.save(CollectorSettings(precision = PrecisionControl(maxTiltRad = Math.toRadians(25.0)))) }
        withPreferences { it.settings.first() }.precision.maxTiltRad shouldBe Math.toRadians(25.0)

        withPreferences { it.save(CollectorSettings(precision = PrecisionControl(maxTiltRad = null))) }
        withPreferences { it.settings.first() }.precision.maxTiltRad shouldBe null
    }

    @Test
    fun `S017 RF-16 y RF-17 el disparo, el intervalo y la distancia se recuerdan, y de fabrica son por tiempo, 5 s y 1 m`() {
        val factory = withPreferences { it.settings.first() }
        factory.autoTrigger shouldBe AutoTrigger.TIME
        factory.autoIntervalS shouldBe 5
        factory.autoDistanceM shouldBe 1.0

        val chosen = CollectorSettings(autoTrigger = AutoTrigger.DISTANCE, autoIntervalS = 30, autoDistanceM = 2.5)
        withPreferences { it.save(chosen) }

        withPreferences { it.settings.first() } shouldBe chosen
    }

    @Test
    fun `S017 RF-1 el promediado se guarda hasta 3600 s y lo que pasa del rango se recorta`() {
        withPreferences { it.save(CollectorSettings(averagingSeconds = 3_600)) }
        withPreferences { it.settings.first() }.averagingSeconds shouldBe 3_600

        withPreferences { it.save(CollectorSettings(averagingSeconds = 7_200)) }
        withPreferences { it.settings.first() }.averagingSeconds shouldBe 3_600
    }

    @Test
    fun `S017 RF-16 y RF-17 el intervalo y la distancia fuera de rango se guardan recortados`() {
        withPreferences { it.save(CollectorSettings(autoIntervalS = 0, autoDistanceM = 5_000.0)) }

        val saved = withPreferences { it.settings.first() }
        saved.autoIntervalS shouldBe 1
        saved.autoDistanceM shouldBe 1_000.0
    }
}
