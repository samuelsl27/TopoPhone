package es.topophone.core.formats.gpkg

import es.topophone.core.formats.csv.BaseProjection
import es.topophone.core.formats.csv.CsvExport
import es.topophone.core.formats.csv.CsvOptions
import es.topophone.core.formats.csv.ExportColumn
import es.topophone.core.model.point.AppliedShift
import es.topophone.core.model.point.BaseShiftVector
import es.topophone.core.model.point.BaseSource
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointBase
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.point.PointTilt
import es.topophone.core.model.point.PoleVector
import es.topophone.core.model.point.PrecisionFailure
import es.topophone.core.model.point.StakeoutRef
import es.topophone.core.model.point.TiltAngles
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Los atributos de la capa de puntos de la exportación a GeoPackage: su tipo y su valor sin redondear (S053/RF-4, RF-5). */
class GpkgPointAttributesTest {

    private val project = Project(
        id = "p1",
        name = "Calle Mayor 12",
        author = "",
        description = "",
        crs = ProjectCrs.ETRS89_UTM30,
        linearUnit = LinearUnit.METRE,
        angularUnit = AngularUnit.DMS,
        correctionsDatum = CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 0,
        modifiedAtMs = 0,
        openedAtMs = null,
        deletedAtMs = null,
        heightSystem = HeightSystem.ORTHOMETRIC,
    )

    private val base = BaseProjection(266_134.9877123, 4_826_201.1235456, 26.0123789)

    /** Un punto replanteado con base, inclinación y todo lo que puede llevar. */
    private val full: Point = Point.measured(
        id = "id-4",
        projectId = "p1",
        name = "4_stkd",
        description = "Pozo, \"nuevo\"",
        measurement = Measurement(
            antenna = GeodeticPosition(40.416815094, -3.703533016, 701.357),
            poleHeightM = 1.8,
            quality = PointQuality(
                "FLOAT", 24, 0.6, 1.0, 0.1382931668593933, 0.21, MeasurementMode.AVERAGED, 5, 2, fromReplay = true,
                averagedTimeS = 30.0, deviationHorizontalM = 0.0123456789, deviationVerticalM = 0.0234567891, auto = true,
            ),
            rawNmea = "\$GNGGA,1*00",
            outOfTolerance = setOf(PrecisionFailure.NOT_FIX),
            measuredFromMs = 1_792_346_614_419,
            measuredToMs = 1_792_346_639_419,
            coordinates = PointCoordinates(
                GeodeticPosition(40.416815094123456, -3.703533016654321, 699.423123456),
                440_313.1221195372,
                4_474_258.876880059,
                648.3113666754832,
            ),
            base = PointBase(GeodeticPosition(43.5578666032, -5.9130704113, 78.4338), BaseSource.CORRECTIONS, 6, 0.0, 382_123.4567),
            tilt = PointTilt(
                TiltMode.APP,
                verified = true,
                angles = TiltAngles(
                    tiltRad = Math.toRadians(12.0),
                    directionRad = Math.toRadians(210.5),
                    headingRad = Math.toRadians(30.0),
                    maxTiltRad = Math.toRadians(14.25),
                    horizontalAccuracyM = 0.0027,
                    verticalAccuracyM = 0.002,
                    pole = PoleVector(0.0, 0.0, 1.0),
                ),
            ),
            shift = AppliedShift(
                "s1",
                "V-12",
                BaseShiftVector(-2.147247769869864, -1.9653960830764845, 0.516280195210129),
                GeodeticPosition(40.4, -3.7, 699.0),
            ),
        ),
        createdAtMs = 1_792_346_639_419,
        staked = StakeoutRef(
            designId = "d1",
            designName = "P1",
            design = PointCoordinates(GeodeticPosition(40.4168, -3.7035, 699.4), 440_313.1, 4_474_258.9, 648.2914),
            dEastM = 0.0166,
            dNorthM = -0.01,
            dHorizontalM = 0.019337,
            dHeightM = 0.02,
            designElevationM = 648.2914,
            toleranceHM = 0.025,
            toleranceVM = 0.03,
            withinTolerance = true,
        ),
    ).copy(edited = true, copyOf = "4")

    /** Un punto manual sin medida, sin altura y oculto. */
    private val manual = Point(
        id = "id-E1",
        projectId = "p1",
        name = "E1",
        description = "",
        origin = PointOrigin.MANUAL,
        coordinates = PointCoordinates(GeodeticPosition(40.4168, -3.7036, null), 440_300.0, 4_474_250.0, null),
        measurement = null,
        typed = null,
        edited = false,
        hidden = true,
        copyOf = null,
        createdAtMs = 1_792_346_700_000,
        deletedAtMs = null,
    )

