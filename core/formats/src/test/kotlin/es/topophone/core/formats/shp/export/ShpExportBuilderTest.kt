package es.topophone.core.formats.shp.export

import es.topophone.core.formats.csv.BaseProjection
import es.topophone.core.formats.csv.ExportColumn
import es.topophone.core.formats.shp.export.ShpExportSamples.attribute
import es.topophone.core.formats.shp.export.ShpExportSamples.code
import es.topophone.core.formats.shp.export.ShpExportSamples.measured
import es.topophone.core.formats.shp.export.ShpExportSamples.point
import es.topophone.core.formats.shp.export.ShpExportSamples.project
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.point.BaseSource
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.PointBase
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** La preparación de la capa de puntos de la exportación a Shapefile: columnas, valores, geometría, sistema y resumen (S055/RF-1, RF-6, RF-8, RF-9, RF-12 a RF-14b, RF-22). */
class ShpExportBuilderTest {
    private val builder = ShpExportBuilder()

    private fun ShpExport.points(): ShpLayer = layers.single { it.kind == ShpLayerKind.POINTS }

    private fun ShpLayer.value(row: Int, name: String): Any? = rows[row][columns.indexOfFirst { it.name == name }]

    // ---------------------------------------------------------------------------------------------------------- las capas
    @Test
    fun `S055 RF-1 con puntos hay una capa de puntos PointZ con una fila y una geometria por punto`() {
        val export = builder.build(listOf(point("1"), point("2"), point("3")), project())

        export.layers.map { it.kind } shouldBe listOf(ShpLayerKind.POINTS)
        export.points().rows.size shouldBe 3
        export.points().geometries.size shouldBe 3
        ShpLayerKind.POINTS.type shouldBe ShpType.POINT_Z
        export.summary.points shouldBe 3
    }

    @Test
    fun `S055 RF-19 sin puntos no hay ninguna capa y el resumen esta a cero`() {
        val export = builder.build(emptyList(), project())

        export.isEmpty shouldBe true
        export.layers shouldBe emptyList()
        export.summary.points shouldBe 0
    }

    @Test
    fun `S055 RF-6 la capa lleva las columnas de Todas las columnas en su orden con el nombre abreviado del idioma`() {
        val es = builder.build(listOf(point("1")), project(), language = ShpNameLanguage.ES).points()
        val en = builder.build(listOf(point("1")), project(), language = ShpNameLanguage.EN).points()

        es.columns.map { it.name } shouldBe ExportColumn.entries.map { ShpColumnNames.of(it, ShpNameLanguage.ES) }
        en.columns.map { it.name } shouldBe ExportColumn.entries.map { ShpColumnNames.of(it, ShpNameLanguage.EN) }
        es.columns.first().name shouldBe "NOMBRE"
        en.columns.first().name shouldBe "NAME"
    }

    @Test
    fun `S055 RF-6 cada columna tiene su tipo y los booleanos son enteros`() {
        val columns = builder.build(listOf(point("1")), project()).points().columns.associate { it.name to it.type }

        columns["NOMBRE"] shouldBe DbfType.TEXT
        columns["CODIGO"] shouldBe DbfType.TEXT
        columns["MED_INICIO"] shouldBe DbfType.TEXT
        columns["SATELITES"] shouldBe DbfType.INTEGER
        columns["REPRODUC"] shouldBe DbfType.INTEGER // booleano: 0 o 1
        columns["EDITADO"] shouldBe DbfType.INTEGER
        columns["ESTE"] shouldBe DbfType.DECIMAL
        columns["HDOP"] shouldBe DbfType.DECIMAL
    }

