package es.topophone.app

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith

/** Instalación de la app (S001/RF-1). */
@RunWith(AndroidJUnit4::class)
class AppManifestTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `S001 RF-1 el identificador de la app es es_topophone_app`() {
        context.packageName shouldBe "es.topophone.app"
        BuildConfig.APPLICATION_ID shouldBe "es.topophone.app"
    }

    @Test
    fun `S001 RF-1 la version minima de Android es la 8_0 (API 26)`() {
        context.applicationInfo.minSdkVersion shouldBe 26
    }

    @Test
    fun `S001 RF-1 el nombre visible es TopoPhone`() {
        context.applicationInfo.loadLabel(context.packageManager).toString() shouldBe "TopoPhone"
    }

    @Test
    fun `S001 RF-1 el lanzador abre la actividad principal`() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)

        launch?.component?.className shouldBe MainActivity::class.java.name
    }

    @Test
    fun `S001 RF-1 la app arranca y llega a primer plano`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)

            scenario.state shouldBe Lifecycle.State.RESUMED
        }
    }
}
