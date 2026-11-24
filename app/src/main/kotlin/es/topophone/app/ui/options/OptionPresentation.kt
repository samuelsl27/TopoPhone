package es.topophone.app.ui.options

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import es.topophone.app.R

/**
 * Cómo se presenta una opción: su icono, el nombre de su fila, la descripción de una línea (solo en «Herramientas», S059/RF-5) y la etiqueta
 * de TalkBack con el verbo y el objeto completos (S059/RF-8).
 */
class OptionPresentation(
    @DrawableRes val icon: Int,
    @StringRes val label: Int,
    @StringRes val accessibilityLabel: Int,
    @StringRes val description: Int? = null,
)

/** El nombre y el icono de cada botón de la barra de grupos (S059/RF-1). */
fun OptionGroup.labelRes(): Int = when (this) {
    OptionGroup.IMPORT -> R.string.option_group_import
    OptionGroup.EXPORT -> R.string.option_group_export
    OptionGroup.TOOLS -> R.string.option_group_tools
    OptionGroup.MORE -> R.string.projects_menu
}

@DrawableRes
fun OptionGroup.iconRes(): Int = when (this) {
    OptionGroup.IMPORT -> R.drawable.ic_opt_import
    OptionGroup.EXPORT -> R.drawable.ic_opt_export
    OptionGroup.TOOLS -> R.drawable.ic_opt_tools
    OptionGroup.MORE -> R.drawable.ic_more_vert
}

fun ProjectOption.presentation(): OptionPresentation = when (this) {
    ProjectOption.IMPORT_POINTS ->
        OptionPresentation(R.drawable.ic_opt_points, R.string.option_import_points, R.string.option_import_points_a11y)
    ProjectOption.IMPORT_CAD ->
        OptionPresentation(R.drawable.ic_drawings, R.string.option_import_cad, R.string.option_import_cad_a11y)
    ProjectOption.IMPORT_SURFACE ->
        OptionPresentation(R.drawable.ic_surface, R.string.option_import_surface, R.string.option_import_surface_a11y)
    ProjectOption.IMPORT_RASTER ->
        OptionPresentation(R.drawable.ic_opt_raster, R.string.option_import_raster, R.string.option_import_raster_a11y)
    ProjectOption.IMPORT_GEOPACKAGE ->
        OptionPresentation(R.drawable.ic_opt_package, R.string.option_import_geopackage, R.string.option_import_geopackage_a11y)
    ProjectOption.IMPORT_SHAPEFILE ->
        OptionPresentation(R.drawable.ic_opt_shape, R.string.option_import_shapefile, R.string.option_import_shapefile_a11y)
    ProjectOption.IMPORT_KML ->
        OptionPresentation(R.drawable.ic_opt_globe, R.string.option_import_kml, R.string.option_import_kml_a11y)
    ProjectOption.IMPORT_ALIGNMENT ->
        OptionPresentation(R.drawable.ic_alignment, R.string.option_import_alignment, R.string.option_import_alignment_a11y)
    ProjectOption.EXPORT_POINTS ->
        OptionPresentation(R.drawable.ic_opt_points, R.string.option_export_points, R.string.option_export_points_a11y)
    ProjectOption.EXPORT_GEOPACKAGE ->
        OptionPresentation(R.drawable.ic_opt_package, R.string.option_export_geopackage, R.string.option_export_geopackage_a11y)
    ProjectOption.EXPORT_CAD ->
        OptionPresentation(R.drawable.ic_drawings, R.string.option_export_cad, R.string.option_export_cad_a11y)
    ProjectOption.EXPORT_SHAPEFILE ->
        OptionPresentation(R.drawable.ic_opt_shape, R.string.option_export_shapefile, R.string.option_export_shapefile_a11y)
    ProjectOption.EXPORT_KML ->
        OptionPresentation(R.drawable.ic_opt_globe, R.string.option_export_kml, R.string.option_export_kml_a11y)
    ProjectOption.EXPORT_PHOTOS ->
        OptionPresentation(R.drawable.ic_photo, R.string.option_export_photos, R.string.option_export_photos_a11y)
    ProjectOption.LINE_FROM_POINTS -> OptionPresentation(
        R.drawable.ic_opt_line,
        R.string.option_line_from_points,
        R.string.option_line_from_points_a11y,
        R.string.option_line_from_points_desc,
    )
    ProjectOption.THREE_POINT_ANGLE -> OptionPresentation(
        R.drawable.ic_opt_angle,
        R.string.option_three_point_angle,
        R.string.option_three_point_angle_a11y,
        R.string.option_three_point_angle_desc,
    )
    ProjectOption.COGO ->
        OptionPresentation(R.drawable.ic_opt_calc, R.string.option_cogo, R.string.option_cogo_a11y, R.string.option_cogo_desc)
    ProjectOption.BASE_SHIFT -> OptionPresentation(
        R.drawable.ic_opt_shift,
        R.string.option_base_shift,
        R.string.option_base_shift_a11y,
        R.string.option_base_shift_desc,
    )
    ProjectOption.LOCALIZATION -> OptionPresentation(
        R.drawable.ic_opt_target,
        R.string.option_localization,
        R.string.option_localization_a11y,
        R.string.option_localization_desc,
    )
    ProjectOption.STAKEOUT_REPORT ->
        OptionPresentation(R.drawable.ic_opt_report, R.string.option_stakeout_report, R.string.option_stakeout_report_a11y)
    ProjectOption.POINT_TRASH ->
        OptionPresentation(R.drawable.ic_opt_trash, R.string.option_point_trash, R.string.option_point_trash_a11y)
}
