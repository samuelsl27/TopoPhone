package es.topophone.app.ui.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.BuildConfig
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.shouldBe
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.junit.runner.RunWith

/** Lo que trae el mapa al instalar la app: permisos, requisitos del móvil y su identificación ante los servicios (S009, RNF). */
@RunWith(AndroidJUnit4::class)
class MapSetupTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `S009 RNF-T1 el mapa no pide la ubicacion ni el estado de la Wi-Fi del movil`() {
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toList()

        permissions shouldNotContainAnyOf listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
        )
        permissions shouldContain Manifest.permission.INTERNET
    }

    @Test
    fun `S009 RNF el mapa no exige Vulkan, asi que funciona en todos los moviles con Android 8`() {
        val features = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_CONFIGURATIONS)
            .reqFeatures.orEmpty().mapNotNull { it.name }

        features.any { it.startsWith("android.hardware.vulkan") } shouldBe false
    }

    @Test
    fun `S009 RNF los servicios de mapas reciben un User-Agent que identifica la app`() {
        var sent: Request? = null
        val client = MapHttpClient.create(versionName = "1.2.3").newBuilder()
            .addInterceptor { chain ->
                sent = chain.request()
                Response.Builder().request(
                    chain.request(),
                ).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("".toResponseBody()).build()
            }
            .build()

        client.newCall(Request.Builder().url("https://tiles.openfreemap.org/styles/liberty").build()).execute().close()

        sent?.header("User-Agent") shouldBe "TopoPhone/1.2.3 (+${BuildConfig.REPO_URL})"
    }
}
