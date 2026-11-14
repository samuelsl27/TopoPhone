package es.topophone.app.ui.map

import io.kotest.matchers.shouldBe
import org.junit.Test

/** No más de 4 peticiones simultáneas a un mismo servidor, que es lo que admite OpenFreeMap en las descargas (S037/RNF). */
class MapDispatcherTest {

    @Test
    fun `S037 RNF el cliente del mapa admite 4 peticiones a la vez por servidor`() {
        MapHttpClient.create(versionName = "1.0.0").dispatcher.maxRequestsPerHost shouldBe 4
    }
}
