package es.topophone.core.formats.gpkg

import java.io.InputStream

/** Qué es un fichero que se quiere importar como GeoPackage (S048/RF-2 a RF-4). */
sealed interface GpkgProbe {
    /**
     * Un SQLite con la cabecera en regla. [declared] es que su `application_id` es el de un GeoPackage (`GPKG`, `GP10` o `GP11`);
     * si no, puede ser un GeoPackage de la versión 1.0, y `data` lo comprueba al abrirlo con las tablas `gpkg_*`.
     */
    data class Candidate(val declared: Boolean) : GpkgProbe

    /** Sin ni un byte. */
    data object Empty : GpkgProbe

    /** No empieza por la cabecera de SQLite. */
    data object NotSqlite : GpkgProbe

    /** Empieza por la cabecera de SQLite, pero está cortada o dice un tamaño de página imposible. */
    data object Damaged : GpkgProbe

    /** Un SQLite de más de [GpkgFileKind.MAX_BYTES]. */
    data object TooLarge : GpkgProbe
}

/**
 * Qué es un fichero por su contenido, no por su extensión ni su tipo MIME (S048/RF-2 a RF-4, RNF-T5), con los primeros
 * [HEAD_BYTES]: la cabecera `SQLite format 3\0`, el tamaño de página (bytes 16 y 17) y el `application_id` (bytes 68 a 71).
 * Que tenga las tablas de un GeoPackage lo comprueba `data` al abrirlo.
 */
object GpkgFileKind {
    /** La cabecera de un fichero SQLite. */
    const val HEAD_BYTES = 100

    /** El tamaño máximo de un GeoPackage (RF-4). */
    const val MAX_BYTES = 100L * 1024 * 1024

    private val MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    private const val PAGE_SIZE_OFFSET = 16
    private const val APPLICATION_ID_OFFSET = 68
    private val APPLICATION_IDS = setOf("GPKG", "GP10", "GP11")

    /** Los primeros [HEAD_BYTES] de un flujo. */
    fun readHead(stream: InputStream): ByteArray = stream.readNBytes(HEAD_BYTES)

    /** Qué es un fichero de [sizeBytes] cuyo principio es [head]. */
    fun probe(head: ByteArray, sizeBytes: Long): GpkgProbe {
        if (sizeBytes == 0L || head.isEmpty()) return GpkgProbe.Empty
        if (head.size < MAGIC.size || !MAGIC.indices.all { head[it] == MAGIC[it] }) return GpkgProbe.NotSqlite
        if (head.size < HEAD_BYTES || !validPageSize(head)) return GpkgProbe.Damaged
        if (sizeBytes > MAX_BYTES) return GpkgProbe.TooLarge
        val id = String(head, APPLICATION_ID_OFFSET, 4, Charsets.US_ASCII)
        return GpkgProbe.Candidate(declared = id in APPLICATION_IDS)
    }

    // Dos bytes en big endian: una potencia de 2 de 512 a 32768, o 1, que es 65536.
    private fun validPageSize(head: ByteArray): Boolean {
        val size = ((head[PAGE_SIZE_OFFSET].toInt() and 0xFF) shl 8) or (head[PAGE_SIZE_OFFSET + 1].toInt() and 0xFF)
        return size == 1 || (size in 512..32768 && size and (size - 1) == 0)
    }
}
