package es.topophone.core.formats.shp.export

// Generado por tools/formatos/generar_shp_exportado.py --kotlin con pyproj 3.8.0 (PROJ 9.8.1); no editar a mano.
internal object ShpPrjDefinitionsData {
    /** El código EPSG, el nombre y el `.prj` (WKT1 de ESRI) de cada sistema de la exportación. */
    val ALL: List<Triple<Int, String, String>> = listOf(
        Triple(
            25829,
            "ETRS89 / UTM 29",
            "PROJCS[\"ETRS_1989_UTM_Zone_29N\",GEOGCS[\"GCS_ETRS_1989\",DATUM[\"D_ETRS_1989\",SPHEROID[\"GRS_1980\",63781" +
                "37.0,298.257222101]],PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]],PROJECTION[\"Transver" +
                "se_Mercator\"],PARAMETER[\"False_Easting\",500000.0],PARAMETER[\"False_Northing\",0.0],PARAMETER[\"Central" +
                "_Meridian\",-9.0],PARAMETER[\"Scale_Factor\",0.9996],PARAMETER[\"Latitude_Of_Origin\",0.0],UNIT[\"Meter\",1" +
                ".0]]",
        ),
        Triple(
            25830,
            "ETRS89 / UTM 30",
            "PROJCS[\"ETRS_1989_UTM_Zone_30N\",GEOGCS[\"GCS_ETRS_1989\",DATUM[\"D_ETRS_1989\",SPHEROID[\"GRS_1980\",63781" +
                "37.0,298.257222101]],PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]],PROJECTION[\"Transver" +
                "se_Mercator\"],PARAMETER[\"False_Easting\",500000.0],PARAMETER[\"False_Northing\",0.0],PARAMETER[\"Central" +
                "_Meridian\",-3.0],PARAMETER[\"Scale_Factor\",0.9996],PARAMETER[\"Latitude_Of_Origin\",0.0],UNIT[\"Meter\",1" +
                ".0]]",
        ),
        Triple(
            25831,
            "ETRS89 / UTM 31",
            "PROJCS[\"ETRS_1989_UTM_Zone_31N\",GEOGCS[\"GCS_ETRS_1989\",DATUM[\"D_ETRS_1989\",SPHEROID[\"GRS_1980\",63781" +
                "37.0,298.257222101]],PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]],PROJECTION[\"Transver" +
                "se_Mercator\"],PARAMETER[\"False_Easting\",500000.0],PARAMETER[\"False_Northing\",0.0],PARAMETER[\"Central" +
                "_Meridian\",3.0],PARAMETER[\"Scale_Factor\",0.9996],PARAMETER[\"Latitude_Of_Origin\",0.0],UNIT[\"Meter\",1." +
                "0]]",
        ),
        Triple(
            4258,
            "ETRS89 geográficas",
            "GEOGCS[\"GCS_ETRS_1989\",DATUM[\"D_ETRS_1989\",SPHEROID[\"GRS_1980\",6378137.0,298.257222101]],PRIMEM[\"Gre" +
                "enwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]",
        ),
        Triple(
            4082,
            "REGCAN95 / UTM 27",
            "PROJCS[\"REGCAN95_UTM_Zone_27N\",GEOGCS[\"GCS_REGCAN95\",DATUM[\"D_Red_Geodesica_de_Canarias_1995\",SPHERO" +
                "ID[\"GRS_1980\",6378137.0,298.257222101]],PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]],P" +
                "ROJECTION[\"Transverse_Mercator\"],PARAMETER[\"False_Easting\",500000.0],PARAMETER[\"False_Northing\",0.0]" +
                ",PARAMETER[\"Central_Meridian\",-21.0],PARAMETER[\"Scale_Factor\",0.9996],PARAMETER[\"Latitude_Of_Origin\"" +
                ",0.0],UNIT[\"Meter\",1.0]]",
        ),
        Triple(
            4083,
            "REGCAN95 / UTM 28",
            "PROJCS[\"REGCAN95_UTM_Zone_28N\",GEOGCS[\"GCS_REGCAN95\",DATUM[\"D_Red_Geodesica_de_Canarias_1995\",SPHERO" +
                "ID[\"GRS_1980\",6378137.0,298.257222101]],PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]],P" +
                "ROJECTION[\"Transverse_Mercator\"],PARAMETER[\"False_Easting\",500000.0],PARAMETER[\"False_Northing\",0.0]" +
                ",PARAMETER[\"Central_Meridian\",-15.0],PARAMETER[\"Scale_Factor\",0.9996],PARAMETER[\"Latitude_Of_Origin\"" +
                ",0.0],UNIT[\"Meter\",1.0]]",
        ),
        Triple(
            4081,
            "REGCAN95 geográficas",
            "GEOGCS[\"GCS_REGCAN95\",DATUM[\"D_Red_Geodesica_de_Canarias_1995\",SPHEROID[\"GRS_1980\",6378137.0,298.257" +
                "222101]],PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]",
        ),
        Triple(
            23029,
            "ED50 / UTM 29",
            "PROJCS[\"ED_1950_UTM_Zone_29N\",GEOGCS[\"GCS_European_1950\",DATUM[\"D_European_1950\",SPHEROID[\"Internati" +
                "onal_1924\",6378388.0,297.0]],PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]],PROJECTION[\"" +
                "Transverse_Mercator\"],PARAMETER[\"False_Easting\",500000.0],PARAMETER[\"False_Northing\",0.0],PARAMETER[" +
                "\"Central_Meridian\",-9.0],PARAMETER[\"Scale_Factor\",0.9996],PARAMETER[\"Latitude_Of_Origin\",0.0],UNIT[\"" +
                "Meter\",1.0]]",
        ),
        Triple(
            23030,
            "ED50 / UTM 30",
            "PROJCS[\"ED_1950_UTM_Zone_30N\",GEOGCS[\"GCS_European_1950\",DATUM[\"D_European_1950\",SPHEROID[\"Internati" +
                "onal_1924\",6378388.0,297.0]],PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]],PROJECTION[\"" +
                "Transverse_Mercator\"],PARAMETER[\"False_Easting\",500000.0],PARAMETER[\"False_Northing\",0.0],PARAMETER[" +
                "\"Central_Meridian\",-3.0],PARAMETER[\"Scale_Factor\",0.9996],PARAMETER[\"Latitude_Of_Origin\",0.0],UNIT[\"" +
                "Meter\",1.0]]",
        ),
        Triple(
            23031,
            "ED50 / UTM 31",
            "PROJCS[\"ED_1950_UTM_Zone_31N\",GEOGCS[\"GCS_European_1950\",DATUM[\"D_European_1950\",SPHEROID[\"Internati" +
                "onal_1924\",6378388.0,297.0]],PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]],PROJECTION[\"" +
                "Transverse_Mercator\"],PARAMETER[\"False_Easting\",500000.0],PARAMETER[\"False_Northing\",0.0],PARAMETER[" +
                "\"Central_Meridian\",3.0],PARAMETER[\"Scale_Factor\",0.9996],PARAMETER[\"Latitude_Of_Origin\",0.0],UNIT[\"M" +
                "eter\",1.0]]",
        ),
    )
}
