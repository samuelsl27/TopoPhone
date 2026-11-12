package es.topophone.core.formats.shp.export

import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointAttribute
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.line.LineVertex
import es.topophone.core.model.line.ProjectLine
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs

/** Un proyecto, unos puntos y unas líneas de ejemplo para los tests de la exportación a Shapefile (S055). */
object ShpExportSamples {
    fun project(
        crs: ProjectCrs = ProjectCrs.ETRS89_UTM30,
        heightSystem: HeightSystem = HeightSystem.ORTHOMETRIC,
        unit: LinearUnit = LinearUnit.METRE,
        name: String = "Calle Mayor 12",
    ) = Project(
        id = "p1",
        name = name,
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
        heightSystem = heightSystem,
    )

    /** Un punto manual con sus coordenadas: [easting], [northing] y [height] del proyecto y las geográficas con la altura elipsoidal [ellipsoidal]. */
    fun point(
        id: String,
        easting: Double? = 440_000.123456789,
        northing: Double? = 4_474_000.987654321,
        height: Double? = 648.25,
        latitude: Double = 40.4168,
        longitude: Double = -3.7038,
        ellipsoidal: Double? = 700.5,
        code: PointCode? = null,
        description: String = "",
    ) = Point(
        id = id,
        projectId = "p1",
        name = "P-$id",
        description = description,
        origin = PointOrigin.MANUAL,
        coordinates = PointCoordinates(GeodeticPosition(latitude, longitude, ellipsoidal), easting, northing, height),
        measurement = null,
        typed = null,
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 0,
        deletedAtMs = null,
        code = code,
    )

    /** Un punto medido en un instante: lleva su calidad y las fechas de la medida. */
    fun measured(id: String, satellites: Int = 24, fromReplay: Boolean = true): Point {
        val coordinates = PointCoordinates(GeodeticPosition(40.4168, -3.7038, 700.5), 440_000.5, 4_474_000.25, 648.0)
        val measurement = Measurement(
            antenna = GeodeticPosition(40.4168, -3.7038, 702.3),
            poleHeightM = 1.8,
            quality = PointQuality("FIX", satellites, 0.6, 1.0, 0.014, 0.021, MeasurementMode.INSTANT, 1, 0, fromReplay),
            rawNmea = "",
            outOfTolerance = emptySet(),
            measuredFromMs = 1_792_346_614_419,
            measuredToMs = 1_792_346_639_419,
            coordinates = coordinates,
        )
        return Point.measured(id, "p1", "M-$id", "medido", measurement, 1_792_346_639_419)
    }

    fun code(code: String, vararg attributes: PointAttribute) =
        PointCode(code, "descripción de $code", 0x1E88E5, CodeSymbol.CIRCLE, attributes.toList())

    fun attribute(name: String, type: AttributeType, value: String) = PointAttribute(name, type, value)

    fun line(id: String, vertices: List<String>, closed: Boolean = false, code: PointCode? = null, name: String = "L-$id") = ProjectLine(
        id = id,
        projectId = "p1",
        name = name,
        code = code,
        geometry = if (closed) es.topophone.core.model.codes.CodeGeometry.POLYGON else es.topophone.core.model.codes.CodeGeometry.LINE,
        closed = closed,
        hidden = false,
        active = false,
        vertices = vertices.map(::LineVertex),
        createdAtMs = 0,
    )
}
