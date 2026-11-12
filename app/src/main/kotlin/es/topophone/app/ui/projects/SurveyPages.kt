package es.topophone.app.ui.projects

import androidx.compose.runtime.Composable
import es.topophone.app.ui.alignment.AlignmentImportActions
import es.topophone.app.ui.alignment.AlignmentImportScreen
import es.topophone.app.ui.alignment.AlignmentImportUiState
import es.topophone.app.ui.alignment.AlignmentPointActions
import es.topophone.app.ui.alignment.AlignmentPointScreen
import es.topophone.app.ui.alignment.AlignmentPointUiState
import es.topophone.app.ui.alignment.ManualAlignmentActions
import es.topophone.app.ui.alignment.ManualAlignmentScreen
import es.topophone.app.ui.alignment.ManualAlignmentUiState
import es.topophone.app.ui.alignment.ProfileCsvImportActions
import es.topophone.app.ui.alignment.ProfileCsvImportScreen
import es.topophone.app.ui.alignment.ProfileCsvUiState
import es.topophone.app.ui.baseshift.BaseShiftActions
import es.topophone.app.ui.baseshift.BaseShiftScreen
import es.topophone.app.ui.baseshift.BaseShiftUiState
import es.topophone.app.ui.cad.CadImportActions
import es.topophone.app.ui.cad.CadImportScreen
import es.topophone.app.ui.cad.CadImportUiState
import es.topophone.app.ui.export.DxfExportActions
import es.topophone.app.ui.export.DxfExportScreen
import es.topophone.app.ui.export.DxfExportUiState
import es.topophone.app.ui.export.ExportActions
import es.topophone.app.ui.export.ExportScreen
import es.topophone.app.ui.export.ExportUiState
import es.topophone.app.ui.export.GpkgExportActions
import es.topophone.app.ui.export.GpkgExportScreen
import es.topophone.app.ui.export.GpkgExportUiState
import es.topophone.app.ui.export.ShpExportActions
import es.topophone.app.ui.export.ShpExportScreen
import es.topophone.app.ui.export.ShpExportUiState
import es.topophone.app.ui.gpkg.GpkgImportActions
import es.topophone.app.ui.gpkg.GpkgImportScreen
import es.topophone.app.ui.gpkg.GpkgImportUiState
import es.topophone.app.ui.linear.LinearSurveysActions
import es.topophone.app.ui.linear.LinearSurveysScreen
import es.topophone.app.ui.linear.LinearSurveysUiState
import es.topophone.app.ui.points.ObjectListActions
import es.topophone.app.ui.points.ObjectListScreen
import es.topophone.app.ui.points.ObjectListUiState
import es.topophone.app.ui.points.PointDetailsActions
import es.topophone.app.ui.points.PointDetailsScreen
import es.topophone.app.ui.points.PointDetailsUiState
import es.topophone.app.ui.points.PointFormActions
import es.topophone.app.ui.points.PointFormScreen
import es.topophone.app.ui.points.PointFormUiState
import es.topophone.app.ui.points.PointImportActions
import es.topophone.app.ui.points.PointImportScreen
import es.topophone.app.ui.points.PointImportUiState
import es.topophone.app.ui.points.PointTrashActions
import es.topophone.app.ui.points.PointTrashScreen
import es.topophone.app.ui.points.PointTrashUiState
import es.topophone.app.ui.raster.RasterImportActions
import es.topophone.app.ui.raster.RasterImportScreen
import es.topophone.app.ui.raster.RasterImportUiState
import es.topophone.app.ui.section.SectionImportActions
import es.topophone.app.ui.section.SectionImportScreen
import es.topophone.app.ui.section.SectionImportUiState
import es.topophone.app.ui.section.SectionPointActions
import es.topophone.app.ui.section.SectionPointScreen
import es.topophone.app.ui.section.SectionPointUiState
import es.topophone.app.ui.section.SectionsActions
import es.topophone.app.ui.section.SectionsScreen
import es.topophone.app.ui.section.SectionsUiState
import es.topophone.app.ui.slope.SlopePointActions
import es.topophone.app.ui.slope.SlopePointScreen
import es.topophone.app.ui.slope.SlopePointUiState
import es.topophone.app.ui.slope.SlopesActions
import es.topophone.app.ui.slope.SlopesScreen
import es.topophone.app.ui.slope.SlopesUiState
import es.topophone.app.ui.stakeout.StakeoutReportActions
import es.topophone.app.ui.stakeout.StakeoutReportScreen
import es.topophone.app.ui.stakeout.StakeoutReportUiState
import es.topophone.app.ui.surface.SurfaceImportActions
import es.topophone.app.ui.surface.SurfaceImportScreen
import es.topophone.app.ui.surface.SurfaceImportUiState
import es.topophone.core.model.preferences.ThemeState

