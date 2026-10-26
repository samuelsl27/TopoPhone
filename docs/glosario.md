# Glosario español ↔ código

Términos de la interfaz (español) y su nombre en el código (inglés). Si un concepto nuevo no aparece aquí, se añade antes de usarlo.

| Interfaz (es) | Código (en) | Nota |
|---|---|---|
| Proyecto | `Project` | |
| Carpeta (de proyectos) | `ProjectFolder` | Un solo nivel (S006) |
| Papelera | `Trash` | Proyectos (S006) y puntos de un proyecto (S011), recuperables 30 días |
| Vista de proyecto | `ProjectView` | Lo que se ve al abrir un proyecto |
| Datum de las correcciones | `CorrectionsDatum` | ETRS89, REGCAN95 o ITRF/WGS84 |
| Unidades lineales / angulares | `LinearUnit` / `AngularUnit` | m, ft, usft / DD, DMS, gon (RNF-T9) |
| Punto | `Point` | Medido, manual, importado, COGO o replanteado → `PointOrigin` |
| Punto medido / manual | `PointOrigin.MEASURED` / `.MANUAL` | Medido con el recolector (S010) o escrito a mano o duplicado (S011) |
| Punto sin altura | `hasHeight` | Punto manual solo con planimetría (S011) |
| Punto importado | `PointOrigin.IMPORTED` | Punto que viene de un fichero CSV o TXT; es un punto manual para todo lo demás (S028) |
| Importación (de puntos) | `PointImport` | Lo que se lee de un fichero y entra de una vez en el proyecto, con su `importId`; se puede deshacer (S028) |
| Asignación (de columnas) | `ColumnMapping` | Qué papel tiene cada columna del fichero: nombre, Este o latitud, Norte o longitud, altura, descripción o ninguno (S028) |
| Informe de errores (de la importación) | `ImportReport` | Las filas del fichero que no entran, con su línea y su motivo (S028) |
| Foto (de un punto) | `PointPhoto` | De la cámara o de la galería (`PhotoOrigin.CAMERA` / `.GALLERY`); hasta 10 por punto (S017b) |
| Foto en espera | `StagedPhoto` | Foto de una medida que aún no se ha guardado como punto (S017b) |
| Destino de la foto | `PhotoDestination` | El punto al que irá una foto desde el recolector (S017b) |
| Punto editado | `edited` | Punto medido cuyas coordenadas difieren de las de su medida (S011) |
| Historial (del punto) | `PointHistory` | Cambios de un punto con su fecha y los valores anteriores (S011) |
| Lista de objetos | `ObjectList` | Puntos del proyecto; las líneas llegan en S025 (S011) |
| Ficha del punto | `PointDetails` | Todo lo guardado de un punto (S011) |
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
| Medida instantánea | `InstantMeasurement` | Con la primera época que llega (S010) |
| Fuera de tolerancia | `OutOfTolerance` | Punto guardado sin cumplir el control de precisión, por decisión del usuario (S010) |
| Descripción (del punto) | `description` | Texto libre; se conserva para el siguiente punto (S010) |
| Modo automático | `AutoMode` | Mide solo un punto por tiempo o por distancia (S017) |
| Disparo | `trigger` | Momento en que el modo automático mide un punto (S017) |
| Medida en pausa | `MeasurementPaused` | Promediado o modo automático detenido por un corte (S017) |
| Desviación típica (de una medida) | `standardDeviation` | Horizontal y vertical de las épocas usadas (S017) |
| Estabilidad de la media | `meanStability` | Lo que se mueve la media en el último minuto (S017) |
| Control de precisión | `PrecisionControl` | |
| Replanteo / replantear | `Stakeout` / `stakeOut` | |
| Punto replanteado | `StakedPoint` | Sufijo `_stkd` |
| Diana (replanteo) | `Bullseye` | |
| Encadenamiento (PK) | `Chainage` | |
| Desplazamiento (offset) | `Offset` | |
| Desmonte / terraplén | `Cut` / `Fill` | |
| Cota de diseño | `DesignElevation` | La altura a la que hay que llegar: la del punto de diseño o la que escribe el usuario (S019) |
| En cota | `ON_GRADE` | La punta dentro de la tolerancia vertical de la cota de diseño; fuera, `CUT` (desmonte: hay que excavar) o `FILL` (terraplén: hay que rellenar) (S019) |
| Nivel vertical | `VerticalLevel` | Burbuja vertical junto a la diana con el desmonte o el terraplén y la banda de la tolerancia (S019) |
| Tolerancia vertical | `toleranceVM` | Valor absoluto máximo de la diferencia de cota; 3 cm por defecto (S019) |
| Informe de replanteo | `StakeoutReport` | Resumen y CSV de los puntos replanteados, con el último replanteo de cada punto de diseño (S019) |
| Sin evaluar | `notEvaluated` | Punto replanteado antes de S019, sin tolerancias guardadas (S019) |
| Jalón | `Pole` | |
| Altura del jalón | `poleHeight` | Del regatón a la base del receptor |
| Altura de antena | `antennaHeight` | Altura del jalón + offset al ARP (RS3: 0,134 m) |
| ARP (punto de referencia de la antena) | `Arp` | |
| APC (centro de fase) | `Apc` | |
| Inclinación (compensación) | `Tilt` / `TiltCompensation` | Mensaje ETC |
| Rumbo (del IMU) | `heading` | |
| Dirección de inclinación | `tiltDirection` | |
| Modo de inclinación | `TiltMode` (`APP`, `RECEIVER`, `VERTICAL`) | Calculada por la app, compensada por el receptor o jalón vertical; uno por receptor (S016) |
| Modo comprobado | `verified` | Modo de inclinación confirmado con el asistente; elegido a mano, "sin comprobar" (S016) |
| Asistente del modo de inclinación | `TiltModeWizard` | Prueba con la punta quieta y el jalón inclinado 15° (S016) |
| Guía de la inclinación | `TiltGuide` | Qué hacer según el estado y el aviso del ETC, como el movimiento en ocho (S016) |
| Punta del jalón | `PoleTip` | Punto del suelo con la inclinación aplicada (S016) |
| Precisión de la inclinación | `tiltAccuracy` | Coeficiente del ETC × altura de antena, por eje (S016) |
| Inclinación máxima | `maxTilt` | Condición del control de precisión, 60° por defecto (S016) |
| Receptor | `Receiver` | |
| Base / rover | `Base` / `Rover` | |
| Línea base | `Baseline` | |
| Solución: autónoma / flotante / fija | `SolutionStatus.SINGLE` / `.FLOAT` / `.FIX` | También `DGPS`, `NONE` |
| Edad de las correcciones | `correctionAge` | |
| Época | `Epoch` | Frases con la misma hora UTC; la completa `EpochAssembler` (S003) |
| Constelación | `Constellation` | GPS, GLONASS, Galileo, BeiDou, QZSS, NavIC; por talker o por *system ID* |
| Satélites usados / en vista | `satellitesUsed` / `satellitesInView` | GSA / GSV |
| Velocidad / rumbo sobre el suelo | `speedMps` / `courseRad` | RMC y VTG |
| Posición de la base | `BasePosition` | Frase EBP o mensajes RTCM 1005/1006 (S015) |
| Estación de referencia | `ReferenceStation` | La base que describen los mensajes RTCM 1005/1006, con su identificador (S015) |
| Skyplot | `Skyplot` | Satélites en vista por elevación y azimut (S015) |
| SNR (relación señal/ruido) | `snrDbHz` | En dB-Hz; del rover por GSV y de la base por MSM 4 a 7 (S015) |
| Banda de frecuencia | `Band` | L1, L2, L5, G1, G2, E1, E5a, E5b, E5, E6, B1, B1C, B2, B2a, B2b, B3 (S015) |
| DOP (PDOP, HDOP, VDOP) | `Dop` | Dilución de la precisión, de la frase GSA |
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
| Caster público | `PublicCaster` | Red del IGN o autonómica, plantilla para crear un perfil (S007) |
| Conexión segura (TLS) | `useTls` | Opción de cada perfil NTRIP (S007) |
| Mensaje RTCM | `RtcmMessage` | Trama RTCM 3 con su tipo y su CRC24Q (S007) |
| Umbral de la alarma de correcciones | `correctionAgeAlarmS` | Edad máxima de las correcciones antes de avisar (S007) |
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
| Factor de altura | `heightFactor` | Radio medio de Gauss / (radio + altura elipsoidal) (S008) |
| Factor combinado | `combinedFactor` | Factor de escala × factor de altura (S008) |
| Huso propuesto | `suggestedZone` | Huso de la posición al crear un proyecto (S008) |
| ED50 | `Ed50` | Datum antiguo; se calcula desde ETRS89 con la rejilla SPED2ETV2 del IGN (S008) |
| Distancia de cuadrícula / de terreno | `gridDistance` / `groundDistance` | |
| Base shift (desplazamiento de base) | `BaseShift` | |
| Localización (calibración local) | `Localization` | |
| Residuo | `Residual` | |
| Inversa / poligonal / intersección | `Inverse` / `Traverse` / `Intersection` | COGO |
| Capa (WMS) | `MapLayer` | |
| Mapa base | `Basemap` | |
| Ortofoto | `Orthophoto` | PNOA del IGN (S009) |
| Barra de escala | `ScaleBar` | |
| Encuadre (del mapa) | `MapViewport` | Centro y escala con que se ve el mapa |
| Tesela | `Tile` | Trozo del mapa que se descarga y se guarda en la caché |
| Círculo de precisión | `AccuracyCircle` | Radio = precisión horizontal (S002/RF-14) |
| Panel de posición | `PositionPanel` | Parte inferior del mapa del proyecto (S009) |
| Datos del proyecto | `ProjectInfo` | SC, altura, unidades y datum de las correcciones (S009) |
| Superficie (MDT/TIN) | `Surface` | |
| Dibujo CAD | `CadDrawing` | Un DXF importado en un proyecto; un proyecto puede tener varios (S040) |
| Capa de diseño | `DesignLayer` | Una capa del DXF separada por tipo (puntos o líneas), con su color y su interruptor (S040) |
| Elemento del dibujo | `CadEntity` | Un punto o una línea (polilínea, arco, círculo, spline) del dibujo, con los bloques ya desplegados (S040) |
| Con cota / sin cota | `hasElevation` | Un elemento del dibujo con altura propia, o sin ella (S040) |
| Copia de seguridad | `Backup` | Copia completa de los datos de la app en un ZIP (S013) |
| Exportar | `export` | Sacar los puntos de un proyecto a un fichero (S012) |
| Formato de exportación | `ExportFormat` | Todas las columnas, PENZD, PNEZD o personalizado (S012) |
| Separador de columnas | `columnSeparator` | Coma, punto y coma o tabulador (S012) |
| Signo decimal | `decimalSeparator` | Punto o coma (S012) |
| Paquete de proyecto | `ProjectPackage` | `.topoproj`: un proyecto completo para llevarlo a otro móvil (S013) |
| Restaurar (una copia) | `restore` | Sustituir los datos del móvil por los de una copia (S013) |
| Deshacer la restauración | `undoRestore` | Volver al estado guardado antes de la última restauración (S013) |
| Copia automática | `AutoBackup` | Copia completa que se hace sola en la carpeta elegida (S013) |
| Log de la app | `AppLog` | Anotaciones de lo que hace la app y de lo que falla, sin secretos (S014) |
| Informe de sistema | `SystemReport` | ZIP con el log, las sesiones recientes y los datos de la app y del móvil (S014) |
| Tema (Sistema, Claro, Oscuro, Sol) | `ThemeMode` (`SYSTEM`, `LIGHT`, `DARK`, `SUN`) | Elección del usuario; el que se pinta es `EffectiveTheme` |
| Modo sol | `ThemeMode.SUN` | Tema claro de alto contraste (≥ 7:1); el acceso rápido es `ThemeState.toggleSun()` |
| Idioma (Sistema, Español, English) | `AppLanguage` (`SYSTEM`, `SPANISH`, `ENGLISH`) | Se guarda con etiquetas BCP 47 |
| Perfil de equipo | `EquipmentProfile` | Modelo, n.º de serie, firmware (lo introduce el usuario) |
