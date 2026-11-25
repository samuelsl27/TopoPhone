package es.topophone.core.survey

import es.topophone.core.geodesy.Geodesy
import es.topophone.core.geodesy.GeodeticPoint
import es.topophone.core.geodesy.GeoidModels
import es.topophone.core.geodesy.catalog.CrsDefinitions
import es.topophone.core.geodesy.catalog.GeoidSource
import es.topophone.core.geodesy.catalog.ResolvedCrs
import es.topophone.core.geodesy.datum.ClasspathGridSource
import es.topophone.core.geodesy.datum.GridSource
import es.topophone.core.geodesy.datum.TransformResult
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LibraryCrs

/**
 * Las rejillas y los geoides con que se calcula un SC de la biblioteca y que no van en su definición: los que vienen en la app y los que el
 * usuario ha descargado (S034/RF-12, RF-14, RF-17, RF-18, RF-24). `data` pone la implementación con los ficheros instalados; sin ella solo hay
 * lo que trae la app.
 */
interface LibraryResources {
    /** De dónde se leen las rejillas de desplazamiento (las de la app y las instaladas). */
    val grids: GridSource

    /** El geoide llamado [gridName], o `null` si no está instalado. */
    fun geoid(gridName: String): GeoidSource?

    /** Cambia cada vez que se instala o se borra una rejilla, para que los motores en caché se rehagan. */
    val generation: Int

    /** ¿Está la rejilla [name] (la de la app o una instalada) lista para leerse? Sin leerla entera si se puede evitar (S057/RF-10). */
    fun isInstalled(name: String): Boolean = grids.read(name) != null

    companion object {
        @Volatile
        private var installed: LibraryResources? = null

        /**
         * Lo que usa el cálculo: lo pone la app al arrancar y al instalar o borrar rejillas. Sin inicializador estático: si
         * [BundledLibraryResources] se inicializa antes que este `companion` (lo hace al implementar una interfaz con métodos por
         * defecto), un valor inicial `= BundledLibraryResources` quedaría en null para siempre y fallaría según el orden de uso.
         */
        var current: LibraryResources
            get() = installed ?: BundledLibraryResources
            set(value) {
                installed = value
            }
    }
}

/** Solo lo que trae la app: la rejilla SPED2ETV2 y los geoides EGM08-REDNAP de la Península y de Canarias (S034/RF-17). */
object BundledLibraryResources : LibraryResources {
    override val grids: GridSource = ClasspathGridSource("/shift/")
    override val generation: Int = 0

    override fun geoid(gridName: String): GeoidSource? = when (gridName) {
        "es_ign_egm08-rednap.tif" -> object : GeoidSource {
            override val name = "EGM08-REDNAP"
            override val attribution = "EGM08-REDNAP © IGN, CC-BY 4.0"

            override fun undulationM(latitudeDeg: Double, longitudeDeg: Double) =
                GeoidModels.egm08RednapPeninsula.undulationM(latitudeDeg, longitudeDeg)
        }
        "es_ign_egm08-rednap-canarias.tif" -> object : GeoidSource {
            override val name = "EGM08-REDNAP Canarias"
            override val attribution = "EGM08-REDNAP (Canarias) © IGN, CC-BY 4.0"

            override fun undulationM(latitudeDeg: Double, longitudeDeg: Double) =
                GeoidModels.egm08RednapCanarias.undulationM(latitudeDeg, longitudeDeg)
        }
        else -> null
    }
}

/**
 * El motor de un SC de la biblioteca (S034/RF-26, RF-27, RF-28): del suelo (geográficas en el datum de las correcciones) al datum del SC por la
 * transformación guardada en su definición, de ahí a la proyección con sus factores y, con altitud, con el geoide del proyecto. Una rejilla que
 * falta da la coordenada como no disponible con su nombre (S033/RF-12, S034/RF-18).
 */
class LibraryEngine(private val crs: LibraryCrs, private val resources: LibraryResources) : CoordinateEngine {
    private val resolved: ResolvedCrs? = CrsDefinitions.resolve(crs.definition, resources.grids)

    override fun containsPosition(latitudeDeg: Double, longitudeDeg: Double): Boolean =
        resolved != null && crs.containsPosition(latitudeDeg, longitudeDeg)

    override fun undulation(latitudeDeg: Double, longitudeDeg: Double): Undulation {
        crs.geoidConstantM?.let { return Undulation.Value(it) } // S035/RF-5: una altura ortométrica constante
        val name = crs.geoidGrid ?: return Undulation.Outside
        val geoid = resources.geoid(name) ?: return Undulation.MissingGrid(name)
        return geoid.undulationM(latitudeDeg, longitudeDeg)?.let { Undulation.Value(it) } ?: Undulation.Outside
    }