/** Pantallas de la sección Levantamiento (S006). */
sealed interface SurveyPage {
    /** Lista de proyectos (S006/RF-1). */
    data object List : SurveyPage

    /** Formulario de proyecto: nuevo si [projectId] es null (S006/RF-5, RF-14). */
    data class Form(val projectId: String?, val folderId: String?) : SurveyPage

    /** Vista de un proyecto (S006/RF-28). */
    data class View(
        val projectId: String,
        val stakeoutPointId: String? = null,
        val stakeoutQuery: String = "",
        val stakeoutLineId: String? = null,
    ) : SurveyPage

    /** Datos de un proyecto (S009/RF-5). */
    data class Info(val projectId: String) : SurveyPage

    /** Una carpeta abierta (S006/RF-20). */
    data class Folder(val folderId: String) : SurveyPage

    /** Papelera de proyectos (S006/RF-23). */
    data object Trash : SurveyPage

    /** Lista de objetos de un proyecto (S011/RF-1). */
    data class Objects(val projectId: String) : SurveyPage

    /** Ficha de un punto (S011/RF-5). */
    data class PointDetails(val projectId: String, val pointId: String) : SurveyPage

    /** Punto nuevo, o edición de uno si hay [pointId] (S011/RF-8, RF-16). */
    data class PointForm(val projectId: String, val pointId: String?) : SurveyPage

    /** Papelera de puntos de un proyecto (S011/RF-23). */
    data class PointTrash(val projectId: String) : SurveyPage

    /** Exportar CSV (S012/RF-1, RF-2). */
    data class Export(val projectId: String, val selection: Boolean) : SurveyPage

    /** Exportar GeoPackage (S053/RF-1, RF-2). */
    data class ExportGeoPackage(val projectId: String, val selection: Boolean) : SurveyPage

    /** Exportar DXF (S029/RF-1, RF-2). */
    data class ExportDxf(val projectId: String, val selection: Boolean) : SurveyPage

    /** Exportar Shapefile (S055/RF-1, RF-2). */
    data class ExportShp(val projectId: String, val selection: Boolean) : SurveyPage

    /** Informe de replanteo (S019/RF-17, RF-18). */
    data class StakeoutReport(val projectId: String, val selection: Boolean) : SurveyPage

    /** Importar puntos de un CSV o TXT: el proyecto y la dirección del fichero elegido (S028/RF-1). */
    data class ImportPoints(val projectId: String, val fileUri: String) : SurveyPage

    /** Importar un DXF: el proyecto y la dirección del fichero elegido (S040/RF-1). */
    data class ImportDxf(val projectId: String, val fileUri: String) : SurveyPage

    /** Importar una superficie de un LandXML: el proyecto y la dirección del fichero elegido (S042/RF-1). */
    data class ImportSurface(val projectId: String, val fileUri: String) : SurveyPage

    /** Importar un ráster de cotas: el proyecto y la dirección del fichero elegido (S045/RF-1). */
    data class ImportRaster(val projectId: String, val fileUri: String) : SurveyPage

    /** Importar un GeoPackage: el proyecto y la dirección del fichero elegido (S048/RF-1). */
    data class ImportGpkg(val projectId: String, val fileUri: String) : SurveyPage

    /** Importar un KML o KMZ: el proyecto y la dirección del fichero elegido (S031/RF-1). */
    data class ImportKml(val projectId: String, val fileUri: String) : SurveyPage

    /** Importar un Shapefile: el proyecto y los ficheros elegidos, un ZIP o varios sueltos (S030/RF-1). */
    data class ImportShp(val projectId: String, val fileUris: kotlin.collections.List<String>) :
        SurveyPage // `List` es una página de esta interfaz

    /** Importar los ejes de un LandXML: el proyecto y la dirección del fichero elegido (S049/RF-1). */
    data class ImportAlignment(val projectId: String, val fileUri: String) : SurveyPage

