package es.topophone.data.receiver

import es.topophone.core.survey.PoleHeight
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

/** Preferencias del receptor entre sesiones (S002/RF-26, S002/RF-27, S002/RF-30, S003/RF-26, S004/RF-5, S004/RF-24). */
class ReceiverPreferencesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file: File by lazy { File(folder.root, "receiver.preferences_pb") }

    private fun <T> withPreferences(block: suspend (ReceiverPreferences) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            block(DataStoreReceiverPreferences(SettingsDataStore.create(scope) { file }))
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `S002 RF-26 sin nada guardado el caster esta vacio con el puerto 2101`() {
        withPreferences { it.ntrip.first() } shouldBe NtripPreferences(host = "", port = 2101, mountpoint = "", username = "")
    }

    @Test
    fun `S002 RF-26 servidor, puerto, punto de montaje y usuario se recuerdan al volver a abrir la app`() {
        val saved = NtripPreferences(host = "ergnss-tr.ign.es", port = 2102, mountpoint = "VRS3", username = "topografo")

        withPreferences { it.saveNtrip(saved) }

        withPreferences { it.ntrip.first() } shouldBe saved
    }

    @Test
    fun `S002 RF-27 la contrasena del caster no se guarda en el dispositivo`() {
        NtripPreferences::class.java.declaredFields.map { it.name.lowercase() }.any { "pass" in it } shouldBe false

        withPreferences { it.saveNtrip(NtripPreferences("h", 2101, "m", "u")) }

        String(file.readBytes(), Charsets.ISO_8859_1).lowercase().contains("pass") shouldBe false
    }

    @Test
    fun `S002 RF-30 la altura del jalon se recuerda y por defecto es 1,800 m`() {
        withPreferences { it.poleHeightM.first() } shouldBe PoleHeight.DEFAULT_M

        withPreferences { it.savePoleHeight(2.05) }

        withPreferences { it.poleHeightM.first() } shouldBe 2.05
    }

    @Test
    fun `S003 RF-26 el espacio para sesiones es de 500 MB por defecto y se recuerda el elegido`() {
        withPreferences { it.sessionSpaceMb.first() } shouldBe 500

        withPreferences { it.saveSessionSpaceMb(2000) }

        withPreferences { it.sessionSpaceMb.first() } shouldBe 2000
    }

    @Test
    fun `S003 RF-26 solo se aceptan los valores entre 100 MB y 2 GB que se ofrecen`() {
        SessionSpace.OPTIONS_MB shouldBe listOf(100, 250, 500, 1000, 2000)

        withPreferences {
            it.saveSessionSpaceMb(250)
            it.saveSessionSpaceMb(5000)
            it.saveSessionSpaceMb(50)
            it.sessionSpaceMb.first()
        } shouldBe 250
    }

    @Test
    fun `S004 RF-24 se recuerda el ultimo receptor con el que se conecto`() {
        withPreferences { it.lastReceiver.first() } shouldBe null

        withPreferences { it.saveLastReceiver(SavedReceiver("00:11:22:33:44:55", "reach-RS3")) }
        withPreferences { it.saveLastReceiver(SavedReceiver("66:77:88:99:AA:BB", "reach-RS3-2")) }

        withPreferences { it.lastReceiver.first() } shouldBe SavedReceiver("66:77:88:99:AA:BB", "reach-RS3-2")
    }

    @Test
    fun `S004 RF-5 se recuerda si ya se pidio el permiso de notificaciones`() {
        withPreferences { it.notificationsAsked.first() } shouldBe false

        withPreferences { it.markNotificationsAsked() }

        withPreferences { it.notificationsAsked.first() } shouldBe true
    }
}
