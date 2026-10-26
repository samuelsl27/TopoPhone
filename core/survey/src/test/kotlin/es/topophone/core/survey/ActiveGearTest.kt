package es.topophone.core.survey

import es.topophone.core.model.point.PoleKind
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El jalón y el perfil activos de un receptor, ya resueltos (S046/RF-7, RF-13, RF-28; plan A3). */
class ActiveGearTest {

    private val standard = SavedPole("p1", "Jalón estándar", PoleKind.VERTICAL, 1.8)
    private val short = SavedPole("p2", "Jalón corto", PoleKind.VERTICAL, 1.2)
    private val slant = SavedPole("p3", "Inclinado", PoleKind.SLANT, 1.95)
    private val own = AntennaProfile("own:a1", "Mi antena", 0.12, 0.06, builtIn = false)
    private val ownNoRadius = AntennaProfile("own:a2", "Sin radio", 0.1, null, builtIn = false)

    @Test
    fun `S046 RF-7 un receptor sin configurar usa el RS3 y el primer jalon`() {
        val gear = GearResolver.resolve(null, emptyList(), null, listOf(standard, short))
        gear.profile shouldBe AntennaProfile.RS3
        gear.pole shouldBe standard
        gear.verticalPoleM shouldBe 1.8
        gear.antennaHeightM!! shouldBe (1.934).plusOrMinus(1e-12)
        gear.problem.shouldBeNull()
    }

    @Test
    fun `S046 RF-13 el jalon activo y el perfil elegidos se usan`() {
        val gear = GearResolver.resolve("own:a1", listOf(own), "p2", listOf(standard, short))
        gear.profile shouldBe own
        gear.pole shouldBe short
        gear.antennaHeightM!! shouldBe (1.32).plusOrMinus(1e-12)
    }

    @Test
    fun `S046 RF-7 un perfil que ya no existe cae en el RS3 y un jalon que ya no existe en el primero`() {
        val gear = GearResolver.resolve("own:borrado", listOf(own), "p9", listOf(standard, short))
        gear.profile shouldBe AntennaProfile.RS3
        gear.pole shouldBe standard
    }

    @Test
    fun `S046 RF-1 un perfil del catalogo se resuelve por su referencia`() {
        GearResolver.resolve("catalog:RS4", emptyList(), "p1", listOf(standard)).antennaHeightM!! shouldBe (1.895).plusOrMinus(1e-12)
    }

    @Test
    fun `S046 RF-28 sin ningun jalon no se puede medir`() {
        val gear = GearResolver.resolve(null, emptyList(), null, emptyList())
        gear.pole.shouldBeNull()
        gear.verticalPoleM.shouldBeNull()
        gear.antennaHeightM.shouldBeNull()
        gear.problem shouldBe GearProblem.NO_POLE
        gear.usable shouldBe false
    }

    @Test
    fun `S046 RF-18 RF-28 un jalon inclinado con un perfil sin radio no permite medir`() {
        val gear = GearResolver.resolve("own:a2", listOf(ownNoRadius), "p3", listOf(standard, slant))
        gear.problem shouldBe GearProblem.NO_RADIUS
        gear.antennaHeightM.shouldBeNull()
        GearResolver.resolve(null, emptyList(), "p3", listOf(slant)).problem shouldBe GearProblem.NO_RADIUS
    }

    @Test
    fun `S046 RF-17 RF-19 un jalon inclinado con radio da la vertical y cambia al cambiar el perfil`() {
        val gear = GearResolver.resolve("own:a1", listOf(own), "p3", listOf(slant))
        gear.problem.shouldBeNull()
        gear.verticalPoleM!! shouldBe (1.949076).plusOrMinus(1e-6)
        gear.antennaHeightM!! shouldBe (1.949076 + 0.12).plusOrMinus(1e-6)
        val bigger = own.copy(radiusM = 0.2)
        GearResolver.resolve("own:a1", listOf(bigger), "p3", listOf(slant)).verticalPoleM!! shouldBe (1.939716).plusOrMinus(1e-6)
    }

    @Test
    fun `S046 RF-18 con la inclinada menor que el radio no se puede medir`() {
        val tiny = SavedPole("p4", "Muy corto", PoleKind.SLANT, 0.05)
        GearResolver.resolve("own:a1", listOf(own), "p4", listOf(tiny)).problem shouldBe GearProblem.SLANT_SHORTER_THAN_RADIUS
    }
}