    /** Asociar a un eje la rasante de un CSV: el proyecto y la dirección del fichero elegido (S049/RF-9). */
    data class ImportProfileCsv(val projectId: String, val fileUri: String) : SurveyPage

    /** Definir un eje a mano en el proyecto (S049/RF-8). */
    data class DefineAlignment(val projectId: String) : SurveyPage

    /** El desplazamiento de base del proyecto (S020/RF-1). */
    data class BaseShift(val projectId: String) : SurveyPage

    /** Calcular un punto sobre un eje del proyecto (S049/RF-20). */
    data class AlignmentPoint(val projectId: String, val alignmentId: String) : SurveyPage

    /** Las secciones transversales y el peralte de un eje (S050/RF-1). */
    data class Sections(val projectId: String, val alignmentId: String) : SurveyPage

    /** Las tomas y los perfiles tomados de un proyecto (S052/RF-22). */
    data class LinearSurveys(val projectId: String) : SurveyPage

    /** Los taludes de un eje (S051/RF-1). */
    data class Slopes(val projectId: String, val alignmentId: String) : SurveyPage

    /** El punto de talud teórico de un eje (S051/RF-5 a RF-8). */
    data class SlopePoint(val projectId: String, val alignmentId: String) : SurveyPage

    /** Calcular un punto de la sección de un eje y ver la sección (S050/RF-9 a RF-15). */
    data class SectionPoint(val projectId: String, val alignmentId: String) : SurveyPage

    /** Importar las secciones de un CSV o un LandXML a un eje: [kind] es `CSV` o `LANDXML` (S050/RF-4, RF-5). */
    data class ImportSections(val projectId: String, val alignmentId: String, val kind: String, val fileUri: String) : SurveyPage
}

