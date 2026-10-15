# Glosario español ↔ código

Términos de la interfaz (español) y su nombre en el código (inglés). Si un concepto nuevo no aparece aquí, se añade antes de usarlo.

| Interfaz (es) | Código (en) | Nota |
|---|---|---|
| Proyecto | `Project` | |
| Carpeta (de proyectos) | `ProjectFolder` | Un solo nivel (S006) |
| Papelera | `Trash` | Proyectos borrados, recuperables 30 días (S006) |
| Vista de proyecto | `ProjectView` | Lo que se ve al abrir un proyecto |
| Datum de las correcciones | `CorrectionsDatum` | ETRS89, REGCAN95 o ITRF/WGS84 |
| Unidades lineales / angulares | `LinearUnit` / `AngularUnit` | m, ft, usft / DD, DMS, gon (RNF-T9) |
| Punto | `Point` | Medido, manual, importado, COGO o replanteado → `PointOrigin` |
| Línea / polilínea | `Line` | |
| Polígono (línea cerrada) | `Polygon` | |
| Vértice (de una línea) | `Vertex` | |
| Vértice geodésico (REGENTE/ROI) | `GeodeticMark` | Señal física con reseña |
| Reseña | `MarkSheet` | |
| Clavo de nivelación (REDNAP) | `LevellingBenchmark` | |
| Código de levantamiento | `SurveyCode` | |
| Biblioteca de códigos | `CodeLibrary` | |
| Atributo | `Attribute` | Text, Integer, Number, List |
| Recolector | `Collector` | |
| Recolectar / medir punto | `collectPoint` | |
| Promediado | `Averaging` | |
| Control de precisión | `PrecisionControl` | |
| Replanteo / replantear | `Stakeout` / `stakeOut` | |
| Punto replanteado | `StakedPoint` | Sufijo `_stkd` |
| Diana (replanteo) | `Bullseye` | |
| Encadenamiento (PK) | `Chainage` | |
| Desplazamiento (offset) | `Offset` | |
| Desmonte / terraplén | `Cut` / `Fill` | |
| Cota de diseño | `DesignElevation` | |
| Jalón | `Pole` | |
| Altura del jalón | `poleHeight` | Del regatón a la base del receptor |
| Altura de antena | `antennaHeight` | Altura del jalón + offset al ARP (RS3: 0,134 m) |
| ARP (punto de referencia de la antena) | `Arp` | |
| APC (centro de fase) | `Apc` | |
| Inclinación (compensación) | `Tilt` / `TiltCompensation` | Mensaje ETC |
| Rumbo (del IMU) | `heading` | |
| Dirección de inclinación | `tiltDirection` | |
| Receptor | `Receiver` | |
| Base / rover | `Base` / `Rover` | |
| Línea base | `Baseline` | |
| Solución: autónoma / flotante / fija | `SolutionStatus.SINGLE` / `.FLOAT` / `.FIX` | También `DGPS`, `NONE` |
| Edad de las correcciones | `correctionAge` | |
| Época | `Epoch` | Frases con la misma hora UTC; la completa `EpochAssembler` (S003) |
| Constelación | `Constellation` | GPS, GLONASS, Galileo, BeiDou, QZSS, NavIC; por talker o por *system ID* |
| Satélites usados / en vista | `satellitesUsed` / `satellitesInView` | GSA / GSV |
| Velocidad / rumbo sobre el suelo | `speedMps` / `courseRad` | RMC y VTG |
| Posición de la base | `BasePosition` | Frase EBP de Emlid |
| Estado / aviso de la inclinación | `TiltState` / `TiltNotification` | Frase ETC |
| Sesión del receptor (conexión) | `ReceiverSession` | |
| Sesión grabada | `RecordedSession` | Carpeta con los datos del receptor y las correcciones |
| Espacio para sesiones | `sessionSpaceMb` | Límite de las sesiones grabadas |
| Reproducción (simulador) | `Replay` | `ReplayTransport`, `ReplayPacer` |
| Indicador de estado | `StatusIndicator` | En el extremo derecho de la barra superior (S005); antes, franja de S003 |
| Lista de receptores | `ReceiverList` | Receptor sin conexión (S005) |
| Pantalla del receptor | `ReceiverDashboard` | Receptor conectado o reproducción: cabecera y menú (S005) |
| Cabecera (de la pantalla del receptor) | `ReceiverHeader` | |
| Correcciones | `Corrections` | RTCM3 |
| Perfil NTRIP | `NtripProfile` | |
| Punto de montaje | `Mountpoint` | |
| Tabla de fuentes | `Sourcetable` | |
| Sistema de coordenadas (SC) | `CoordinateSystem` | |
| Datum | `Datum` | |
| Elipsoide | `Ellipsoid` | |
| Proyección | `Projection` | |
| Huso (UTM) | `UtmZone` | Huso forzado = `forcedZone` |
| Transformación (de datum) | `DatumTransformation` | Helmert, Molodensky-Badekas, rejilla |
| Rejilla | `Grid` | Geoide o desplazamiento |
| Geoide / modelo de geoide | `GeoidModel` | |
| Ondulación del geoide (N) | `geoidUndulation` | |
| Altura elipsoidal (h) | `ellipsoidalHeight` | |
| Altitud ortométrica (H) | `orthometricHeight` | |
| Coordenadas geográficas (φ, λ, h) | `GeodeticPosition` | |
| Coordenadas cartesianas geocéntricas | `EcefPosition` | |
| Coordenadas proyectadas (X/Este, Y/Norte) | `GridPosition` (`easting`, `northing`) | |
| Plano tangente local | `EnuPosition` | |
| Factor de escala | `scaleFactor` | |
| Convergencia de meridianos | `meridianConvergence` | |
| Distancia de cuadrícula / de terreno | `gridDistance` / `groundDistance` | |
| Base shift (desplazamiento de base) | `BaseShift` | |
| Localización (calibración local) | `Localization` | |
| Residuo | `Residual` | |
| Inversa / poligonal / intersección | `Inverse` / `Traverse` / `Intersection` | COGO |
| Capa (WMS) | `MapLayer` | |
| Mapa base | `Basemap` | |
| Superficie (MDT/TIN) | `Surface` | |
| Dibujo CAD | `CadDrawing` | |
| Copia de seguridad | `Backup` | |
| Paquete de proyecto | `ProjectPackage` | `.topoproj` |
| Tema (Sistema, Claro, Oscuro, Sol) | `ThemeMode` (`SYSTEM`, `LIGHT`, `DARK`, `SUN`) | Elección del usuario; el que se pinta es `EffectiveTheme` |
| Modo sol | `ThemeMode.SUN` | Tema claro de alto contraste (≥ 7:1); el acceso rápido es `ThemeState.toggleSun()` |
| Idioma (Sistema, Español, English) | `AppLanguage` (`SYSTEM`, `SPANISH`, `ENGLISH`) | Se guarda con etiquetas BCP 47 |
| Perfil de equipo | `EquipmentProfile` | Modelo, n.º de serie, firmware (lo introduce el usuario) |
