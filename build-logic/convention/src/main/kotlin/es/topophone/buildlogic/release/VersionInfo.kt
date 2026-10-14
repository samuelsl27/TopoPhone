package es.topophone.buildlogic.release

/**
 * Versión semántica de TopoPhone, derivada de las etiquetas git `vX.Y.Z` (ADR-20).
 * Y y Z van de 0 a 99 para que el versionCode sea único y creciente (S001/RF-30).
 */
data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {

    /** versionCode de Android: X·10000 + Y·100 + Z (S001/RF-30). */
    val versionCode: Int get() = major * 10_000 + minor * 100 + patch

    override fun compareTo(other: SemVer): Int = compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        val ZERO = SemVer(0, 0, 0)

        private const val MAX_MAJOR = 209_999 // versionCode ≤ 2_100_000_000 (límite de Android)
        private val TAG_FORMAT = Regex("""^v(0|[1-9]\d*)\.(0|[1-9]\d?)\.(0|[1-9]\d?)$""")

        /** Devuelve la versión de una etiqueta `vX.Y.Z` o `null` si el formato no es válido (S001/RF-31). */
        fun parseTag(tag: String): SemVer? {
            val match = TAG_FORMAT.matchEntire(tag) ?: return null
            val (major, minor, patch) = match.destructured
            val majorValue = major.toIntOrNull()?.takeIf { it <= MAX_MAJOR } ?: return null
            return SemVer(majorValue, minor.toInt(), patch.toInt())
        }
    }
}

/** Versión que lleva el APK: de publicación si el commit tiene una etiqueta válida; si no, `-dev` (S001/RF-21). */
data class VersionInfo(val versionName: String, val versionCode: Int, val isRelease: Boolean) {

    companion object {
        private const val DEV_SUFFIX = "-dev"

        /**
         * @param exactTag etiqueta que apunta exactamente al commit compilado (`git describe --exact-match`), o `null`.
         * @param allTags todas las etiquetas del repositorio que empiezan por `v`.
         */
        fun from(exactTag: String?, allTags: List<String>): VersionInfo {
            val release = exactTag?.let(SemVer::parseTag)
            if (release != null) {
                return VersionInfo(release.toString(), release.versionCode, isRelease = true)
            }
            val latest = allTags.mapNotNull(SemVer::parseTag).maxOrNull() ?: SemVer.ZERO
            return VersionInfo("$latest$DEV_SUFFIX", maxOf(latest.versionCode, 1), isRelease = false)
        }
    }
}

/** Identificador corto del commit compilado (S001/RF-20). */
object GitCommit {
    private const val SHORT_LENGTH = 12
    const val UNKNOWN = "unknown"

    fun normalize(rawHash: String?): String {
        val hash = rawHash?.trim().orEmpty()
        return if (hash.isEmpty()) UNKNOWN else hash.take(SHORT_LENGTH)
    }
}