/** Lo que Levantamiento necesita de la navegación de la app (S006). */
class SurveyNavigation(
    val onNewProject: (folderId: String?) -> Unit = {},
    val onOpenProject: (String) -> Unit = {},
    val onOpenFolder: (String) -> Unit = {},
    /** Tras guardar un proyecto nuevo se abre en lugar del formulario (S006/RF-12). */
    val onProjectSaved: (String) -> Unit = {},
    val onEditProject: (String) -> Unit = {},
    val onOpenProjectInfo: (String) -> Unit = {},
    /** Los códigos de la biblioteca que lleva el proyecto, solo de consulta (S023/RF-20). */
    val onOpenProjectCodes: (String) -> Unit = {},
    /** Crear o editar un código del proyecto desde el recolector (S024/RF-5, RF-7). */
    val onNewProjectCode: (projectId: String) -> Unit = {},
    val onEditProjectCode: (projectId: String, codeId: String) -> Unit = { _, _ -> },
    val onOpenObjects: (projectId: String) -> Unit = {},
    /** El asistente «Línea desde puntos» (S025/RF-12). */
    val onLineFromPoints: (projectId: String) -> Unit = {},
    /** El ángulo entre tres puntos del proyecto (S026/RF-13). */
    val onThreePointAngle: (projectId: String) -> Unit = {},
    /** La ficha de una línea (S025/RF-22). */
    val onOpenLine: (projectId: String, lineId: String) -> Unit = { _, _ -> },
    /** El replanteo de una línea del proyecto, en la vista del proyecto (S027/RF-1). */
    val onStakeoutLine: (projectId: String, lineId: String) -> Unit = { _, _ -> },
    /** La información geométrica de una línea (S026/RF-1). */
    val onLineInformation: (projectId: String, lineId: String) -> Unit = { _, _ -> },
    val onOpenPoint: (projectId: String, pointId: String) -> Unit = { _, _ -> },
    val onNewPoint: (projectId: String) -> Unit = {},
    val onEditPoint: (projectId: String, pointId: String) -> Unit = { _, _ -> },
    val onOpenPointTrash: (projectId: String) -> Unit = {},
    /** El replanteo de un punto, en la vista del proyecto; [query] es la búsqueda de la lista de objetos (S018/RF-1, RF-3). */
    val onStakeout: (projectId: String, pointId: String, query: String) -> Unit = { _, _, _ -> },
    /** S013/RF-21: "Copias de seguridad" de Ajustes. */
    val onOpenBackups: () -> Unit = {},
    val onExport: (projectId: String, selection: Boolean) -> Unit = { _, _ -> },
    /** Exportar a GeoPackage todos los puntos de un proyecto o los seleccionados (S053/RF-1, RF-2). */
    val onExportGeoPackage: (projectId: String, selection: Boolean) -> Unit = { _, _ -> },
    /** Exportar a DXF todos los puntos de un proyecto o los seleccionados (S029/RF-1, RF-2). */
    val onExportDxf: (projectId: String, selection: Boolean) -> Unit = { _, _ -> },
    /** Exportar a Shapefile todos los puntos de un proyecto o los seleccionados (S055/RF-1, RF-2). */
    val onExportShp: (projectId: String, selection: Boolean) -> Unit = { _, _ -> },
    /** El informe de replanteo de un proyecto o de los puntos seleccionados (S019/RF-17, RF-18). */
    val onStakeoutReport: (projectId: String, selection: Boolean) -> Unit = { _, _ -> },
    /** El asistente de importación de puntos, con el fichero que eligió el usuario (S028/RF-1). */
    val onImportPoints: (projectId: String, fileUri: String) -> Unit = { _, _ -> },
    /** La pantalla de importación de un DXF, con el fichero que eligió el usuario (S040/RF-1). */
    val onImportDxf: (projectId: String, fileUri: String) -> Unit = { _, _ -> },
    /** La pantalla de importación de una superficie, con el fichero que eligió el usuario (S042/RF-1). */
    val onImportSurface: (projectId: String, fileUri: String) -> Unit = { _, _ -> },
    /** La pantalla de importación de un ráster de cotas, con el fichero que eligió el usuario (S045/RF-1). */
    val onImportRaster: (projectId: String, fileUri: String) -> Unit = { _, _ -> },
    /** La pantalla de importación de un GeoPackage, con el fichero que eligió el usuario (S048/RF-1). */
    val onImportGpkg: (projectId: String, fileUri: String) -> Unit = { _, _ -> },
    /** La pantalla de importación de un Shapefile, con los ficheros que eligió el usuario (S030/RF-1). */
    val onImportShp: (projectId: String, fileUris: List<String>) -> Unit = { _, _ -> },
    val onImportKml: (projectId: String, fileUri: String) -> Unit = { _, _ -> },
    /** La pantalla de importación de los ejes de un LandXML, con el fichero que eligió el usuario (S049/RF-1). */
    val onImportAlignment: (projectId: String, fileUri: String) -> Unit = { _, _ -> },
    /** La pantalla de la rasante de un CSV, con el fichero que eligió el usuario (S049/RF-9). */
    val onImportProfileCsv: (projectId: String, fileUri: String) -> Unit = { _, _ -> },
    /** La pantalla de definir un eje a mano (S049/RF-8). */
    val onDefineAlignment: (projectId: String) -> Unit = {},
    /** La pantalla del desplazamiento de base (S020/RF-1). */
    val onBaseShift: (projectId: String) -> Unit = {},
    /** La pantalla de calcular un punto sobre un eje (S049/RF-20). */
    val onAlignmentPoint: (projectId: String, alignmentId: String) -> Unit = { _, _ -> },
    /** La pantalla de las secciones y el peralte de un eje (S050/RF-1). */
    val onSections: (projectId: String, alignmentId: String) -> Unit = { _, _ -> },
    /** La pantalla de las tomas y los perfiles tomados (S052/RF-22). */
    val onLinearSurveys: (projectId: String) -> Unit = {},
    /** La pantalla de los taludes de un eje (S051/RF-1). */
    val onSlopes: (projectId: String, alignmentId: String) -> Unit = { _, _ -> },
    /** La pantalla del punto de talud teórico (S051/RF-5 a RF-8). */
    val onSlopePoint: (projectId: String, alignmentId: String) -> Unit = { _, _ -> },
    /** La pantalla de calcular un punto de la sección y verla (S050/RF-9 a RF-15). */
    val onSectionPoint: (projectId: String, alignmentId: String) -> Unit = { _, _ -> },
    /** La pantalla de importar secciones de un CSV o un LandXML, con el fichero elegido (S050/RF-4, RF-5). */
    val onImportSections: (projectId: String, alignmentId: String, kind: String, fileUri: String) -> Unit = { _, _, _, _ -> },
    val onOpenTrash: () -> Unit = {},
    /** El apartado "Inclinación" del receptor, desde el recolector (S016/RF-2, RF-18). */
    val onOpenTilt: () -> Unit = {},
    val onBack: () -> Unit = {},
)

