package es.topophone.buildlogic.release

/**
 * Comprobaciones previas a publicar un APK en GitHub Releases (ADR-20). Cada función devuelve
 * la lista de errores; vacía = se puede publicar. Los mensajes nunca incluyen secretos.
 */
object ReleaseChecks {

    /** Variables de entorno con la firma de publicación (los secretos de GitHub Actions). */
    val SIGNING_VARIABLES = listOf(
        "TOPOPHONE_KEYSTORE_FILE",
        "TOPOPHONE_KEYSTORE_PASSWORD",
        "TOPOPHONE_KEY_ALIAS",
        "TOPOPHONE_KEY_PASSWORD",
    )

    private val APKSIGNER_SHA256 = Regex("""Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F:]+)""")

    /** Formato `vX.Y.Z` (S001/RF-31) y versión mayor que la última publicada (S001/RF-33). */
    fun checkTag(tag: String, lastPublishedTag: String?): List<String> {
        val version = SemVer.parseTag(tag)
            ?: return listOf("La etiqueta '$tag' no sigue el formato vX.Y.Z (S001/RF-31).")
        val last = lastPublishedTag?.trim()?.takeIf { it.isNotEmpty() }?.let(SemVer::parseTag) ?: SemVer.ZERO
        return if (version > last) {
            emptyList()
        } else {
            listOf("La versión $version no es mayor que la última publicada ($last) (S001/RF-33).")
        }
    }

    /** Firma disponible: todas las variables con valor y el almacén de claves existe (S001/RF-32). */
    fun checkSigning(env: Map<String, String?>, fileExists: (String) -> Boolean): List<String> {
        val missing = SIGNING_VARIABLES.filter { env[it].isNullOrBlank() }
        if (missing.isNotEmpty()) {
            return listOf("Falta la firma de publicación; variables sin valor: ${missing.joinToString()} (S001/RF-32).")
        }
        val keystore = env.getValue("TOPOPHONE_KEYSTORE_FILE")!!
        return if (fileExists(keystore)) {
            emptyList()
        } else {
            listOf("El almacén de claves de TOPOPHONE_KEYSTORE_FILE no existe (S001/RF-32).")
        }
    }

    /** Huella SHA-256 del primer firmante según `apksigner verify --print-certs`, normalizada. */
    fun parseApksignerSha256(output: String): String? = APKSIGNER_SHA256.find(output)?.groupValues?.get(1)?.let(::normalizeFingerprint)

    /** Minúsculas, sin `:`, espacios ni prefijo `SHA256:` (formato de keytool). */
    fun normalizeFingerprint(raw: String): String =
        raw.trim().removePrefix("SHA256:").removePrefix("SHA-256:").replace(":", "").replace(Regex("\\s"), "").lowercase()

    /** El APK está firmado con el certificado registrado en el repositorio (S001/RF-34). */
    fun checkCertificate(expectedFileContent: String?, apksignerOutput: String): List<String> {
        val expected = expectedFileContent?.let(::normalizeFingerprint)?.takeIf { it.isNotEmpty() }
            ?: return listOf("No hay huella registrada en release/certificado-sha256.txt (S001/RF-34).")
        val actual = parseApksignerSha256(apksignerOutput)
            ?: return listOf("apksigner no devolvió la huella del certificado: el APK no está firmado (S001/RF-34).")
        return if (actual == expected) {
            emptyList()
        } else {
            listOf("La huella del certificado del APK ($actual) no coincide con la registrada ($expected) (S001/RF-34).")
        }
    }
}