    // --------------------------------------------------------------------------------------------------------- los valores
    @Test
    fun `S055 RF-6 los valores son los de S012 sin redondear y vacio lo que el punto no tiene`() {
        val layer = builder.build(listOf(point("1", description = "Pozo"), measured("2")), project()).points()

        layer.value(0, "NOMBRE") shouldBe "P-1"
        layer.value(0, "DESCRIP") shouldBe "Pozo"
        layer.value(0, "ESTE") shouldBe 440_000.123456789
        layer.value(0, "NORTE") shouldBe 4_474_000.987654321
        layer.value(0, "COTA") shouldBe 648.25
        layer.value(0, "LATITUD") shouldBe 40.4168
        layer.value(0, "LONGITUD") shouldBe -3.7038
        layer.value(0, "ALT_ELIP") shouldBe 700.5
        layer.value(0, "ORIGEN") shouldBe "Manual"
        layer.value(0, "SC") shouldBe "EPSG:25830"
        layer.value(0, "SATELITES") shouldBe null // un punto manual no tiene calidad
        layer.value(0, "REPRODUC") shouldBe null
        layer.value(1, "ORIGEN") shouldBe "Measured"
        layer.value(1, "SATELITES") shouldBe 24L
        layer.value(1, "REPRODUC") shouldBe 1L // true es 1
        layer.value(1, "MED_INICIO") shouldBe "2026-10-18T18:03:34Z" // UTC con la Z
        layer.value(1, "ALT_JALON") shouldBe 1.8
    }

    @Test
    fun `S055 RF-6 un booleano falso es 0 y uno que el punto no tiene queda vacio`() {
        val layer = builder.build(listOf(measured("1", fromReplay = false), point("2")), project()).points()

        layer.value(0, "REPRODUC") shouldBe 0L
        layer.value(0, "EDITADO") shouldBe 0L
        layer.value(1, "REPRODUC") shouldBe null
    }

    @Test
    fun `S055 RF-6 las longitudes van en metros aunque el proyecto este en pies`() {
        val layer = builder.build(listOf(point("1")), project(unit = LinearUnit.INTERNATIONAL_FOOT)).points()

        layer.value(0, "ESTE") shouldBe 440_000.123456789
        layer.value(0, "COTA") shouldBe 648.25
        layer.value(0, "UNIDADES") shouldBe "ft" // la columna de unidades dice cuál es la del proyecto
        (builder.build(listOf(point("1")), project(unit = LinearUnit.US_SURVEY_FOOT)).points().value(0, "UNIDADES")) shouldBe "US ft"
    }

    @Test
    fun `S055 RF-6 la base del punto en el sistema del proyecto sale de baseProjection`() {
        val withBase = measured("1").let { p ->
            p.copy(
                measurement = p.measurement!!.copy(
                    base = PointBase(GeodeticPosition(43.5, -5.9, 78.4), BaseSource.CORRECTIONS, 6, 0.0, 1234.5),
                ),
            )
        }

        val projection = BaseProjection(266_134.9877, 4_826_201.1235, 26.0123)
        val layer = builder.build(listOf(withBase), project(), baseProjection = { projection }).points()

        layer.value(0, "BASE_ESTE") shouldBe 266_134.9877
        layer.value(0, "BASE_NORTE") shouldBe 4_826_201.1235
        layer.value(0, "BASE_COTA") shouldBe 26.0123
        layer.value(0, "LINEABASE") shouldBe 1234.5
        builder.build(listOf(withBase), project()).points().value(0, "BASE_ESTE") shouldBe null // sin proyección, vacías
    }

    // ----------------------------------------------------------------------------------------- los atributos de los códigos
    @Test
    fun `S055 RF-6 cada atributo de codigo es una columna tipada tras las fijas`() {
        val arbol = code(
            "ARBOL",
            attribute("Diámetro", AttributeType.NUMBER, "0.35"),
            attribute("Ramas", AttributeType.INTEGER, "4"),
            attribute("Especie", AttributeType.TEXT, "Pino"),
            attribute("Estado", AttributeType.LIST, "Bueno"),
        )
        val export = builder.build(listOf(point("1", code = arbol), point("2")), project())
        val layer = export.points()
        val fixed = ExportColumn.entries.size

        layer.columns.drop(fixed).map { it.name to it.type } shouldBe listOf(
            "DIAMETRO" to DbfType.DECIMAL,
            "RAMAS" to DbfType.INTEGER,
            "ESPECIE" to DbfType.TEXT,
            "ESTADO" to DbfType.TEXT,
        )
        layer.value(0, "CODIGO") shouldBe "ARBOL"
        layer.value(0, "DIAMETRO") shouldBe 0.35
        layer.value(0, "RAMAS") shouldBe 4L
        layer.value(0, "ESPECIE") shouldBe "Pino"
        layer.value(0, "ESTADO") shouldBe "Bueno"
        layer.value(1, "DIAMETRO") shouldBe null // el punto sin código no tiene el atributo
        layer.value(1, "CODIGO") shouldBe null
    }