    override fun groundFromDatum(latitudeDeg: Double, longitudeDeg: Double): GroundResult {
        val crsResolved = resolved ?: return GroundResult.Unavailable(PositionUnavailable.OUTSIDE_CRS_AREA)
        return when (val result = crsResolved.transformation.forward(GeodeticPoint(latitudeDeg, longitudeDeg, 0.0))) {
            is TransformResult.Ok -> GroundResult.Ok(result.point.latitudeDeg, result.point.longitudeDeg)
            is TransformResult.GridUnavailable -> GroundResult.Unavailable(PositionUnavailable.MISSING_GRID, result.gridName)
            is TransformResult.OutsideGrid, is TransformResult.NotConverged -> GroundResult.Unavailable(
                PositionUnavailable.OUTSIDE_DATUM_GRID,
            )
        }
    }

    override fun groundFromGrid(eastingM: Double, northingM: Double): GroundResult {
        val projection = resolved?.projection ?: return GroundResult.Unavailable(PositionUnavailable.OUTSIDE_CRS_AREA)
        val point = projection.inverse(eastingM, northingM) ?: return GroundResult.Unavailable(PositionUnavailable.OUTSIDE_CRS_AREA)
        return groundFromDatum(point.latitudeDeg, point.longitudeDeg)
    }

    override fun fromGround(ground: GeodeticPosition, heightSystem: HeightSystem): ProjectPosition {
        val crsResolved = resolved ?: return ProjectPosition(unavailable = setOf(PositionUnavailable.OUTSIDE_CRS_AREA))
        val lat = ground.latitudeDeg
        val lon = ground.longitudeDeg
        if (!crs.containsPosition(lat, lon)) return ProjectPosition(unavailable = setOf(PositionUnavailable.OUTSIDE_CRS_AREA)) // S006/RF-30

        // Del suelo al datum del SC: la transformación se guarda de ese datum a las correcciones, así que se usa su inverso.
        val datumPoint = when (val result = crsResolved.transformation.inverse(GeodeticPoint(lat, lon, ground.ellipsoidalHeightM ?: 0.0))) {
            is TransformResult.Ok -> result.point
            is TransformResult.GridUnavailable ->
                return ProjectPosition(unavailable = setOf(PositionUnavailable.MISSING_GRID), missingGrid = result.gridName)
            is TransformResult.OutsideGrid, is TransformResult.NotConverged ->
                return ProjectPosition(unavailable = setOf(PositionUnavailable.OUTSIDE_DATUM_GRID))
        }
        val unavailable = mutableSetOf<PositionUnavailable>()
        var missingGrid: String? = null
        val groundHeight = ground.ellipsoidalHeightM
        if (groundHeight == null) unavailable += PositionUnavailable.NO_HEIGHT
        val height = when (heightSystem) {
            HeightSystem.ELLIPSOIDAL -> groundHeight
            HeightSystem.ORTHOMETRIC -> when (val undulation = undulation(lat, lon)) {
                is Undulation.Value -> groundHeight?.minus(undulation.metres)
                Undulation.Outside -> {
                    if (groundHeight != null) unavailable += PositionUnavailable.OUTSIDE_GEOID
                    null
                }
                is Undulation.MissingGrid -> {
                    if (groundHeight != null) {
                        unavailable += PositionUnavailable.MISSING_GRID
                        missingGrid = undulation.name
                    }
                    null
                }
            }
        }

        val projection = crsResolved.projection
        if (projection == null) {
            // Un SC geográfico: sin factores, con las coordenadas del datum del SC.
            return ProjectPosition(
                height = height,
                unavailable = unavailable,
                latitudeDeg = datumPoint.latitudeDeg,
                longitudeDeg = datumPoint.longitudeDeg,
                missingGrid = missingGrid,
            )
        }
        val projected = projection.forward(datumPoint.latitudeDeg, datumPoint.longitudeDeg)
            ?: return ProjectPosition(unavailable = setOf(PositionUnavailable.OUTSIDE_CRS_AREA))
        val factors = projection.factors(datumPoint.latitudeDeg, datumPoint.longitudeDeg)
        val combined = if (factors != null && groundHeight != null) {
            val radius = Geodesy.gaussMeanRadius(datumPoint.latitudeDeg, crsResolved.ellipsoid)
            factors.scale * radius / (radius + groundHeight)
        } else {
            null
        }
        return ProjectPosition(
            easting = projected.eastingM,
            northing = projected.northingM,
            height = height,
            unavailable = unavailable,
            scaleFactor = factors?.scale,
            convergenceRad = factors?.convergenceRad,
            combinedFactor = combined,
            missingGrid = missingGrid,
        )
    }
}

/** Los motores de los SC de la biblioteca ya construidos, para no releer las rejillas en cada época (S034/RF-26). */
internal object LibraryEngines {
    private const val MAX = 4
    private val cache = object : LinkedHashMap<Triple<LibraryCrs, LibraryResources, Int>, LibraryEngine>(MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<LibraryCrs, LibraryResources, Int>, LibraryEngine>) =
            size > MAX
    }

    @Synchronized
    fun of(crs: LibraryCrs, resources: LibraryResources): LibraryEngine =
        cache.getOrPut(Triple(crs, resources, resources.generation)) { LibraryEngine(crs, resources) }
}
