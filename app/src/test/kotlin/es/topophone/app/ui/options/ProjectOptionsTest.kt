package es.topophone.app.ui.options

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import org.junit.Test

class ProjectOptionsTest {

    @Test
    fun `S059 RF-2 Importar lleva ocho filas en su orden`() {
        ProjectOptions.of(OptionGroup.IMPORT) shouldBe listOf(
            ProjectOption.IMPORT_POINTS,
            ProjectOption.IMPORT_CAD,
            ProjectOption.IMPORT_SURFACE,
            ProjectOption.IMPORT_RASTER,
            ProjectOption.IMPORT_GEOPACKAGE,
            ProjectOption.IMPORT_SHAPEFILE,
            ProjectOption.IMPORT_KML,
            ProjectOption.IMPORT_ALIGNMENT,
        )
    }

    @Test
    fun `S059 RF-3 Exportar lleva seis filas en su orden`() {
        ProjectOptions.of(OptionGroup.EXPORT) shouldBe listOf(
            ProjectOption.EXPORT_POINTS,
            ProjectOption.EXPORT_GEOPACKAGE,
            ProjectOption.EXPORT_CAD,
            ProjectOption.EXPORT_SHAPEFILE,
            ProjectOption.EXPORT_KML,
            ProjectOption.EXPORT_PHOTOS,
        )
    }

    @Test
    fun `S059 RF-4 Herramientas lleva cinco filas en su orden`() {
        ProjectOptions.of(OptionGroup.TOOLS) shouldBe listOf(
            ProjectOption.LINE_FROM_POINTS,
            ProjectOption.THREE_POINT_ANGLE,
            ProjectOption.COGO,
            ProjectOption.BASE_SHIFT,
            ProjectOption.LOCALIZATION,
        )
    }

    @Test
    fun `S059 RF-6 el menu de mas opciones solo lleva el informe de replanteo y la papelera`() {
        ProjectOptions.of(OptionGroup.MORE) shouldBe listOf(ProjectOption.STAKEOUT_REPORT, ProjectOption.POINT_TRASH)
    }

    @Test
    fun `S059 RNF ningun menu pasa de ocho filas`() {
        OptionGroup.entries.forEach { ProjectOptions.of(it).size shouldBeLessThanOrEqualTo ProjectOptions.MAX_ROWS }
    }

    @Test
    fun `S059 RF-7 estan las 21 opciones de hoy, cada una en un solo grupo y con nombre unico`() {
        ProjectOption.entries shouldHaveSize 21
        ProjectOption.entries.map { it.key }.toSet() shouldHaveSize 21
        OptionGroup.entries.sumOf { ProjectOptions.of(it).size } shouldBe 21
    }
}