    @Test
    fun `S055 RF-9 un atributo que coincide con una columna fija o con otro se renombra y el resumen lo cuenta`() {
        val codes = code(
            "X",
            attribute("cota", AttributeType.NUMBER, "1.5"),
            attribute("Ancho vía", AttributeType.NUMBER, "2.5"),
            attribute("Altura del árbol", AttributeType.TEXT, "a"),
            attribute("Altura del poste", AttributeType.TEXT, "b"),
        )
        val export = builder.build(listOf(point("1", code = codes)), project())

        val names = export.points().columns.drop(ExportColumn.entries.size).map { it.name }
        names shouldBe listOf("COTA1", "ANCHO_VIA", "ALTURA_DEL", "ALTURA_DE1")
        export.summary.renamedAttributes shouldBe listOf(
            ShpRenamedColumn("cota", "COTA1"),
            ShpRenamedColumn("Ancho vía", "ANCHO_VIA"),
            ShpRenamedColumn("Altura del árbol", "ALTURA_DEL"),
            ShpRenamedColumn("Altura del poste", "ALTURA_DE1"),
        )
    }

    @Test
    fun `S055 RF-9 los atributos que no cambian de nombre no salen en el resumen`() {
        val export = builder.build(listOf(point("1", code = code("X", attribute("Ancho", AttributeType.NUMBER, "1.0")))), project())

        export.points().columns.last().name shouldBe "ANCHO"
        export.summary.renamedAttributes shouldBe emptyList()
    }

    @Test
    fun `S055 RF-9 en ingles cota no es una columna fija y se queda como COTA`() {
        val export = builder.build(
            listOf(point("1", code = code("X", attribute("cota", AttributeType.NUMBER, "1.5")))),
            project(),
            language = ShpNameLanguage.EN,
        )

        export.points().columns.last().name shouldBe "COTA"
        export.summary.renamedAttributes shouldBe emptyList()
    }

    // ------------------------------------------------------------------------------------------------------- la geometria
    @Test
    fun `S055 RF-12 por defecto X es el Este, Y el Norte y Z la altura del proyecto, sin redondear y en metros`() {
        val export = builder.build(listOf(point("1")), project())

        export.points().geometries.single() shouldBe ShpGeometry.Point(ShpVertex(440_000.123456789, 4_474_000.987654321, 648.25))
        export.summary.geographic shouldBe false
        export.summary.epsg shouldBe 25830
        export.summary.heightSystem shouldBe HeightSystem.ORTHOMETRIC
    }

    @Test
    fun `S055 RF-13 en geograficas X es la longitud, Y la latitud y Z la altura elipsoidal, sin redondear`() {
        val p = point("1", latitude = 40.416815094123456, longitude = -3.703533016654321, ellipsoidal = 699.423123456)
        val export = builder.build(listOf(p), project(), ShpExportOptions(ShpCoordinates.GEOGRAPHIC))

        export.points().geometries.single() shouldBe ShpGeometry.Point(ShpVertex(-3.703533016654321, 40.416815094123456, 699.423123456))
        export.summary.geographic shouldBe true
        export.summary.epsg shouldBe 4258
        export.summary.heightSystem shouldBe HeightSystem.ELLIPSOIDAL // el .prj no lo declara: va en el resumen
    }

    @Test
    fun `S055 RF-13 las columnas de Este y Norte siguen siendo las del proyecto aunque las coordenadas vayan en geograficas`() {
        val layer = builder.build(listOf(point("1")), project(), ShpExportOptions(ShpCoordinates.GEOGRAPHIC)).points()

        layer.value(0, "ESTE") shouldBe 440_000.123456789
        layer.value(0, "LONGITUD") shouldBe -3.7038
    }

