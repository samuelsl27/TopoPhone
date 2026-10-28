package es.topophone.core.formats.raster

import es.topophone.core.formats.gpkg.GpkgCrsCatalog
import es.topophone.core.model.cad.CadSourceCrs
import java.io.IOException

/**
 * El punto de entrada para leer un ráster de cotas (S045/RF-2): reconoce el formato por el contenido y no por la extensión, y delega
 * en [GeoTiffRasterReader] o en [AsciiGridReader].
 */
object RasterReader {
    private const val SNIFF_BYTES = 16

    /** El formato de [source] por sus primeros bytes, o null si no parece ninguno. */
    fun detect(source: RasterSource): RasterFormat? {
        if (source.size == 0L) return null
        val head = try {
            source.readBytes(0, minOf(source.size, SNIFF_BYTES.toLong()).toInt())
        } catch (e: IOException) {
            return null
        }
        if (isTiffHeader(head)) return RasterFormat.GEOTIFF
        return if (AsciiGridReader.looksLikeAsciiGrid(head)) RasterFormat.ASCII_GRID else null
    }

    private fun isTiffHeader(head: ByteArray): Boolean {
        if (head.size < 4) return false
        val little =
            head[0] == 'I'.code.toByte() &&
                head[1] == 'I'.code.toByte() &&
                head[3] == 0.toByte() &&
                (head[2] == 42.toByte() || head[2] == 43.toByte())
        val big =
            head[0] == 'M'.code.toByte() &&
                head[1] == 'M'.code.toByte() &&
                head[2] == 0.toByte() &&
                (head[3] == 42.toByte() || head[3] == 43.toByte())
        return little || big
    }

    fun scan(source: RasterSource): RasterScan {
        if (source.size == 0L) return RasterScan.Rejected(RasterRejection.EMPTY)
        if (source.size > RasterLimits.MAX_FILE_BYTES) return RasterScan.Rejected(RasterRejection.TOO_LARGE)
        return when (detect(source)) {
            RasterFormat.GEOTIFF -> GeoTiffRasterReader.scan(source)
            RasterFormat.ASCII_GRID -> AsciiGridReader.scan(source)
            null -> RasterScan.Rejected(RasterRejection.NOT_A_RASTER)
        }
    }

    /** Las filas de [source] por bandas; [RasterFormatException] si no es un ráster legible. */
    fun readBands(source: RasterSource, sink: RasterSink, bandRows: Int = RasterLimits.BAND_ROWS): RasterReadStats = when (detect(source)) {
        RasterFormat.GEOTIFF -> GeoTiffRasterReader.readBands(source, sink, bandRows)
        RasterFormat.ASCII_GRID -> AsciiGridReader.readBands(source, sink, bandRows)
        null -> throw RasterFormatException("El fichero no es un ráster")
    }
}

/** Lo que dice un fichero de su sistema de coordenadas (S045/RF-8). */
sealed interface RasterCrsDeclaration {
    /** Uno de los sistemas de la app (S008); [wgs84] es EPSG:4326, que se trata como ETRS89. */
    data class Supported(val epsg: Int, val source: CadSourceCrs, val wgs84: Boolean) : RasterCrsDeclaration

    /** Un código EPSG que la app no ofrece: el usuario tiene que elegir otro sistema. */
    data class Unavailable(val epsg: Int) : RasterCrsDeclaration

    /** El fichero no declara sistema. */
    data object Undeclared : RasterCrsDeclaration
}

/** Del código EPSG de un GeoTIFF al sistema de la app, con la misma tabla que usa S048 para los GeoPackage (S045/RF-8). */
object RasterCrsResolver {
    fun resolve(epsg: Int?): RasterCrsDeclaration {
        if (epsg == null) return RasterCrsDeclaration.Undeclared
        val known = GpkgCrsCatalog.resolveEpsg(epsg) ?: return RasterCrsDeclaration.Unavailable(epsg)
        return RasterCrsDeclaration.Supported(known.epsg, known.source, known.wgs84)
    }

    /** Los sistemas que el usuario puede elegir para el ráster. */
    val choices: List<RasterCrsDeclaration.Supported> get() = GpkgCrsCatalog.choices.map {
        RasterCrsDeclaration.Supported(it.epsg, it.source, it.wgs84)
    }
}
