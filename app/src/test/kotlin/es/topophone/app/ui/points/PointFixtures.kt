package es.topophone.app.ui.points

import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.point.PrecisionFailure
import es.topophone.core.model.point.TypedCoordinates

/** Puntos de prueba en la Puerta del Sol (S011). */
object PointFixtures {
    val sol = PointCoordinates(GeodeticPosition(40.41668, -3.70371, 699.423), 440_297.988, 4_474_244.001, 648.311)

    fun measured(
        name: String,
        projectId: String = "p1",
        description: String = "arqueta",
        createdAtMs: Long = 1_000,
        failures: Set<PrecisionFailure> = emptySet(),
    ) = Point.measured(
        id = "id-$name",
        projectId = projectId,
        name = name,
        description = description,
        measurement = Measurement(
            antenna = GeodeticPosition(40.41668, -3.70371, 701.357),
            poleHeightM = 1.8,
            quality = PointQuality("FIX", 24, 0.6, 1.0, 0.009, 0.014, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
            rawNmea = "\$GNGGA,100000.00*00\n\$GNGST,100000.00*00",
            outOfTolerance = failures,
            measuredFromMs = createdAtMs,
            measuredToMs = createdAtMs,
            coordinates = sol,
        ),
        createdAtMs = createdAtMs,
    )

    fun manual(name: String, projectId: String = "p1", description: String = "esquina", createdAtMs: Long = 2_000, height: Double? = null) =
        Point(
            id = "id-$name",
            projectId = projectId,
            name = name,
            description = description,
            origin = PointOrigin.MANUAL,
            coordinates = PointCoordinates(GeodeticPosition(40.4169, -3.7036, height?.let { it + 51.1 }), 440_310.0, 4_474_256.0, height),
            measurement = null,
            typed = TypedCoordinates(EntryForm.PROJECT_CRS, 440_310.0, 4_474_256.0, height, HeightKind.PROJECT),
            edited = false,
            hidden = false,
            copyOf = null,
            createdAtMs = createdAtMs,
            deletedAtMs = null,
        )
}
