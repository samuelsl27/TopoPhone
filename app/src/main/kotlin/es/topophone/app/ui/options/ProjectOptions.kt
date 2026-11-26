package es.topophone.app.ui.options

/** Los grupos de opciones del proyecto en la lista de Objetos: tres menús de grupo y el «⋮» (S059/RF-1, RF-6). */
enum class OptionGroup { IMPORT, EXPORT, TOOLS, MORE }

/**
 * Cada opción del proyecto, en el orden en que sale en su menú (S059/RF-2 a RF-6). La pantalla y los tests parten de esta lista: añadir
 * una opción sin cambiar la spec hace fallar un test. [key] es el nombre estable de la opción.
 */
enum class ProjectOption(val group: OptionGroup, val key: String) {
    IMPORT_POINTS(OptionGroup.IMPORT, "import_points"),
    IMPORT_CAD(OptionGroup.IMPORT, "import_cad"),
    IMPORT_SURFACE(OptionGroup.IMPORT, "import_surface"),
    IMPORT_RASTER(OptionGroup.IMPORT, "import_raster"),
    IMPORT_GEOPACKAGE(OptionGroup.IMPORT, "import_geopackage"),
    IMPORT_SHAPEFILE(OptionGroup.IMPORT, "import_shapefile"),
    IMPORT_KML(OptionGroup.IMPORT, "import_kml"),
    IMPORT_ALIGNMENT(OptionGroup.IMPORT, "import_alignment"),

    EXPORT_POINTS(OptionGroup.EXPORT, "export_points"),
    EXPORT_GEOPACKAGE(OptionGroup.EXPORT, "export_geopackage"),
    EXPORT_CAD(OptionGroup.EXPORT, "export_cad"),
    EXPORT_SHAPEFILE(OptionGroup.EXPORT, "export_shapefile"),
    EXPORT_KML(OptionGroup.EXPORT, "export_kml"),
    EXPORT_PHOTOS(OptionGroup.EXPORT, "export_photos"),

    LINE_FROM_POINTS(OptionGroup.TOOLS, "line_from_points"),
    THREE_POINT_ANGLE(OptionGroup.TOOLS, "three_point_angle"),
    COGO(OptionGroup.TOOLS, "cogo"),
    BASE_SHIFT(OptionGroup.TOOLS, "base_shift"),
    LOCALIZATION(OptionGroup.TOOLS, "localization"),
    SURFACE_STAKE(OptionGroup.TOOLS, "surface_stake"), // S062/RF-3

    STAKEOUT_REPORT(OptionGroup.MORE, "stakeout_report"),
    POINT_TRASH(OptionGroup.MORE, "point_trash"),
}

object ProjectOptions {
    /** Lo máximo que cabe en un menú (S059/RNF específico, RNF-T15). */
    const val MAX_ROWS = 8

    /** Las opciones de un grupo, en su orden. */
    fun of(group: OptionGroup): List<ProjectOption> = ProjectOption.entries.filter { it.group == group }
}
