package es.topophone.app.log

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.BuildConfig
import es.topophone.app.ui.receiver.FakePreferences
import es.topophone.data.receiver.SavedReceiver
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Datos de la app y del móvil que van en el informe (S014/RF-13). */
@RunWith(AndroidJUnit4::class)
class AndroidDeviceInfoTest {

    @Test
    fun `S014 RF-13 se leen la app, el movil, el espacio, los permisos y el ultimo receptor`() {
        val receiver = FakePreferences().apply { lastReceiverFlow.value = SavedReceiver("00:11:22:33:44:55", "RS3 Ana") }

        val info = runBlocking { AndroidDeviceInfo(ApplicationProvider.getApplicationContext<Context>(), receiver).read() }

        info.appVersion shouldBe BuildConfig.VERSION_NAME
        info.model.shouldNotBeEmpty()
        info.androidRelease.shouldNotBeEmpty()
        info.freeBytes shouldBeGreaterThanOrEqual 0L // Robolectric da 0; en el móvil es el espacio real.
        info.permissions.keys.contains("BLUETOOTH_CONNECT") shouldBe true
        info.lastReceiver shouldBe "RS3 Ana"
    }
}
