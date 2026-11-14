package es.topophone.app.ui.offline

import es.topophone.core.geodesy.GeoBox
import es.topophone.core.model.offline.DetailLevel
import es.topophone.core.model.offline.DownloadableSource
import es.topophone.core.model.offline.OfflineArea
import es.topophone.core.model.offline.SourceKind
import es.topophone.core.model.offline.ZoneMeta
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** El doble de las regiones de MapLibre que usan los tests del descargador y de las zonas (S037): se comporta como el contrato de [OfflineRegionStore]. */
class FakeOfflineRegionStoreTest {

    private val definition = RegionDefinition("https://x/s.json", GeoBox(40.4, -3.7, 40.5, -3.6), 7.0, 17.0)
    private val meta = ZoneMeta(
        "z",
        "p",
        "Zona",
        DownloadableSource(SourceKind.PNOA, "PNOA"),
        OfflineArea.Radius(40.4, -3.7, 500.0),
        DetailLevel.HIGH,
        10,
        1,
        null,
        0,
    )

    @Test
    fun `S037 RF-18 una region nueva esta inactiva y se lista con sus metadatos`() = runBlocking<Unit> {
        val store = FakeOfflineRegionStore()

        val id = store.create(definition, meta)

        store.list().single().let {
            it.id shouldBe id
            it.meta shouldBe meta
            it.progress.state shouldBe RegionDownloadState.INACTIVE
        }
    }

    @Test
    fun `S037 RF-16 activar y pausar cambian el estado y lo dejan en las llamadas`() = runBlocking<Unit> {
        val store = FakeOfflineRegionStore()
        val id = store.create(definition, meta)

        store.setActive(id, true)
        store.progressOf(id).state shouldBe RegionDownloadState.ACTIVE
        store.setActive(id, false)
        store.progressOf(id).state shouldBe RegionDownloadState.INACTIVE

        store.calls shouldContainExactly listOf("create:1", "setActive:1:true", "setActive:1:false")
    }

    @Test
    fun `S037 RF-15 el progreso se observa y al terminar la region vuelve a inactiva y completa`() = runBlocking<Unit> {
        val store = FakeOfflineRegionStore()
        val id = store.create(definition, meta)
        store.setActive(id, true)

        store.progress(id, completed = 5, required = 10)
        store.progressOf(id).fraction shouldBe 0.5f
        store.finish(id)

        store.progressOf(id).let {
            it.complete shouldBe true
            it.state shouldBe RegionDownloadState.INACTIVE
            it.fraction shouldBe 1f
        }
    }

    @Test
    fun `S037 RF-19 los errores se suman y una region sin total conocido tiene fraccion cero`() = runBlocking<Unit> {
        val store = FakeOfflineRegionStore()
        val id = store.create(definition, meta)

        store.fail(id, 3)
        store.fail(id)

        store.progressOf(id).failures shouldBe 4
        store.progressOf(id).fraction shouldBe 0f
    }

    @Test
    fun `S037 RF-27 borrar una region la quita de la lista y cierra su observacion`() = runBlocking<Unit> {
        val store = FakeOfflineRegionStore()
        val id = store.create(definition, meta)
        store.progress(id, 1, 2)

        store.delete(id)

        store.list() shouldBe emptyList()
        store.observe(id).toList() shouldBe emptyList()
    }

    @Test
    fun `S037 RF-25 los metadatos se cambian y una region ajena se lista sin ellos`() = runBlocking<Unit> {
        val store = FakeOfflineRegionStore()
        val id = store.create(definition, meta)
        store.addForeign(definition)

        store.updateMeta(id, meta.copy(name = "Otro nombre"))

        store.meta(id)?.name shouldBe "Otro nombre"
        store.list().map { it.meta?.name } shouldBe listOf("Otro nombre", null)
    }
}
