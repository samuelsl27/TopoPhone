package es.topophone.core.formats.dxf.export

import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.line.LineVertex
import es.topophone.core.model.line.ProjectLine
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs

/** Proyecto, puntos y líneas de los tests de la exportación a DXF (S029). */
object DxfTestData {
    fun project(crs: ProjectCrs = ProjectCrs.ETRS89_UTM30, unit: LinearUnit = LinearUnit.METRE) = Project(
        id = "p1",
        name = "Obra",
        author = "",
        description = "",
        crs = crs,
        linearUnit = unit,
        angularUnit = AngularUnit.DMS,
        correctionsDatum = CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 0,
        modifiedAtMs = 0,
        openedAtMs = null,
        deletedAtMs = null,
    )

    fun code(code: String, color: Int) = PointCode(code, "", color, CodeSymbol.CIRCLE)

    /** Un punto con sus coordenadas locales en metros (`east`, `north`, `height`) y las globales (`lat`, `lon`, `ellipsoidal`). */
    @Suppress("LongParameterList")
    fun point(
        name: String,
        east: Double? = 440_000.0,
        north: Double? = 4_474_000.0,
        height: Double? = 650.0,
        code: PointCode? = null,
        description: String = "",
        lat: Double = 40.0,
        lon: Double = -3.0,
        ellipsoidal: Double? = 700.0,
    ) = Point(
        id = "id-$name",
        projectId = "p1",
        name = name,
        description = description,
        origin = PointOrigin.MANUAL,
        coordinates = PointCoordinates(GeodeticPosition(lat, lon, ellipsoidal), east, north, height),
        measurement = null,
        typed = null,
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 0,
        deletedAtMs = null,
        code = code,
    )

    fun line(name: String, closed: Boolean, code: PointCode?, vararg points: Point) = ProjectLine(
        id = "line-$name",
        projectId = "p1",
        name = name,
        code = code,
        geometry = if (closed) CodeGeometry.POLYGON else CodeGeometry.LINE,
        closed = closed,
        hidden = false,
        active = false,
        vertices = points.map { LineVertex(it.id) },
        createdAtMs = 0,
    )
}
