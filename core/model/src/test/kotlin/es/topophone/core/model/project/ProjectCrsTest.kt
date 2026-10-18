package es.topophone.core.model.project

import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectDefaults
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Catálogo de SC de España, alturas, ámbitos, huso propuesto y datum de las correcciones (S008). */
class ProjectCrsTest {

    @Test
    fun `S008 RF-1 hay diez SC con su datum, su huso y su codigo EPSG`() {
        ProjectCrs.entries.map { Triple(it.datum, it.utmZone, it.epsg) } shouldContainExactly listOf(
            Triple(GeodeticDatum.ETRS89, 29, 25829),
            Triple(GeodeticDatum.ETRS89, 30, 25830),
            Triple(GeodeticDatum.ETRS89, 31, 25831),
            Triple(GeodeticDatum.ETRS89, null, 4258),
            Triple(GeodeticDatum.REGCAN95, 27, 4082),
            Triple(GeodeticDatum.REGCAN95, 28, 4083),
            Triple(GeodeticDatum.REGCAN95, null, 4081),
            Triple(GeodeticDatum.ED50, 29, 23029),
            Triple(GeodeticDatum.ED50, 30, 23030),
            Triple(GeodeticDatum.ED50, 31, 23031),
        )
    }

    @Test
    fun `S008 RF-1 cada SC se encuentra por su datum y su huso, y sin huso son geograficas`() {
        ProjectCrs.of(GeodeticDatum.REGCAN95, 28) shouldBe ProjectCrs.REGCAN95_UTM28
        ProjectCrs.of(GeodeticDatum.ETRS89, null) shouldBe ProjectCrs.ETRS89_GEOGRAPHIC
        ProjectCrs.of(GeodeticDatum.ED50, null).shouldBeNull() // ED50 solo en UTM
        ProjectCrs.ETRS89_GEOGRAPHIC.isUtm shouldBe false
        ProjectCrs.ED50_UTM30.isUtm shouldBe true
    }

    @Test
    fun `S008 RF-2 y RF-3 todos admiten altitud y altura elipsoidal salvo ED50, que solo admite altitud`() {
        ProjectCrs.entries.forEach { crs ->
            crs.heightSystems shouldBe if (crs.datum == GeodeticDatum.ED50) {
                listOf(HeightSystem.ORTHOMETRIC)
            } else {
                listOf(HeightSystem.ORTHOMETRIC, HeightSystem.ELLIPSOIDAL)
            }
        }
    }

    @Test
    fun `S008 RF-9 los SC REGCAN95 son de Canarias y los ETRS89 y ED50 de la Peninsula y Baleares`() {
        ProjectCrs.entries.forEach { crs ->
            crs.area shouldBe if (crs.datum == GeodeticDatum.REGCAN95) CrsArea.CANARIAS else CrsArea.PENINSULA_BALEARES
        }
        CrsArea.of(40.4168, -3.7038) shouldBe CrsArea.PENINSULA_BALEARES // Madrid
        CrsArea.of(39.5696, 2.6502) shouldBe CrsArea.PENINSULA_BALEARES // Palma
        CrsArea.of(28.4636, -16.2518) shouldBe CrsArea.CANARIAS // Santa Cruz de Tenerife
        CrsArea.of(48.8566, 2.3522).shouldBeNull() // París
    }

    @Test
    fun `S008 RF-11 al crear un proyecto se propone el SC UTM del huso y del territorio de la posicion`() {
        ProjectCrs.suggested(ProjectCrs.ETRS89_UTM30, 42.8782, -8.5448) shouldBe ProjectCrs.ETRS89_UTM29 // Santiago
        ProjectCrs.suggested(ProjectCrs.ETRS89_UTM30, 41.3874, 2.1686) shouldBe ProjectCrs.ETRS89_UTM31 // Barcelona
        ProjectCrs.suggested(ProjectCrs.ETRS89_UTM30, 28.4636, -16.2518) shouldBe ProjectCrs.REGCAN95_UTM28 // Tenerife
        ProjectCrs.suggested(ProjectCrs.ETRS89_UTM30, 27.72, -18.1) shouldBe ProjectCrs.REGCAN95_UTM27 // El Hierro (oeste)
        ProjectCrs.suggested(ProjectCrs.ED50_UTM30, 41.3874, 2.1686) shouldBe ProjectCrs.ED50_UTM31
        ProjectCrs.suggested(ProjectCrs.ED50_UTM30, 28.4636, -16.2518) shouldBe ProjectCrs.REGCAN95_UTM28 // ED50 no llega a Canarias
        ProjectCrs.suggested(ProjectCrs.REGCAN95_UTM28, 40.4168, -3.7038) shouldBe ProjectCrs.ETRS89_UTM30
    }

    @Test
    fun `S008 RF-11 con geograficas por defecto o fuera de Espana no se propone huso`() {
        ProjectCrs.suggested(ProjectCrs.ETRS89_GEOGRAPHIC, 42.8782, -8.5448).shouldBeNull()
        ProjectCrs.suggested(ProjectCrs.ETRS89_UTM30, 48.8566, 2.3522).shouldBeNull()
    }

