package es.topophone.core.geodesy.catalog

import es.topophone.core.model.project.CrsDefinition

/**
 * El WKT1 de un SC personalizado, que se escribe en el `.prj` de un Shapefile y en `gpkg_spatial_ref_sys` de un GeoPackage (S035/RF-23). Es el WKT1
 * «OGC» que leen QGIS, GDAL y pyproj: `PROJCS[…GEOGCS[…DATUM[…,SPHEROID[…],TOWGS84[…]]…]…]`. La plantilla es la de `tools/geodesia/personalizado_wkt.py`,
 * que comprueba contra PROJ que su lectura da las mismas coordenadas que los parámetros sueltos del SC (≤ 1 mm).
 *
 * `TOWGS84` va en posición vectorial: un Helmert de marco de coordenadas lleva las rotaciones con el signo cambiado y uno sin transformación, ceros. Una
 * transformación que WKT1 no expresa (rejilla, Molodensky-Badekas, cadena) deja el datum sin `TOWGS84` y [datumShiftExpressed] da `false`.
 */
object CustomCrsWkt {
    private const val DEGREE_IN_RADIANS = "0.0174532925199433"
    private const val GEOGRAPHIC_NAME_FALLBACK = "Personalizado"

    /** `null` si el método de proyección del SC no tiene forma en WKT1 (la estereográfica polar C). */
    fun wkt1(definition: CrsDefinition): String? {
        val geogcs = geogcs(definition)
        if (!definition.projected) return geogcs
        val (projection, parameters) = projection(definition) ?: return null
        val list = parameters.joinToString(",") { (name, value) -> "PARAMETER[\"$name\",$value]" }
        return "PROJCS[\"${clean(
            definition.name,
        )}\",$geogcs,PROJECTION[\"$projection\"],$list,UNIT[\"${unitName(definition.unitMetres)}\",${definition.unitMetres}]]"
    }

    /** ¿Lleva el WKT la transformación del datum (`TOWGS84`)? Con una rejilla, Molodensky-Badekas o una cadena no: quien exporta lo avisa. */
    fun datumShiftExpressed(definition: CrsDefinition): Boolean = towgs84(definition) != null

    private fun geogcs(d: CrsDefinition): String {
        val datum = clean(d.datumName.ifBlank { GEOGRAPHIC_NAME_FALLBACK })
        val shift = towgs84(d)?.let { ",TOWGS84[$it]" }.orEmpty()
        val primeMeridian = if (d.primeMeridianDeg == 0.0) "Greenwich" else "Custom"
        return "GEOGCS[\"$datum\",DATUM[\"$datum\",SPHEROID[\"$datum\",${d.ellipsoidSemiMajorM},${d.ellipsoidInverseFlattening}]$shift]," +
            "PRIMEM[\"$primeMeridian\",${d.primeMeridianDeg}],UNIT[\"degree\",$DEGREE_IN_RADIANS]]"
    }

    private fun towgs84(d: CrsDefinition): String? {
        val step = try {
            CrsCatalog.parseSteps(d.transformationSteps).singleOrNull()
        } catch (_: RuntimeException) {
            null
        } ?: return null
        return when (step) {
            StepSpec.Null -> listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0).joinToString(",")
            is StepSpec.Grid -> null
            is StepSpec.Helmert -> helmert(step)
        }
    }

    private fun helmert(step: StepSpec.Helmert): String? {
        if (step.inverse || step.pivotM != null) return null
        val sign = when (step.convention) {
            "PV" -> 1.0
            "CF" -> -1.0
            else -> return null
        }
        val (rx, ry, rz) = step.rotationArcSec
        val (tx, ty, tz) = step.translationM
        // `0.0 - x` y no `-x`: así un cero no sale como -0.0.
        val rotations = if (sign < 0) listOf(0.0 - rx, 0.0 - ry, 0.0 - rz) else listOf(rx, ry, rz)
        return (listOf(tx, ty, tz) + rotations + step.scalePpm).joinToString(",")
    }

    private fun projection(d: CrsDefinition): Pair<String, List<Pair<String, Double>>>? {
        val p = d.parameters
        fun v(key: String) = p.getValue(key)
        return when (d.method) {
            "tm" -> "Transverse_Mercator" to listOf(
                "latitude_of_origin" to v("latitudeOfOriginDeg"),
                "central_meridian" to v("longitudeOfOriginDeg"),
                "scale_factor" to v("scaleFactor"),
                "false_easting" to v("falseEasting"),
                "false_northing" to v("falseNorthing"),
            )
            "lcc1" -> "Lambert_Conformal_Conic_1SP" to listOf(
                "latitude_of_origin" to v("latitudeOfOriginDeg"),
                "central_meridian" to v("longitudeOfOriginDeg"),
                "scale_factor" to v("scaleFactor"),
                "false_easting" to v("falseEasting"),
                "false_northing" to v("falseNorthing"),
            )
            "lcc2" -> "Lambert_Conformal_Conic_2SP" to listOf(
                "standard_parallel_1" to v("standardParallel1Deg"),
                "standard_parallel_2" to v("standardParallel2Deg"),
                "latitude_of_origin" to v("latitudeOfFalseOriginDeg"),
                "central_meridian" to v("longitudeOfFalseOriginDeg"),
                "false_easting" to v("eastingAtFalseOrigin"),
                "false_northing" to v("northingAtFalseOrigin"),
            )
            "stereo" -> "Oblique_Stereographic" to listOf(
                "latitude_of_origin" to v("latitudeOfOriginDeg"),
                "central_meridian" to v("longitudeOfOriginDeg"),
                "scale_factor" to v("scaleFactor"),
                "false_easting" to v("falseEasting"),
                "false_northing" to v("falseNorthing"),
            )
            "omerca" -> "Hotine_Oblique_Mercator" to obliqueMercator(::v, "falseEasting", "falseNorthing")
            "omercb" -> "Hotine_Oblique_Mercator_Azimuth_Center" to obliqueMercator(::v, "eastingAtCentre", "northingAtCentre")
            else -> null
        }
    }

    private fun obliqueMercator(v: (String) -> Double, eastingKey: String, northingKey: String) = listOf(
        "latitude_of_center" to v("latitudeOfCentreDeg"),
        "longitude_of_center" to v("longitudeOfCentreDeg"),
        "azimuth" to v("azimuthDeg"),
        "rectified_grid_angle" to v("rectifiedGridAngleDeg"),
        "scale_factor" to v("scaleFactor"),
        "false_easting" to v(eastingKey),
        "false_northing" to v(northingKey),
    )

    private fun unitName(metres: Double): String = when (metres) {
        1.0 -> "metre"
        0.3048 -> "foot"
        else -> "unit"
    }

    /** Un nombre sin comillas ni saltos de línea, que romperían el WKT. */
    private fun clean(name: String): String = name.replace('"', '\'').replace('\n', ' ').replace('\r', ' ').trim()
}