    private fun value(point: Point, column: ExportColumn) = GpkgPointAttributes.value(point, column, project, base)

    @Test
    fun `S053 RF-4 cada columna de Todas las columnas tiene su tipo y los de siempre son los esperados`() {
        ExportColumn.entries.forEach { GpkgPointAttributes.typeOf(it) }

        GpkgPointAttributes.typeOf(ExportColumn.NAME) shouldBe GpkgAttributeType.TEXT
        GpkgPointAttributes.typeOf(ExportColumn.MEASURED_FROM) shouldBe GpkgAttributeType.TEXT
        GpkgPointAttributes.typeOf(ExportColumn.SATELLITES) shouldBe GpkgAttributeType.INTEGER
        GpkgPointAttributes.typeOf(ExportColumn.EPOCHS_USED) shouldBe GpkgAttributeType.INTEGER
        GpkgPointAttributes.typeOf(ExportColumn.EASTING) shouldBe GpkgAttributeType.REAL
        GpkgPointAttributes.typeOf(ExportColumn.LATITUDE) shouldBe GpkgAttributeType.REAL
        GpkgPointAttributes.typeOf(ExportColumn.REPLAY) shouldBe GpkgAttributeType.BOOLEAN
        GpkgPointAttributes.typeOf(ExportColumn.STAKE_WITHIN) shouldBe GpkgAttributeType.BOOLEAN
    }

    @Test
    fun `S053 RF-4 el valor de cada columna de un punto completo es del tipo que declara`() {
        for (column in ExportColumn.entries) {
            val v = value(full, column)
            val ok = when (GpkgPointAttributes.typeOf(column)) {
                GpkgAttributeType.TEXT -> v is String
                GpkgAttributeType.INTEGER -> v is Long
                GpkgAttributeType.REAL -> v is Double
                GpkgAttributeType.BOOLEAN -> v is Boolean
            }
            check(ok) { "${column.header}: ${v?.javaClass} no es ${GpkgPointAttributes.typeOf(column)}" }
        }
    }

    @Test
    fun `S020 RF-13 el vector del base shift es REAL, en metros y sin redondear`() {
        listOf(ExportColumn.SHIFT_DX, ExportColumn.SHIFT_DY, ExportColumn.SHIFT_DZ).forEach {
            GpkgPointAttributes.typeOf(it) shouldBe GpkgAttributeType.REAL
        }
        value(full, ExportColumn.SHIFT_DX) shouldBe -2.147247769869864
        value(full, ExportColumn.SHIFT_DY) shouldBe -1.9653960830764845
        value(full, ExportColumn.SHIFT_DZ) shouldBe 0.516280195210129
        val feet = project.copy(linearUnit = LinearUnit.INTERNATIONAL_FOOT)
        GpkgPointAttributes.value(full, ExportColumn.SHIFT_DX, feet, base) shouldBe -2.147247769869864
    }

    @Test
    fun `S053 RF-5 las coordenadas y los demas decimales salen sin redondear`() {
        value(full, ExportColumn.EASTING) shouldBe 440_313.1221195372
        value(full, ExportColumn.NORTHING) shouldBe 4_474_258.876880059
        value(full, ExportColumn.ELEVATION) shouldBe 648.3113666754832
        value(full, ExportColumn.LATITUDE) shouldBe 40.416815094123456
        value(full, ExportColumn.LONGITUDE) shouldBe -3.703533016654321
        value(full, ExportColumn.ELLIPSOIDAL_HEIGHT) shouldBe 699.423123456
        value(full, ExportColumn.HORIZONTAL_ACCURACY) shouldBe 0.1382931668593933
        value(full, ExportColumn.DEVIATION_HORIZONTAL) shouldBe 0.0123456789
    }

    @Test
    fun `S053 RF-4 las longitudes van en metros y los angulos en grados sin pasar por la unidad del proyecto`() {
        val feet = project.copy(linearUnit = LinearUnit.INTERNATIONAL_FOOT)

        GpkgPointAttributes.value(full, ExportColumn.EASTING, feet, base) shouldBe 440_313.1221195372
        GpkgPointAttributes.value(full, ExportColumn.POLE_HEIGHT, feet, base) shouldBe 1.8
        GpkgPointAttributes.value(full, ExportColumn.UNITS, feet, base) shouldBe "ft" // la unidad del proyecto, como dato
        (value(full, ExportColumn.TILT) as Double) shouldBe (12.0 plusOrMinus 1e-12)
        (value(full, ExportColumn.TILT_DIRECTION) as Double) shouldBe (210.5 plusOrMinus 1e-12)
    }

