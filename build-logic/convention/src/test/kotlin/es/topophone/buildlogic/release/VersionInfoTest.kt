package es.topophone.buildlogic.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Tests de la versión derivada de git (S001/RF-21, S001/RF-30, S001/RF-31, S001/RF-33). */
class VersionInfoTest {

    @Test
    fun `S001 RF-30 etiqueta exacta v0_1_0 da version 0_1_0 y versionCode 100`() {
        val info = VersionInfo.from(exactTag = "v0.1.0", allTags = listOf("v0.1.0"))

        assertEquals("0.1.0", info.versionName)
        assertEquals(100, info.versionCode)
        assertTrue(info.isRelease)
    }

    @Test
    fun `S001 RF-30 la primera version publicada v0_0_1 da versionCode 1 y v0_0_2 da 2`() {
        val first = VersionInfo.from(exactTag = "v0.0.1", allTags = listOf("v0.0.1"))
        val second = VersionInfo.from(exactTag = "v0.0.2", allTags = listOf("v0.0.1", "v0.0.2"))

        assertEquals("0.0.1", first.versionName)
        assertEquals(1, first.versionCode)
        assertEquals("0.0.2", second.versionName)
        assertEquals(2, second.versionCode)
    }

    @Test
    fun `S001 RF-30 versionCode es X por 10000 mas Y por 100 mas Z`() {
        val info = VersionInfo.from(exactTag = "v1.2.3", allTags = listOf("v1.2.3"))

        assertEquals("1.2.3", info.versionName)
        assertEquals(10203, info.versionCode)
    }

    @Test
    fun `S001 RF-21 sin etiquetas la version es 0_0_0-dev con versionCode 1`() {
        val info = VersionInfo.from(exactTag = null, allTags = emptyList())

        assertEquals("0.0.0-dev", info.versionName)
        assertEquals(1, info.versionCode)
        assertFalse(info.isRelease)
    }

    @Test
    fun `S001 RF-21 sin etiqueta exacta se usa la ultima version valida con sufijo -dev`() {
        val info = VersionInfo.from(
            exactTag = null,
            allTags = listOf("v0.2.0", "v0.10.0", "v0.9.9", "no-es-version", "v1.2"),
        )

        assertEquals("0.10.0-dev", info.versionName)
        assertEquals(1000, info.versionCode)
        assertFalse(info.isRelease)
    }

    @Test
    fun `S001 RF-31 una etiqueta exacta mal formada no produce una version de publicacion`() {
        val info = VersionInfo.from(exactTag = "v1.2", allTags = listOf("v0.1.0", "v1.2"))

        assertEquals("0.1.0-dev", info.versionName)
        assertFalse(info.isRelease)
    }

    @ParameterizedTest
    @ValueSource(strings = ["v1.2", "1.2.3", "v1.2.3.4", "v01.2.3", "v1.02.3", "v1.100.0", "v1.2.100", "v1.2.3-beta", "V1.2.3", ""])
    fun `S001 RF-31 formatos de etiqueta no validos se rechazan`(tag: String) {
        assertNull(SemVer.parseTag(tag))
    }

    @ParameterizedTest
    @ValueSource(strings = ["v0.0.1", "v0.1.0", "v1.0.0", "v10.99.99", "v123.4.5"])
    fun `S001 RF-31 formatos de etiqueta validos se aceptan`(tag: String) {
        val version = SemVer.parseTag(tag)

        assertEquals(tag.removePrefix("v"), version.toString())
    }

    @Test
    fun `S001 RF-33 las versiones se comparan numericamente y no como texto`() {
        assertTrue(SemVer(0, 10, 0) > SemVer(0, 9, 9))
        assertTrue(SemVer(1, 0, 0) > SemVer(0, 99, 99))
        assertTrue(SemVer(0, 2, 0) > SemVer(0, 1, 9))
        assertEquals(0, SemVer(1, 2, 3).compareTo(SemVer(1, 2, 3)))
    }

    @Test
    fun `S001 RF-30 el versionCode crece con cada version mayor`() {
        val ordered = listOf("v0.0.1", "v0.0.2", "v0.0.3", "v0.1.0", "v0.1.1", "v0.2.0", "v0.99.99", "v1.0.0", "v2.0.0")
            .map { requireNotNull(SemVer.parseTag(it)) }

        ordered.zipWithNext().forEach { (previous, next) ->
            assertTrue(next.versionCode > previous.versionCode, "$next debe tener un versionCode mayor que $previous")
        }
    }

    @Test
    fun `S001 RF-20 el commit se acorta a 12 caracteres y sin git es unknown`() {
        assertEquals("0123456789ab", GitCommit.normalize("0123456789abcdef0123456789abcdef01234567\n"))
        assertEquals("unknown", GitCommit.normalize(""))
        assertEquals("unknown", GitCommit.normalize(null))
    }
}