    @Test
    fun `S055 RF-12 un proyecto en geograficas escribe su longitud, su latitud y la altura del proyecto aunque se pida proyectadas`() {
        val geographic = project(crs = ProjectCrs.ETRS89_GEOGRAPHIC, heightSystem = HeightSystem.ORTHOMETRIC)
        val p = point("1", height = 648.25, ellipsoidal = 700.5)

        val export = builder.build(listOf(p), geographic, ShpExportOptions(ShpCoordinates.PROJECTED))

        export.points().geometries.single() shouldBe ShpGeometry.Point(ShpVertex(-3.7038, 40.4168, 648.25))
        export.summary.geographic shouldBe true
        export.summary.heightSystem shouldBe HeightSystem.ORTHOMETRIC
        // pedir geográficas en un proyecto geográfico no cambia nada: la altura sigue siendo la del proyecto
        val asked = builder.build(listOf(p), geographic, ShpExportOptions(ShpCoordinates.GEOGRAPHIC))
        asked.points().geometries shouldBe export.points().geometries
    }

    @Test
    fun `S055 RF-14 un punto sin altura se escribe con Z igual a 0 y se cuenta`() {
        val export = builder.build(
            listOf(point("1"), point("2", height = null, ellipsoidal = null), point("3", height = Double.NaN)),
            project(),
        )

        (export.points().geometries[1] as ShpGeometry.Point).vertex.z shouldBe 0.0
        (export.points().geometries[2] as ShpGeometry.Point).vertex.z shouldBe 0.0
        export.summary.verticesWithoutHeight shouldBe 2
        export.points().value(1, "COTA") shouldBe null // el atributo sigue vacío: la Z = 0 es solo de la geometría
    }

    @Test
    fun `S055 RF-14 en geograficas lo que falta es la altura elipsoidal y no la del proyecto`() {
        val options = ShpExportOptions(ShpCoordinates.GEOGRAPHIC)
        val sinElipsoidal = point("1", height = 648.0, ellipsoidal = null)
        val sinCota = point("2", height = null, ellipsoidal = 700.0)

        val export = builder.build(listOf(sinElipsoidal, sinCota), project(), options)

        (export.points().geometries[0] as ShpGeometry.Point).vertex.z shouldBe 0.0
        (export.points().geometries[1] as ShpGeometry.Point).vertex.z shouldBe 700.0
        export.summary.verticesWithoutHeight shouldBe 1
    }

    @Test
    fun `S055 RF-14b un punto sin Este y Norte es un objeto sin geometria con sus atributos y se cuenta`() {
        val export = builder.build(
            listOf(point("1"), point("2", easting = null, northing = null), point("3", easting = 1.0, northing = Double.NaN)),
            project(),
        )

        val kinds = export.points().geometries.map { it::class }
        kinds shouldBe listOf(ShpGeometry.Point::class, ShpGeometry.Null::class, ShpGeometry.Null::class)
        export.points().value(1, "NOMBRE") shouldBe "P-2" // conserva su fila
        export.points().value(1, "LATITUD") shouldBe 40.4168
        export.summary.pointsWithoutPosition shouldBe 2
        export.summary.points shouldBe 3
        export.summary.verticesWithoutHeight shouldBe 0 // sin posición no hay vértice
    }

    @Test
    fun `S055 RF-14b en geograficas la posicion es la latitud y la longitud y un punto sin Este y Norte si tiene geometria`() {
        val noEastNorth = point("1", easting = null, northing = null)
        val export = builder.build(listOf(noEastNorth), project(), ShpExportOptions(ShpCoordinates.GEOGRAPHIC))

        export.points().geometries.single() shouldBe ShpGeometry.Point(ShpVertex(-3.7038, 40.4168, 700.5))
        export.summary.pointsWithoutPosition shouldBe 0
    }