    @Test
    fun `S008 RF-17 cada SC tiene su datum de las correcciones`() {
        ProjectCrs.entries.forEach { crs ->
            crs.correctionsDatum shouldBe if (crs.datum == GeodeticDatum.REGCAN95) CorrectionsDatum.REGCAN95 else CorrectionsDatum.ETRS89
        }
    }

    @Test
    fun `S008 RF-18 se avisa si las correcciones no estan en el datum del SC`() {
        DatumCheck.mismatch(ProjectCrs.REGCAN95_UTM28, CorrectionsDatum.ETRS89) shouldBe true
        DatumCheck.mismatch(ProjectCrs.REGCAN95_UTM28, CorrectionsDatum.ITRF_WGS84) shouldBe true
        DatumCheck.mismatch(ProjectCrs.REGCAN95_UTM28, CorrectionsDatum.REGCAN95) shouldBe false
        DatumCheck.mismatch(ProjectCrs.ED50_UTM30, CorrectionsDatum.ETRS89) shouldBe false
        DatumCheck.mismatch(ProjectCrs.ED50_UTM30, CorrectionsDatum.ITRF_WGS84) shouldBe true
        DatumCheck.mismatch(ProjectCrs.ETRS89_GEOGRAPHIC, CorrectionsDatum.REGCAN95) shouldBe true
        // El caso de S006/RF-11 tiene su propio texto de 0,9 m.
        DatumCheck.itrfOverEtrs89(ProjectCrs.ED50_UTM30, CorrectionsDatum.ITRF_WGS84) shouldBe true
        DatumCheck.itrfOverEtrs89(ProjectCrs.REGCAN95_UTM28, CorrectionsDatum.ITRF_WGS84) shouldBe false
    }

    @Test
    fun `S008 RF-2 un proyecto nuevo usa altitud si no se elige otra cosa`() {
        ProjectDefaults().heightSystem shouldBe HeightSystem.ORTHOMETRIC
        ProjectDefaults().toDraft().heightSystem shouldBe HeightSystem.ORTHOMETRIC
    }

    @Test
    fun `S008 RF-1 al cambiar de datum se conserva el huso si existe y si no se usa el habitual del datum`() {
        ProjectCrs.ETRS89_UTM29.closestIn(GeodeticDatum.ED50) shouldBe ProjectCrs.ED50_UTM29
        ProjectCrs.ETRS89_UTM30.closestIn(GeodeticDatum.REGCAN95) shouldBe ProjectCrs.REGCAN95_UTM28
        ProjectCrs.REGCAN95_UTM27.closestIn(GeodeticDatum.ETRS89) shouldBe ProjectCrs.ETRS89_UTM30
        ProjectCrs.ETRS89_GEOGRAPHIC.closestIn(GeodeticDatum.REGCAN95) shouldBe ProjectCrs.REGCAN95_GEOGRAPHIC
        ProjectCrs.ETRS89_GEOGRAPHIC.closestIn(GeodeticDatum.ED50) shouldBe ProjectCrs.ED50_UTM30
    }

    @Test
    fun `S008 RF-3 y RF-17 al elegir un SC el borrador toma su datum de las correcciones y una altura admitida`() {
        val draft = ProjectDefaults(heightSystem = HeightSystem.ELLIPSOIDAL).toDraft()

        val ed50 = draft.withCrs(ProjectCrs.ED50_UTM30)
        ed50.heightSystem shouldBe HeightSystem.ORTHOMETRIC
        ed50.correctionsDatum shouldBe CorrectionsDatum.ETRS89

        val canarias = draft.copy(correctionsDatum = CorrectionsDatum.ITRF_WGS84).withCrs(ProjectCrs.REGCAN95_UTM28)
        canarias.correctionsDatum shouldBe CorrectionsDatum.REGCAN95
        canarias.heightSystem shouldBe HeightSystem.ELLIPSOIDAL
    }

    @Test
    fun `S008 RF-11 el SC propuesto cambia el huso y, si el territorio pide otro datum, tambien el de las correcciones`() {
        val draft = ProjectDefaults(correctionsDatum = CorrectionsDatum.ITRF_WGS84).toDraft()

        draft.withProposedCrs(ProjectCrs.ETRS89_UTM29) shouldBe draft.copy(crs = ProjectCrs.ETRS89_UTM29)
        draft.withProposedCrs(ProjectCrs.REGCAN95_UTM28) shouldBe
            draft.copy(crs = ProjectCrs.REGCAN95_UTM28, correctionsDatum = CorrectionsDatum.REGCAN95)
    }

    @Test
    fun `S008 RF-10 en los valores por defecto el SC elegido trae su datum de las correcciones y una altura que admite`() {
        val defaults = ProjectDefaults(author = "S. S.", heightSystem = HeightSystem.ELLIPSOIDAL)

        defaults.withCrs(ProjectCrs.REGCAN95_GEOGRAPHIC) shouldBe
            defaults.copy(crs = ProjectCrs.REGCAN95_GEOGRAPHIC, correctionsDatum = CorrectionsDatum.REGCAN95)
        defaults.withCrs(ProjectCrs.ED50_UTM31) shouldBe defaults.copy(crs = ProjectCrs.ED50_UTM31, heightSystem = HeightSystem.ORTHOMETRIC)
    }
}
