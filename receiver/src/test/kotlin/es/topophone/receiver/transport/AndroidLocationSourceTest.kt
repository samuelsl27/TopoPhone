package es.topophone.receiver.transport

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** La ubicación del sistema Android como fuente del GNSS del móvil (S022/RF-2, RF-3, RF-5). */
@RunWith(AndroidJUnit4::class)
class AndroidLocationSourceTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val manager: LocationManager get() = context.getSystemService(LocationManager::class.java)
    private val source = AndroidLocationSource(context)

    @Before
    fun setUp() {
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
    }

    @Test
    fun `S022 RF-2 el permiso es el de ubicacion precisa`() {
        source.hasPermission() shouldBe false

        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        source.hasPermission() shouldBe true
    }

    @Test
    fun `S022 RF-3 la ubicacion apagada se detecta por el proveedor GNSS`() {
        source.isEnabled() shouldBe true

        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)

        source.isEnabled() shouldBe false
    }

    @Test
    fun `S022 RF-5 una posicion del sistema llega con su altura, precision y hora y deja de llegar al parar`() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val received = mutableListOf<LocationFix>()
        source.start { received += it }

        shadowOf(manager).simulateLocation(
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 43.2630126
                longitude = -2.9349852
                altitude = 123.456
                accuracy = 3.5f
                time = 1_794_736_803_450L
            },
        )
        shadowOf(Looper.getMainLooper()).idle()

        val fix = received.single()
        fix.latitudeDeg shouldBe 43.2630126
        fix.longitudeDeg shouldBe -2.9349852
        fix.ellipsoidalHeightM shouldBe 123.456
        fix.horizontalAccuracyM.shouldNotBeNull() shouldBe 3.5
        fix.verticalAccuracyM.shouldBeNull()
        fix.timeMillis shouldBe 1_794_736_803_450L
        fix.mock shouldBe false

        source.stop()
        shadowOf(manager).simulateLocation(Location(LocationManager.GPS_PROVIDER).apply { latitude = 1.0 })
        shadowOf(Looper.getMainLooper()).idle()
        received.size shouldBe 1
    }
}
