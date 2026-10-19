package es.topophone.core.formats.backup

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Cuándo toca una copia automática y qué copias sobran (S013/RF-18, RF-19). */
class AutoBackupPolicyTest {

    private val hour = 60 * 60 * 1000L
    private val now = 1_792_400_000_000L

    @Test
    fun `S013 RF-18 toca con carpeta, con cambios sin copiar y una hora despues de la anterior`() {
        AutoBackupPolicy.due(now, lastSuccessMs = now - hour, pendingChanges = true, folderSet = true) shouldBe true
        AutoBackupPolicy.due(now, lastSuccessMs = now - hour + 1, pendingChanges = true, folderSet = true) shouldBe false
        AutoBackupPolicy.due(now, lastSuccessMs = null, pendingChanges = true, folderSet = true) shouldBe true
    }

    @Test
    fun `S013 RF-18 sin cambios o sin carpeta no se hace ninguna`() {
        AutoBackupPolicy.due(now, lastSuccessMs = now - 5 * hour, pendingChanges = false, folderSet = true) shouldBe false
        AutoBackupPolicy.due(now, lastSuccessMs = null, pendingChanges = true, folderSet = false) shouldBe false
    }

    @Test
    fun `S013 RF-18 si el reloj del movil vuelve atras no se espera a que alcance la ultima copia`() {
        AutoBackupPolicy.due(now, lastSuccessMs = now + 3 * hour, pendingChanges = true, folderSet = true) shouldBe true
    }

    @Test
    fun `S013 RF-19 se conservan las 24 copias automaticas mas recientes y no se toca nada mas`() {
        val automatic = (0 until 30).map { "TopoPhone_auto_202610%02d-0800.zip".format(it + 1) }
        val others = listOf("TopoPhone_20261001-0800.zip", "TopoPhone_antes-de-restaurar_20261001-0800.zip", "notas.txt")

        val surplus = AutoBackupPolicy.surplus((others + automatic).shuffled())

        surplus shouldContainExactlyInAnyOrder automatic.take(6)
    }

    @Test
    fun `S013 RF-19 una copia repetida en el mismo minuto cuenta como mas reciente que la original`() {
        val names = (1..23).map { "TopoPhone_auto_202610%02d-0800.zip".format(it) } +
            listOf("TopoPhone_auto_20261024-0800.zip", "TopoPhone_auto_20261024-0800 (1).zip")

        AutoBackupPolicy.surplus(names) shouldBe listOf("TopoPhone_auto_20261001-0800.zip")
    }

    @Test
    fun `S013 RF-19 con 24 o menos no sobra ninguna`() {
        AutoBackupPolicy.surplus((1..24).map { "TopoPhone_auto_202610%02d-0800.zip".format(it) }) shouldBe emptyList()
    }
}