    // ---------------------------------------------------------------------------------------------------------- el .prj
    @Test
    fun `S055 RF-15 el prj es el del sistema del proyecto o el de las geograficas de su datum`() {
        val definitions = ShpPrjDefinitions()
        val utm = builder.build(listOf(point("1")), project(ProjectCrs.ETRS89_UTM30)).points()
        val geo = builder.build(listOf(point("1")), project(ProjectCrs.ETRS89_UTM30), ShpExportOptions(ShpCoordinates.GEOGRAPHIC)).points()
        val geographic = ShpExportOptions(ShpCoordinates.GEOGRAPHIC)
        val canarias = builder.build(listOf(point("1")), project(ProjectCrs.REGCAN95_UTM28), geographic).points()
        val ed50 = builder.build(listOf(point("1")), project(ProjectCrs.ED50_UTM30), ShpExportOptions(ShpCoordinates.GEOGRAPHIC)).points()

        utm.prj shouldBe definitions.of(ProjectCrs.ETRS89_UTM30)
        geo.prj shouldBe definitions.geographicOf(ProjectCrs.ETRS89_UTM30)
        canarias.prj shouldBe definitions.geographicOf(ProjectCrs.REGCAN95_UTM28)
        ed50.prj shouldBe definitions.geographicOf(ProjectCrs.ETRS89_UTM30) // un proyecto ED50 exporta ETRS89
    }

    @Test
    fun `S055 RF-15 el sistema declarado del resumen es el EPSG de lo escrito`() {
        fun epsg(crs: ProjectCrs, coordinates: ShpCoordinates) =
            builder.build(listOf(point("1")), project(crs), ShpExportOptions(coordinates)).summary.epsg

        epsg(ProjectCrs.ETRS89_UTM29, ShpCoordinates.PROJECTED) shouldBe 25829
        epsg(ProjectCrs.REGCAN95_UTM27, ShpCoordinates.PROJECTED) shouldBe 4082
        epsg(ProjectCrs.ED50_UTM31, ShpCoordinates.PROJECTED) shouldBe 23031
        epsg(ProjectCrs.REGCAN95_UTM28, ShpCoordinates.GEOGRAPHIC) shouldBe 4081
        epsg(ProjectCrs.ED50_UTM31, ShpCoordinates.GEOGRAPHIC) shouldBe 4258
        epsg(ProjectCrs.ETRS89_GEOGRAPHIC, ShpCoordinates.PROJECTED) shouldBe 4258
    }

    @Test
    fun `S055 RF-16 si el sistema no tiene definicion no hay prj y el resumen lo avisa`() {
        val none = ShpExportBuilder(ShpPrjDefinitions(emptyMap()))

        val export = none.build(listOf(point("1")), project())

        export.points().prj shouldBe null
        export.summary.prjMissing shouldBe true
        builder.build(listOf(point("1")), project()).summary.prjMissing shouldBe false
    }

    @Test
    fun `S055 RF-15 el sistema de alturas del resumen es el del proyecto, y el elipsoidal si se piden geograficas`() {
        fun height(system: HeightSystem, coordinates: ShpCoordinates) =
            builder.build(listOf(point("1")), project(heightSystem = system), ShpExportOptions(coordinates)).summary.heightSystem

        height(HeightSystem.ORTHOMETRIC, ShpCoordinates.PROJECTED) shouldBe HeightSystem.ORTHOMETRIC
        height(HeightSystem.ELLIPSOIDAL, ShpCoordinates.PROJECTED) shouldBe HeightSystem.ELLIPSOIDAL
        height(HeightSystem.ORTHOMETRIC, ShpCoordinates.GEOGRAPHIC) shouldBe HeightSystem.ELLIPSOIDAL
    }

    @Test
    fun `S055 RF-22 el resumen cuenta los puntos, los que no tienen posicion y los vertices sin altura`() {
        val export = builder.build(
            listOf(point("1"), point("2", height = null), point("3", easting = null, northing = null), point("4", height = null)),
            project(),
        )

        export.summary.points shouldBe 4
        export.summary.pointsWithoutPosition shouldBe 1
        export.summary.verticesWithoutHeight shouldBe 2
        export.summary.lines shouldBe 0
        export.summary.polygons shouldBe 0
    }
}
