package es.topophone.core.model.offline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OfflineAreaTest {

    @Test
    fun `S037 RF-2 el area es un radio, el encuadre visible o todo el proyecto`() {
        OfflineArea.Radius(40.4, -3.7, 500.0).kind shouldBe OfflineAreaKind.RADIUS
        OfflineArea.Viewport(40.41, -3.71, 40.42, -3.70).kind shouldBe OfflineAreaKind.VIEWPORT
        OfflineArea.ProjectExtent(40.41, -3.71, 40.42, -3.70, 500.0).kind shouldBe OfflineAreaKind.PROJECT
        OfflineAreaKind.entries.map { it.key } shouldBe listOf("radius", "viewport", "project")
        OfflineAreaKind.entries.forEach { OfflineAreaKind.fromKey(it.key) shouldBe it }
    }

    @Test
    fun `S037 RF-3 un radio fuera de 100 m a 20 km no es un area`() {
        shouldThrow<IllegalArgumentException> { OfflineArea.Radius(40.4, -3.7, 99.0) }
        shouldThrow<IllegalArgumentException> { OfflineArea.Radius(40.4, -3.7, 20_001.0) }
        OfflineArea.Radius(40.4, -3.7, 100.0).radiusM shouldBe 100.0
        OfflineArea.Radius(40.4, -3.7, 20_000.0).radiusM shouldBe 20_000.0
    }

    @Test
    fun `S037 RF-4 un margen fuera de 200 m a 2 km no es un area de todo el proyecto`() {
        shouldThrow<IllegalArgumentException> { OfflineArea.ProjectExtent(40.41, -3.71, 40.42, -3.70, 199.0) }
        shouldThrow<IllegalArgumentException> { OfflineArea.ProjectExtent(40.41, -3.71, 40.42, -3.70, 2_001.0) }
    }

    @Test
    fun `S037 RF-6 un proyecto con un solo punto es un area valida de todo el proyecto`() {
        val one = OfflineArea.ProjectExtent(40.41, -3.71, 40.41, -3.71, 200.0)
        one.south shouldBe one.north
        one.west shouldBe one.east
    }

    @Test
    fun `S037 RF-2 un encuadre o una extension con el sur por encima del norte no es un area`() {
        shouldThrow<IllegalArgumentException> { OfflineArea.Viewport(40.42, -3.71, 40.41, -3.70) }
        shouldThrow<IllegalArgumentException> { OfflineArea.Viewport(40.41, -3.70, 40.42, -3.71) }
        shouldThrow<IllegalArgumentException> { OfflineArea.ProjectExtent(40.42, -3.71, 40.41, -3.70, 500.0) }
    }

    @Test
    fun `S037 RF-2 una latitud o una longitud fuera de rango no es un area`() {
        shouldThrow<IllegalArgumentException> { OfflineArea.Radius(91.0, -3.7, 500.0) }
        shouldThrow<IllegalArgumentException> { OfflineArea.Radius(40.4, 181.0, 500.0) }
    }
}