/** Estado de las pantallas de Levantamiento para pintarlas sin ViewModel (tests y vistas previas). */
data class SurveyUiState(
    val list: ProjectListUiState = ProjectListUiState(),
    val form: ProjectFormUiState = ProjectFormUiState(loaded = true),
    val view: ProjectViewUiState = ProjectViewUiState(),
    val info: ProjectInfoUiState = ProjectInfoUiState(),
    val folder: ProjectFolderUiState = ProjectFolderUiState(),
    val trash: TrashUiState = TrashUiState(),
    val objects: ObjectListUiState = ObjectListUiState(),
    val point: PointDetailsUiState = PointDetailsUiState(),
    val pointForm: PointFormUiState = PointFormUiState(loaded = true),
    val pointTrash: PointTrashUiState = PointTrashUiState(loaded = true),
    val export: ExportUiState = ExportUiState(loaded = true),
    val gpkgExport: GpkgExportUiState = GpkgExportUiState(loaded = true),
    val dxfExport: DxfExportUiState = DxfExportUiState(loaded = true),
    val shpExport: ShpExportUiState = ShpExportUiState(loaded = true),
    val stakeoutReport: StakeoutReportUiState = StakeoutReportUiState(loaded = true),
    val import: PointImportUiState = PointImportUiState(),
    val cadImport: CadImportUiState = CadImportUiState(),
    val gpkgImport: GpkgImportUiState = GpkgImportUiState(),
    val alignmentImport: AlignmentImportUiState = AlignmentImportUiState(),
    val profileCsv: ProfileCsvUiState = ProfileCsvUiState(),
    val manualAlignment: ManualAlignmentUiState = ManualAlignmentUiState(),
    val alignmentPoint: AlignmentPointUiState = AlignmentPointUiState(),
    val surfaceImport: SurfaceImportUiState = SurfaceImportUiState(),
    val rasterImport: RasterImportUiState = RasterImportUiState(),
    val baseShift: BaseShiftUiState = BaseShiftUiState(),
    val sections: SectionsUiState = SectionsUiState(),
    val sectionImport: SectionImportUiState = SectionImportUiState(),
    val sectionPoint: SectionPointUiState = SectionPointUiState(),
    val slopes: SlopesUiState = SlopesUiState(),
    val linearSurveys: LinearSurveysUiState = LinearSurveysUiState(),
    val slopePoint: SlopePointUiState = SlopePointUiState(),
)

