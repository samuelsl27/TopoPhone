package es.topophone.app.receiver

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.EntryPointAccessors
import es.topophone.app.MainActivity
import es.topophone.app.R
import es.topophone.app.ui.receiver.FakeBluetooth
import es.topophone.app.ui.receiver.FakePreferences
import es.topophone.app.ui.receiver.RS3_DEVICE
import es.topophone.app.ui.receiver.nmea
import es.topophone.app.ui.text
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/** Servicio en primer plano con la notificación del receptor (S004/RF-7 a RF-11). Interfaz en español (es-ES). */
@RunWith(AndroidJUnit4::class)
class ReceiverServiceTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val session: ReceiverSession = EntryPointAccessors.fromApplication(app, ReceiverServiceEntryPoint::class.java).session()
    private val toApp = PipedOutputStream()
    private var controller: ServiceController<ReceiverService>? = null

    @After
    fun tearDown() {
        session.disconnect()
        runCatching { toApp.close() }
        controller?.destroy()
    }

    private fun receiving() {
        session.connect(StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream()))
        toApp.write(nmea("GNGGA,101530.00,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
        toApp.flush()
        runBlocking { withTimeout(5_000) { session.state.first { it.connection is ConnectionState.Receiving } } }
    }

    private fun start(): ReceiverService {
        val built = Robolectric.buildService(ReceiverService::class.java).create().startCommand(0, 1)
        controller = built
        return built.get()
    }

    private fun Notification.text(): String = extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    /** Espera a que el servicio procese el estado, que se muestrea a 1 Hz. */
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        condition() shouldBe true
    }

    @Test
    fun `S004 RF-7 el servicio se declara en primer plano de tipo dispositivo conectado`() {
        val info = app.packageManager.getServiceInfo(ComponentName(app, ReceiverService::class.java), PackageManager.GET_META_DATA)

        info.foregroundServiceType shouldBe ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        info.exported shouldBe false
    }

    @Test
    fun `S004 RF-8 la notificacion muestra la solucion y la edad de las correcciones`() {
        receiving()

        val service = start()

        val notification = shadowOf(service).lastForegroundNotification.shouldNotBeNull()
        notification.text() shouldContain text(R.string.solution_fix)
        notification.text() shouldContain "1,0 s"
        (notification.flags and Notification.FLAG_ONGOING_EVENT) shouldBe Notification.FLAG_ONGOING_EVENT
    }

    @Test
    fun `S004 RF-8 durante la reconexion la notificacion lo indica`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        receivingWithReconnect()
        start()

        toApp.close()

        val reconnecting = text(R.string.notification_reconnecting, 1).substringBefore(" ·")
        waitFor {
            shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.any { it.text().contains(reconnecting) }
        }
    }

    @Test
    fun `S004 RF-11 al dejar de haber receptor el servicio quita la notificacion y se detiene`() {
        receiving()
        val service = start()

        session.disconnect()

        waitFor { shadowOf(service).isStoppedBySelf }
        shadowOf(service).isForegroundStopped shouldBe true
    }

    @Test
    fun `S004 RF-7 al conectar un receptor se arranca el servicio`() {
        var started = 0
        val connections = ReceiverConnections(
            session,
            es.topophone.app.ui.receiver.FakeBluetooth().apply {
                transports[es.topophone.app.ui.receiver.RS3_DEVICE.address] =
                    StreamTransport("reach-RS3", PipedInputStream(toApp, 1024), ByteArrayOutputStream())
            },
            es.topophone.app.ui.receiver.FakePreferences(),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
        ) {
            started++
        }

        connections.connect(es.topophone.app.ui.receiver.RS3_DEVICE.address)

        started shouldBe 1
    }

    private fun receivingWithReconnect() {
        val transport = StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream())
        session.connect(transport) { throw IOException("sin receptor") }
        toApp.write(nmea("GNGGA,101530.00,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
        toApp.flush()
        runBlocking { withTimeout(5_000) { session.state.first { it.connection is ConnectionState.Receiving } } }
    }

    @Test
    fun `S004 RF-9 pulsar la notificacion abre la app en la seccion Receptor`() {
        receiving()

        val notification = shadowOf(start()).lastForegroundNotification.shouldNotBeNull()

        val intent = shadowOf(notification.contentIntent).savedIntent
        intent.component?.className shouldBe MainActivity::class.java.name
        intent.getBooleanExtra(MainActivity.EXTRA_OPEN_RECEIVER, false) shouldBe true
    }

    @Test
    fun `S004 RF-10 la notificacion tiene la accion Desconectar, que cierra la conexion y el servicio`() {
        receiving()
        val service = start()
        val notification = shadowOf(service).lastForegroundNotification.shouldNotBeNull()
        val action = notification.actions.single()
        action.title.toString() shouldBe text(R.string.receiver_disconnect)

        val disconnect = shadowOf(action.actionIntent).savedIntent
        controller!!.withIntent(disconnect).startCommand(0, 2)

        session.state.value.connection shouldBe ConnectionState.Disconnected
        waitFor { shadowOf(service).isStoppedBySelf }
    }
}
