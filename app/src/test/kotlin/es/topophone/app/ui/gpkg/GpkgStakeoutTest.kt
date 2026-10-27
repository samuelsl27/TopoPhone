package es.topophone.app.ui.gpkg

import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.cad.DesignSelection
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadEntityRef
import es.topophone.core.model.cad.GpkgValue
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith

/** El nombre con el que se replantean los puntos y las líneas importados de un GeoPackage (S048/RF-22, RF-24). */
@RunWith(AndroidJUnit4::class)
class GpkgStakeoutTest {

    private fun selection(name: String?, kind: CadEntityKind = CadEntityKind.POINT, ring: Boolean = false) = DesignSelection(
        CadEntityRef("l1", 1L, 0),
        CadEntity(kind, doubleArrayOf(440_000.0), doubleArrayOf(4_474_000.0), null, null, name = name, polygonRing = ring),
        "vertices",
        "capas.gpkg",
        false,
    )

    @Test
    fun `S048 RF-24 se replantea con el nombre del objeto`() {
        selection("Farola").stakeoutName shouldBe "Farola"
    }

    @Test
    fun `S048 RF-24 sin nombre se replantea con el de su capa como un DXF`() {
        selection(null).stakeoutName shouldBe "vertices"
    }

    @Test
    fun `S048 RF-24 un nombre en blanco cuenta como sin nombre`() {
        selection("   ").stakeoutName shouldBe "vertices"
        selection("").stakeoutName shouldBe "vertices"
    }

    @Test
    fun `S048 RF-24 el nombre de un contorno de poligono es el de su objeto y no cambia por ser contorno`() {
        selection("Parcela 2", CadEntityKind.POLYLINE, ring = true).stakeoutName shouldBe "Parcela 2"
    }

    @Test
    fun `S048 RF-24 los atributos de la seleccion no cambian el nombre`() {
        selection("Farola").copy(attributes = mapOf("nombre" to GpkgValue.Text("Otro"))).stakeoutName shouldBe "Farola"
    }

    @Test
    fun `S048 RF-24 los objetos sin nombre se nombran por su numero antes de importar y asi llegan`() {
        // La importación ya da el número del objeto como nombre cuando no hay campo (RF-22); el replanteo solo lo recoge.
        selection("7").stakeoutName shouldBe "7"
    }
}