/** Pinta una pantalla de Levantamiento sin estado propio. */
@Composable
fun SurveyPageScreen(
    page: SurveyPage,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: SurveyUiState,
    navigation: SurveyNavigation,
) {
    when (page) {
        SurveyPage.List -> ProjectListScreen(
            themeState,
            onToggleSun,
            state.list,
            ProjectListActions(
                onNewProject = { navigation.onNewProject(null) },
                onOpenProject = navigation.onOpenProject,
                onOpenFolder = navigation.onOpenFolder,
                onEdit = navigation.onEditProject,
                onOpenTrash = navigation.onOpenTrash,
            ),
        )
        is SurveyPage.Form -> ProjectFormScreen(themeState, onToggleSun, state.form, ProjectFormActions(onBack = navigation.onBack))
        is SurveyPage.View -> ProjectViewScreen(
            themeState,
            onToggleSun,
            state.view,
            navigation.onBack,
            navigation.onEditProject,
            navigation.onOpenProjectInfo,
        )
        is SurveyPage.Info -> ProjectInfoScreen(themeState, onToggleSun, state.info, navigation.onBack)
        is SurveyPage.Folder -> ProjectFolderScreen(
            themeState,
            onToggleSun,
            state.folder,
            ProjectFolderActions(ProjectListActions(onOpenProject = navigation.onOpenProject), navigation.onNewProject, navigation.onBack),
        )
        SurveyPage.Trash -> TrashScreen(themeState, onToggleSun, state.trash, TrashActions(onBack = navigation.onBack))
        is SurveyPage.Objects -> ObjectListScreen(themeState, onToggleSun, state.objects, ObjectListActions(), navigation.onBack)
        is SurveyPage.PointDetails -> PointDetailsScreen(themeState, onToggleSun, state.point, PointDetailsActions(), navigation.onBack)
        is SurveyPage.PointForm -> PointFormScreen(themeState, onToggleSun, state.pointForm, PointFormActions(), navigation.onBack)
        is SurveyPage.PointTrash -> PointTrashScreen(themeState, onToggleSun, state.pointTrash, PointTrashActions(), navigation.onBack)
        is SurveyPage.Export -> ExportScreen(themeState, onToggleSun, state.export, ExportActions(), navigation.onBack)
        is SurveyPage.ExportGeoPackage -> GpkgExportScreen(
            themeState,
            onToggleSun,
            state.gpkgExport,
            GpkgExportActions(),
            navigation.onBack,
        )
        is SurveyPage.ExportDxf -> DxfExportScreen(themeState, onToggleSun, state.dxfExport, DxfExportActions(), navigation.onBack)
        is SurveyPage.ExportShp -> ShpExportScreen(themeState, onToggleSun, state.shpExport, ShpExportActions(), navigation.onBack)
        is SurveyPage.ImportPoints -> PointImportScreen(themeState, onToggleSun, state.import, PointImportActions(), navigation.onBack)
        is SurveyPage.ImportDxf -> CadImportScreen(themeState, onToggleSun, state.cadImport, CadImportActions(), navigation.onBack)
        is SurveyPage.ImportSurface ->
            SurfaceImportScreen(themeState, onToggleSun, state.surfaceImport, SurfaceImportActions(), navigation.onBack)
        is SurveyPage.ImportRaster ->
            RasterImportScreen(themeState, onToggleSun, state.rasterImport, RasterImportActions(), navigation.onBack)
        is SurveyPage.BaseShift -> BaseShiftScreen(themeState, onToggleSun, state.baseShift, BaseShiftActions(), navigation.onBack)
        is SurveyPage.ImportGpkg -> GpkgImportScreen(themeState, onToggleSun, state.gpkgImport, GpkgImportActions(), navigation.onBack)
        is SurveyPage.ImportShp -> GpkgImportScreen(themeState, onToggleSun, state.gpkgImport, GpkgImportActions(), navigation.onBack)
        is SurveyPage.ImportKml -> GpkgImportScreen(themeState, onToggleSun, state.gpkgImport, GpkgImportActions(), navigation.onBack)
        is SurveyPage.AlignmentPoint ->
            AlignmentPointScreen(themeState, onToggleSun, state.alignmentPoint, AlignmentPointActions(), navigation.onBack)
        is SurveyPage.Sections -> SectionsScreen(themeState, onToggleSun, state.sections, SectionsActions(), navigation.onBack)
        is SurveyPage.Slopes -> SlopesScreen(themeState, onToggleSun, state.slopes, SlopesActions(), navigation.onBack)
        is SurveyPage.LinearSurveys ->
            LinearSurveysScreen(themeState, onToggleSun, state.linearSurveys, LinearSurveysActions(), { false }, navigation.onBack)
        is SurveyPage.SlopePoint ->
            SlopePointScreen(themeState, onToggleSun, state.slopePoint, SlopePointActions(), navigation.onBack)
        is SurveyPage.SectionPoint ->
            SectionPointScreen(themeState, onToggleSun, state.sectionPoint, SectionPointActions(), navigation.onBack)
        is SurveyPage.ImportSections ->
            SectionImportScreen(themeState, onToggleSun, state.sectionImport, SectionImportActions(), navigation.onBack)
        is SurveyPage.DefineAlignment ->
            ManualAlignmentScreen(themeState, onToggleSun, state.manualAlignment, ManualAlignmentActions(), navigation.onBack)
        is SurveyPage.ImportProfileCsv ->
            ProfileCsvImportScreen(themeState, onToggleSun, state.profileCsv, ProfileCsvImportActions(), navigation.onBack)
        is SurveyPage.ImportAlignment ->
            AlignmentImportScreen(themeState, onToggleSun, state.alignmentImport, AlignmentImportActions(), navigation.onBack)
        is SurveyPage.StakeoutReport ->
            StakeoutReportScreen(themeState, onToggleSun, state.stakeoutReport, StakeoutReportActions(), navigation.onBack)
    }
}
