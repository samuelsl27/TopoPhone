package es.topophone.core.formats.shp

import es.topophone.core.formats.gpkg.GpkgCrs
import es.topophone.core.formats.gpkg.GpkgCrsCatalog
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.ProjectCrs
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Del WKT de un `.prj` al sistema de la app (S030/RF-8, RF-9). Por orden, y con el primero que acierta:
 * 1. la autoridad `AUTHORITY["EPSG", n]` de la raíz, con los códigos que ofrece la app;
 * 2. la estructura, no el nombre: QGIS y ArcGIS escriben el mismo sistema con nombres distintos y casi nunca con autoridad. Un sistema
 *    proyectado es UTM si es Transverse Mercator, con escala 0,9996, falso este 500.000, falso norte 0, latitud de origen 0, metros y un
 *    meridiano central de un huso (6·huso − 183), y su huso y la familia de su datum dan el [ProjectCrs]; uno geográfico, con grados y la
 *    familia del datum;
 * 3. WGS 84 geográfico, que se trata como ETRS89 y se avisa (como S048/RF-8);
 * 4. cualquier otro es «no disponible» con el nombre del `.prj`, y sin `.prj` o con un texto que no es un WKT, «sin sistema».
 * La familia del datum sale del nombre del datum o del sistema geográfico (ETRS89, REGCAN95, ED50 o WGS 84) y se confirma con su elipsoide.
 */
object ShpCrsCatalog {
    private const val EPSG = "EPSG"
    private const val PRJ = "PRJ"
    private const val DEGREE = 0.0174532925199433
    private const val TOLERANCE = 1e-6

    private enum class Family(val datum: GeodeticDatum?, val semiMajor: Double, val inverseFlattening: Double) {
        ETRS89(GeodeticDatum.ETRS89, 6378137.0, 298.257222101),
        REGCAN95(GeodeticDatum.REGCAN95, 6378137.0, 298.257222101),
        ED50(GeodeticDatum.ED50, 6378388.0, 297.0),
        WGS84(null, 6378137.0, 298.257223563),
    }

    /** El sistema que dice [prj], el texto de un `.prj`, o null si la capa no lo tiene. */
    fun resolve(prj: String?): GpkgCrs {
        val root = prj?.let(PrjReader::parse) ?: return GpkgCrs.Undeclared
        val name = root.text(0)
        if (root.keyword != "PROJCS" && root.keyword != "GEOGCS") return GpkgCrs.Unavailable(PRJ, 0, name)
        authority(root)?.let { code ->
            return GpkgCrsCatalog.resolveEpsg(code) ?: GpkgCrs.Unavailable(EPSG, code, name)
        }
        val crs = if (root.keyword == "PROJCS") projected(root) else geographic(root)
        return crs ?: GpkgCrs.Unavailable(PRJ, 0, name)
    }

    private fun authority(root: WktNode): Int? {
        val node = root.children.lastOrNull { it.keyword == "AUTHORITY" } ?: return null
        if (!node.text(0).equals(EPSG, ignoreCase = true)) return null
        return node.text(1)?.trim()?.toIntOrNull()
    }

    private fun projected(root: WktNode): GpkgCrs? {
        if (!isTransverseMercator(root.child("PROJECTION")?.text(0))) return null
        if (!near(root.parameter("scale_factor"), 0.9996) || !near(root.parameter("false_easting"), 500_000.0)) return null
        if (!near(root.parameter("false_northing"), 0.0) || !near(root.parameter("latitude_of_origin"), 0.0)) return null
        if (!near(root.children.lastOrNull { it.keyword == "UNIT" }?.number(1), 1.0)) return null
        val meridian = root.parameter("central_meridian") ?: return null
        val zone = (meridian + 183.0) / 6.0
        if (abs(zone - zone.roundToInt()) > TOLERANCE) return null
        val geographic = root.child("GEOGCS") ?: return null
        val family = family(geographic) ?: return null
        val crs = family.datum?.let { ProjectCrs.of(it, zone.roundToInt()) } ?: return null
        return GpkgCrsCatalog.supported(crs)
    }

    private fun geographic(root: WktNode): GpkgCrs? {
        if (!near(root.children.lastOrNull { it.keyword == "UNIT" }?.number(1), DEGREE, 1e-12)) return null
        return when (val family = family(root) ?: return null) {
            Family.WGS84 -> GpkgCrsCatalog.resolveEpsg(GpkgCrsCatalog.WGS84_EPSG)
            else -> ProjectCrs.of(family.datum ?: return null, null)?.let(GpkgCrsCatalog::supported)
        }
    }

    /** La familia del datum de un `GEOGCS`, por su nombre o el de su datum, si su elipsoide es el de esa familia. */
    private fun family(geographic: WktNode): Family? {
        val datum = geographic.child("DATUM")
        val names = listOfNotNull(datum?.text(0), geographic.text(0)).map(::normalized)
        val family = names.firstNotNullOfOrNull(::familyOf) ?: return null
        val spheroid = datum?.child("SPHEROID") ?: return null
        if (!near(spheroid.number(1), family.semiMajor, 1e-3) || !near(spheroid.number(2), family.inverseFlattening, 1e-4)) return null
        return family
    }

    private fun familyOf(name: String): Family? = when {
        "etrs" in name -> Family.ETRS89
        "regcan" in name || "canarias" in name -> Family.REGCAN95
        "european1950" in name || "ed1950" in name || "ed50" in name -> Family.ED50
        "wgs1984" in name || "wgs84" in name -> Family.WGS84
        else -> null
    }

    private fun isTransverseMercator(name: String?): Boolean = name?.let(::normalized) == "transversemercator"

    private fun normalized(name: String): String = name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun near(value: Double?, expected: Double, tolerance: Double = TOLERANCE): Boolean =
        value != null && abs(value - expected) <= tolerance
}
