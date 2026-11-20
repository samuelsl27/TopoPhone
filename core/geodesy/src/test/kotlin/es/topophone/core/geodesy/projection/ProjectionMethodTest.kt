package es.topophone.core.geodesy.projection

import es.topophone.core.geodesy.Ellipsoid
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** La lista de métodos de proyección que la app calcula y su contrato (S032/RF-20). */
class ProjectionMethodTest {

    @Test
    fun `S032 RF-20 la lista de metodos son los doce que declara la spec`() {
        ProjectionMethod.entries.toSet() shouldBe setOf(
            ProjectionMethod.TRANSVERSE_MERCATOR,
            ProjectionMethod.LAMBERT_CONIC_1SP,
            ProjectionMethod.LAMBERT_CONIC_2SP,
            ProjectionMethod.MERCATOR_A,
            ProjectionMethod.MERCATOR_B,
            ProjectionMethod.OBLIQUE_MERCATOR_A,
            ProjectionMethod.OBLIQUE_MERCATOR_B,
            ProjectionMethod.OBLIQUE_STEREOGRAPHIC,
            ProjectionMethod.POLAR_STEREOGRAPHIC_A,
            ProjectionMethod.POLAR_STEREOGRAPHIC_B,
            ProjectionMethod.POLAR_STEREOGRAPHIC_C,
            ProjectionMethod.WEB_MERCATOR,
        )
    }

    @Test
    fun `S032 RF-20 las proyecciones de siempre figuran como calculadas y no se repite ninguna`() {
        ProjectionMethod.implemented shouldContain ProjectionMethod.TRANSVERSE_MERCATOR
        ProjectionMethod.implemented shouldContain ProjectionMethod.WEB_MERCATOR
        ProjectionMethod.implemented.size shouldBe ProjectionMethod.implemented.toSet().size
    }

    @Test
    fun `S032 RF-20 cada variante de parametros declara su propio metodo`() {
        ProjectionTestData.all.map { it.method } shouldContainExactlyInAnyOrder
            ProjectionMethod.entries - setOf(ProjectionMethod.TRANSVERSE_MERCATOR, ProjectionMethod.WEB_MERCATOR)
    }

    @Test
    fun `S032 RF-20 la fabrica crea cada una de las diez variantes y ninguna se rechaza como no soportada`() {
        ProjectionTestData.all.forEach { parameters ->
            val result = ProjectionFactory.create(ProjectionSetup(Ellipsoid.GRS80, parameters))

            result.shouldBeInstanceOf<ProjectionResult.Ok>()
        }
    }

    @Test
    fun `S032 RF-20 la lista publicada es exactamente lo que la fabrica sabe crear mas la Transversa de Mercator y Web Mercator`() {
        val creatable = ProjectionTestData.all.filter {
            ProjectionFactory.create(ProjectionSetup(Ellipsoid.GRS80, it)) is ProjectionResult.Ok
        }.map { it.method }

        ProjectionMethod.implemented.toSet() shouldBe
            creatable.toSet() + setOf(ProjectionMethod.TRANSVERSE_MERCATOR, ProjectionMethod.WEB_MERCATOR)
    }

    @Test
    fun `S032 RF-20 la lista publicada contiene los doce metodos de la spec`() {
        ProjectionMethod.implemented.toSet() shouldBe ProjectionMethod.entries.toSet()
        ProjectionMethod.implemented.size shouldBe 12
    }
}