    @Test
    fun `S053 RF-4 un punto manual deja vacios los atributos que no tiene`() {
        val empty = listOf(
            ExportColumn.ELEVATION, ExportColumn.ELLIPSOIDAL_HEIGHT, ExportColumn.POLE_HEIGHT, ExportColumn.SOLUTION,
            ExportColumn.SATELLITES, ExportColumn.HDOP, ExportColumn.HORIZONTAL_ACCURACY, ExportColumn.MODE, ExportColumn.EPOCHS_USED,
            ExportColumn.REPLAY, ExportColumn.OUT_OF_TOLERANCE, ExportColumn.MEASURED_FROM, ExportColumn.BASE_LATITUDE,
            ExportColumn.TILT_MODE, ExportColumn.TILT, ExportColumn.AUTOMATIC, ExportColumn.COPY_OF, ExportColumn.STAKE_DESIGN,
            ExportColumn.STAKE_WITHIN, ExportColumn.SHIFT_DX, ExportColumn.SHIFT_DY, ExportColumn.SHIFT_DZ,
        )

        empty.forEach { value(manual, it).shouldBeNull() }
        value(manual, ExportColumn.NAME) shouldBe "E1"
        value(manual, ExportColumn.ORIGIN) shouldBe "Manual"
        value(manual, ExportColumn.EDITED) shouldBe false
        value(manual, ExportColumn.EASTING) shouldBe 440_300.0
    }

    @Test
    fun `S053 RF-4 la base proyectada sale de lo que calcula quien exporta y sin ella va vacia`() {
        value(full, ExportColumn.BASE_EASTING) shouldBe 266_134.9877123
        value(full, ExportColumn.BASE_ELEVATION) shouldBe 26.0123789
        GpkgPointAttributes.value(full, ExportColumn.BASE_EASTING, project, null).shouldBeNull()
        value(full, ExportColumn.BASE_LATITUDE) shouldBe 43.5578666032
    }

    @Test
    fun `S053 RF-4 los textos de la lista de fallos y las fechas son los del CSV`() {
        value(full, ExportColumn.OUT_OF_TOLERANCE) shouldBe "NOT_FIX"
        value(full, ExportColumn.MEASURED_FROM) shouldBe "2026-10-18T18:03:34Z"
        value(full, ExportColumn.CRS) shouldBe "EPSG:25830"
        value(full, ExportColumn.HEIGHT_SYSTEM) shouldBe "EGM08-REDNAP"
        value(full, ExportColumn.ORIGIN) shouldBe "Measured"
    }

    @Test
    fun `S053 RF-4 cada valor coincide con la celda del CSV del mismo punto en la resolucion del CSV`() {
        for (point in listOf(full, manual)) {
            val out = StringBuilder()
            CsvExport.write(sequenceOf(point), project, CsvOptions(), out) {
                BaseProjection(266_134.9877123, 4_826_201.1235456, 26.0123789)
            }
            val lines = out.toString().removeSuffix("\r\n").split("\r\n")
            val cells = splitCsv(lines[1])
            ExportColumn.entries.forEachIndexed { index, column ->
                val cell = cells[index]
                val v = value(point, column)
                when (GpkgPointAttributes.typeOf(column)) {
                    GpkgAttributeType.TEXT -> (v ?: "") shouldBe cell
                    GpkgAttributeType.INTEGER -> (v?.toString() ?: "") shouldBe cell
                    GpkgAttributeType.BOOLEAN -> (v?.toString() ?: "") shouldBe cell
                    GpkgAttributeType.REAL ->
                        if (cell.isEmpty()) v.shouldBeNull() else (v as Double) shouldBe (cell.toDouble() plusOrMinus tolerance(column))
                }
            }
        }
    }

    /** Lo que el CSV redondea: 9 decimales de grado, 4 de longitud, 3 de ángulo y 1 de HDOP, edad y tiempo. */
    private fun tolerance(column: ExportColumn): Double = when (column) {
        ExportColumn.LATITUDE, ExportColumn.LONGITUDE, ExportColumn.BASE_LATITUDE, ExportColumn.BASE_LONGITUDE -> 5.1e-10
        ExportColumn.HDOP, ExportColumn.CORRECTION_AGE, ExportColumn.AVERAGED_TIME -> 0.0501
        ExportColumn.TILT, ExportColumn.TILT_DIRECTION, ExportColumn.HEADING, ExportColumn.MAX_TILT -> 0.00051
        else -> 0.00005001
    }

    /** Las celdas de una fila del CSV de los tests, con el RFC 4180 de las comillas dobles. */
    private fun splitCsv(line: String): List<String> {
        val cells = ArrayList<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && line.getOrNull(i + 1) == '"' -> {
                    current.append('"')
                    i++
                }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> {
                    cells += current.toString()
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        cells += current.toString()
        return cells
    }
}
