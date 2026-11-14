package es.topophone.app.ui.offline

import es.topophone.core.model.map.Basemap
import es.topophone.core.model.project.CentralPoint
import es.topophone.data.wms.ProjectLayerView

/** Lo que el plan de una zona lee del proyecto, para los tests (S037/RF-2, RF-5, RF-6, RF-8). */
class FakeZoneInputs(
    var center: CentralPoint? = CentralPoint(40.4, -3.7),
    var points: List<CentralPoint> = emptyList(),
    var layers: List<ProjectLayerView> = emptyList(),
    var basemap: Basemap = Basemap.IGN_VECTOR,
) : ZoneInputs {
    override suspend fun center(projectId: String): CentralPoint? = center

    override suspend fun points(projectId: String): List<CentralPoint> = points

    override suspend fun wmsLayers(projectId: String): List<ProjectLayerView> = layers

    override suspend fun basemap(): Basemap = basemap
}
