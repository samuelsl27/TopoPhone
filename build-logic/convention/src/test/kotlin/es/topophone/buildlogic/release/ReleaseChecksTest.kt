package es.topophone.buildlogic.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Comprobaciones previas a publicar un APK (S001/RF-30, S001/RF-31, S001/RF-32, S001/RF-33, S001/RF-34). */
class ReleaseChecksTest {

    // --- Etiqueta (RF-30, RF-31, RF-33) ---

    @Test
    fun `S001 RF-30 una etiqueta valida y mayor que la ultima publicada se acepta`() {
        assertEquals(emptyList<String>(), ReleaseChecks.checkTag("v0.0.2", lastPublishedTag = "v0.0.1"))
    }

    @Test
    fun `S001 RF-30 la primera publicacion v0_0_1 se acepta sin version anterior`() {
        assertEquals(emptyList<String>(), ReleaseChecks.checkTag("v0.0.1", lastPublishedTag = null))
        assertEquals(emptyList<String>(), ReleaseChecks.checkTag("v0.0.1", lastPublishedTag = ""))
    }

    @Test
    fun `S001 RF-31 una etiqueta mal formada se rechaza indicando el formato`() {
        listOf("v1.2", "1.2.3", "v1.2.3-rc1", "release-1").forEach { tag ->
            val errors = ReleaseChecks.checkTag(tag, lastPublishedTag = null)

            assertEquals(1, errors.size, tag)
            assertTrue("vX.Y.Z" in errors.single(), errors.single())
        }
    }

    @Test
    fun `S001 RF-33 una version igual a la ultima publicada se rechaza`() {
        val errors = ReleaseChecks.checkTag("v0.0.1", lastPublishedTag = "v0.0.1")

        assertEquals(1, errors.size)
        assertTrue("0.0.1" in errors.single())
    }

    @Test
    fun `S001 RF-33 una version menor que la ultima publicada se rechaza aunque como texto parezca mayor`() {
        assertEquals(1, ReleaseChecks.checkTag("v0.0.9", lastPublishedTag = "v0.1.0").size)
        assertEquals(emptyList<String>(), ReleaseChecks.checkTag("v0.10.0", lastPublishedTag = "v0.9.0"))
    }

    // --- Clave de firma (RF-32) ---

    private val allSigning = mapOf(
        "TOPOPHONE_KEYSTORE_FILE" to "/tmp/topophone.jks",
        "TOPOPHONE_KEYSTORE_PASSWORD" to "secreto-1",
        "TOPOPHONE_KEY_ALIAS" to "topophone",
        "TOPOPHONE_KEY_PASSWORD" to "secreto-2",
    )

    @Test
    fun `S001 RF-32 con todas las variables y el fichero de claves la firma esta disponible`() {
        assertEquals(emptyList<String>(), ReleaseChecks.checkSigning(allSigning) { true })
    }

    @Test
    fun `S001 RF-32 si falta alguna variable se nombra sin mostrar ningun secreto`() {
        val env = allSigning - "TOPOPHONE_KEY_PASSWORD" + ("TOPOPHONE_KEYSTORE_PASSWORD" to "  ")

        val errors = ReleaseChecks.checkSigning(env) { true }

        assertEquals(1, errors.size)
        assertTrue("TOPOPHONE_KEY_PASSWORD" in errors.single())
        assertTrue("TOPOPHONE_KEYSTORE_PASSWORD" in errors.single())
        assertFalse("secreto" in errors.single())
    }

    @Test
    fun `S001 RF-32 si el fichero de claves no existe la firma no esta disponible`() {
        val errors = ReleaseChecks.checkSigning(allSigning) { false }

        assertEquals(1, errors.size)
        assertTrue("TOPOPHONE_KEYSTORE_FILE" in errors.single())
    }

    // --- Huella del certificado (RF-34) ---

    private val apksignerOutput = """
        Signer #1 certificate DN: CN=TopoPhone, O=TopoPhone
        Signer #1 certificate SHA-256 digest: 3f2a1b0c9d8e7f6a5b4c3d2e1f0a9b8c7d6e5f4a3b2c1d0e9f8a7b6c5d4e3f2a
        Signer #1 certificate SHA-1 digest: 0011223344556677889900112233445566778899
    """.trimIndent()

    @Test
    fun `S001 RF-34 se lee la huella SHA-256 de la salida de apksigner`() {
        assertEquals(
            "3f2a1b0c9d8e7f6a5b4c3d2e1f0a9b8c7d6e5f4a3b2c1d0e9f8a7b6c5d4e3f2a",
            ReleaseChecks.parseApksignerSha256(apksignerOutput),
        )
        assertNull(ReleaseChecks.parseApksignerSha256("sin firmas"))
    }

    @Test
    fun `S001 RF-34 la huella registrada puede venir de keytool con dos puntos y mayusculas`() {
        val keytoolFormat = "SHA256: 3F:2A:1B:0C:9D:8E:7F:6A:5B:4C:3D:2E:1F:0A:9B:8C:7D:6E:5F:4A:3B:2C:1D:0E:9F:8A:7B:6C:5D:4E:3F:2A\n"

        assertEquals(emptyList<String>(), ReleaseChecks.checkCertificate(keytoolFormat, apksignerOutput))
    }

    @Test
    fun `S001 RF-34 una huella distinta a la registrada impide publicar`() {
        val other = "aa".repeat(32)

        val errors = ReleaseChecks.checkCertificate(other, apksignerOutput)

        assertEquals(1, errors.size)
        assertTrue("no coincide" in errors.single())
    }

    @Test
    fun `S001 RF-34 sin huella registrada o sin firma en el APK no se publica`() {
        assertEquals(1, ReleaseChecks.checkCertificate(null, apksignerOutput).size)
        assertEquals(1, ReleaseChecks.checkCertificate("   ", apksignerOutput).size)
        assertEquals(1, ReleaseChecks.checkCertificate("aa".repeat(32), "DOES NOT VERIFY").size)
    }
}
