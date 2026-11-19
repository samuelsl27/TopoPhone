package es.topophone.receiver.experimental

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** S044/RF-7: el adaptador de `NsdManager` arranca y para sin fallar y avisa si la búsqueda no puede empezar. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NsdServiceBrowserTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class Recorder : ServiceBrowser.Listener {
        var failed = 0
        val found = mutableListOf<Pair<String, String>>()

        override fun onResolved(name: String, host: String) {
            found += name to host
        }

        override fun onFailed() {
            failed++
        }
    }

    @Test
    fun `S044 RF-7 arrancar y parar el navegador dos veces no falla`() {
        val browser = NsdServiceBrowser(context)
        val recorder = Recorder()

        browser.start(recorder)
        browser.stop()
        browser.stop()
        browser.start(recorder)
        browser.stop()

        recorder.found shouldBe emptyList()
    }

    @Test
    fun `S044 RF-7 el tipo de servicio es el de la documentacion recopilada`() {
        NsdServiceBrowser.SERVICE_TYPE shouldBe "_reach._tcp."
    }
}
