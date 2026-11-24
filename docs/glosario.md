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
| Lista de objetos | `ObjectList` | Puntos y líneas del proyecto (S011, S025) |
| Ficha del punto | `PointDetails` | Todo lo guardado de un punto (S011) |
| Línea (del proyecto) | `ProjectLine` | Sucesión ordenada de vértices con nombre, código de geometría Línea o Polígono y atributos; no es la línea de un dibujo CAD (S025) |
| Línea activa | `ActiveLine` | La línea que recibe los puntos que se miden con su código (S025) |
| Línea abierta | `OpenLine` | Línea que no es un polígono (S025) |
| Polígono (línea cerrada) | `Polygon` | |
| Vértice (de una línea) | `Vertex` | Punto del proyecto que la línea referencia, sin copiarlo (S025) |
| Vértice geodésico (REGENTE/ROI) | `GeodeticMark` | Señal física con reseña |
| Reseña | `MarkSheet` | |
| Clavo de nivelación (REDNAP) | `LevellingBenchmark` | |
| Código de levantamiento | `SurveyCode` | |
| Biblioteca de códigos | `CodeLibrary` | Conjunto de códigos con nombre; se gestiona en Ajustes (S023) |
| Geometría (del código) | `CodeGeometry` | Punto, Línea o Polígono; un código tiene una sola (S023) |
| Biblioteca por defecto | `CodeLibrary.isDefault` | La que viene con la app: «Topografía general» y «Urbanización»; de solo lectura (S023) |
| Biblioteca vinculada | `LibraryChoice` | La copia de una biblioteca que lleva un proyecto; no cambia si cambia la original (S023) |
| Atributo | `Attribute` | Text, Integer, Number, List |
| Código del proyecto | `ProjectCode` | Los de la biblioteca vinculada más los creados en el proyecto; el recolector ofrece los de geometría Punto (S024) |
| Códigos recientes | `RecentCodes` | Los últimos 10 códigos distintos usados en el proyecto (S024) |
| Símbolo (del código) | `CodeSymbol` | Círculo, cuadrado, triángulo, rombo, cruz o estrella; solo se elige en los códigos del proyecto (S024) |
| Código del punto | `PointCode` | El código, color, símbolo y atributos con que se guardó un punto, por valor (S024) |
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
| Altura de antena | `antennaHeight` | Altura vertical del jalón + desplazamiento al centro de fase (APC) del perfil de antena (RS3: 0,134 m) (S046) |
| ARP (punto de referencia de la antena) | `Arp` | |
| Perfil de antena | `AntennaProfile` | Modelo de receptor o de antena con su desplazamiento del ARP al centro de fase y, si se conoce, el radio de su base; del catálogo o propio (S046) |
| Jalón guardado | `SavedPole` | Jalón con nombre, tipo de medida y altura (S046) |
| Jalón activo | `ActivePole` | El jalón que usan el recolector, el replanteo y las coordenadas para un receptor (S046) |
| Altura inclinada | `slantHeight` | La que se mide con la cinta del regatón al borde de la base del receptor (S046) |
| Altura vertical del jalón | `verticalPoleHeight` | Del regatón a la base del receptor, en vertical; la de todos los cálculos (S046) |
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
| Tipo de solución | `SolutionType` | FIX, FLOAT o SINGLE, de mejor a peor; una GGA con calidad DGPS cuenta como SINGLE (S047) |
| Tipo de solución admitido | `AdmittedSolution` (`AdmittedSolutions`) | Los tipos con los que la app deja medir y replantear; por defecto solo FIX (S047) |
| Solución degradada | `DegradedSolution` | Durante un promediado, llega una época de un tipo peor que el mejor visto y se descarta (S047) |
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
| Receptor Wi-Fi | `WifiReceiver` | Receptor guardado con nombre, dirección y puerto de posición, y puerto de correcciones opcional; con identidad propia que no cambia al editar su IP (S021) |
| Puerto de posición | `positionPort` | El del servidor TCP del receptor que emite el NMEA (9001 por defecto); la app es el cliente (S021) |
| Puerto de correcciones | `correctionsPort` | El del receptor que acepta RTCM3 por TCP; si coincide con el de posición se usa la misma conexión (S021) |
| Clave de receptor | `ReceiverKey` | Con qué se guarda lo de cada receptor (último receptor, jalón y antena, inclinación): su dirección Bluetooth, `wifi:<id>` o `replay` (S021) |
| Enlace de correcciones | `CorrectionsLink` | El segundo socket que lleva las RTCM3 a un receptor Wi-Fi cuyo puerto de correcciones es distinto del de posición; si cae, descarta y reintenta sin cortar la posición (S021) |
| Permiso de red local | `ACCESS_LOCAL_NETWORK` | Android 17+: se pide al conectar con un receptor Wi-Fi, no al abrir la app (S021) |
| GNSS del móvil | `PhoneGnss` | La posición que da el sistema Android; siempre SINGLE, sin correcciones (S022) |
| Receptor virtual | `VirtualReceiver` | Fuente de épocas generadas por la app a partir de un escenario; es una fuente simulada (S022) |
| Escenario | `Scenario` | Guion de duración fija del receptor virtual: posición, tipo de solución y eventos de cada instante (S022) |
| Fuente de la posición | `PositionSource` | De dónde viene la posición de un punto: receptor, reproducción, GNSS del móvil o receptor virtual (S022) |
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
| Helmert (3, 4, 6 o 7 parámetros) | `HelmertTransformation` | Traslaciones, rotaciones y escala sobre geocéntricas; convención PV o CF (S033) |
| Molodensky-Badekas | `MolodenskyBadekas` | Helmert de 7 parámetros con punto de rotación propio (S033) |
| Rejilla de desplazamiento | `GridShiftTransformation` | Rejilla GeoTIFF con desplazamientos de latitud y longitud en segundos de arco (S033) |
| Geoide / modelo de geoide | `GeoidModel` | |
| Biblioteca de SC | `CrsLibrary` | Catálogo de SC calculables generado del registro EPSG e incluido en la app (S034) |
| Ficha del SC | `CrsDetails` | Pantalla con los datos de un SC y de su transformación (S034) |
| SC calculable | `CalculableCrs` | SC cuya proyección y cuya transformación de datum sabe calcular la app (S034) |
| Datum vertical | `VerticalDatum` | Referencia de las alturas: elipsoidal o un modelo de geoide (S034) |
| Rejilla instalada | `InstalledGrid` | Rejilla (geoide o desplazamiento) descargada y verificada en el almacenamiento privado (S034) |
| SC de origen | `CadSourceCrs` (`Library` si es de la biblioteca) | El SC en que vienen las coordenadas de un fichero que se importa; se pasa al SC del proyecto con el cambio de datum de su definición (S057) |
| Definición del SC | `CrsDefinition` | El SC de la biblioteca ya construido: su proyección, su datum y la transformación hasta el de las correcciones; es lo que guarda un proyecto o un dibujo (S034, S057) |
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
| Información geométrica | `GeometryInfo` | Longitud 2D y 3D, perímetro, área y tabla por segmento de una línea o un polígono (S026) |
| Segmento | `Segment` | Trozo de línea entre dos vértices consecutivos; un polígono incluye el de cierre (S026) |
| Perímetro | `Perimeter` | Longitud 2D de un polígono (S026) |
| Área | `Area` | Superficie horizontal que encierra un polígono (S026) |
| Pendiente | `Slope` | Desnivel de un segmento entre su distancia horizontal, en porcentaje (S026) |
| Ángulo entre tres puntos | `ThreePointAngle` | Ángulo en B entre las direcciones a A y a C, de 0° a 180° (S026) |
| Base shift (desplazamiento de base) | `BaseShift` | Vector ECEF conocido − medido que se suma a la punta del jalón antes del SC (S020) |
| Punto conocido | `KnownPoint` | El punto de coordenadas buenas con el que se calcula un base shift; se mide aparte y no se guarda como punto (S020) |
| Desplazamiento activo | `ActiveBaseShift` | El base shift que llevan los puntos que se midan; uno o ninguno por proyecto (S020) |
| Localización (calibración local) | `Localization` | Transformación horizontal (traslación, giro y escala) y vertical (desplazamiento o plano) calculada con pares de puntos, que se aplica a los puntos GNSS tras el SC (S039) |
| Par de localización | `LocalizationPair` | Un punto GNSS y las coordenadas conocidas que le corresponden en el sistema de la obra (S039) |
| Localización activa | `ActiveLocalization` | La localización que usa el proyecto; una o ninguna; el borrador no se aplica hasta «Aplicar» (S039) |
| Localizado | `Localized` | Indicador de que las coordenadas mostradas de los puntos GNSS pasan por una localización activa (S039) |
| Residuo | `Residual` | Diferencia conocido − transformado de un par tras el ajuste (S039) |
| Inversa / poligonal / intersección | `Inverse` / `Traverse` / `Intersection` | COGO (S038) |
| Punto COGO | `PointOrigin.COGO` | Punto guardado desde el resultado de una poligonal o una intersección, con la operación y las entradas; es un punto manual para todo lo demás (S038) |
| Error de cierre | `ClosureError` | Diferencia en Este y Norte entre el último punto de una poligonal y el de cierre, con el error lineal y la precisión 1:N; solo se informa (S038) |
| Deflexión | `LegKind.DEFLECTION_RIGHT` / `.DEFLECTION_LEFT` | Ángulo entre la prolongación del tramo anterior y el tramo siguiente de una poligonal, a la derecha o a la izquierda, de 0° a 180° (S038) |
| Borrador de la poligonal | `TraverseDraft` | Puntos de entrada y tramos escritos de la poligonal de un proyecto y aún sin guardar como puntos; uno por proyecto (S038) |
| Capa (WMS) | `MapLayer` | Una capa de un servicio WMS añadida a un proyecto: «capa del proyecto» (S036) |
| Servicio WMS | `WmsService` | Dirección de un servidor WMS guardada en la biblioteca del móvil, con sus capas leídas (S036) |
| Biblioteca WMS | `WmsLibrary` | Lista de servicios WMS del móvil, común a todos los proyectos (S036) |
| Capa del proyecto | `ProjectMapLayer` | Capa de un servicio WMS con su orden, visibilidad y opacidad en un proyecto (S036) |
| Preset (WMS) | `WmsPreset` | Servicio público de la app ya preparado, con su atribución y su licencia (S036) |
| Modo de petición | `WmsRequestMode` | «Por teselas» o «una imagen» de la zona visible, según las condiciones del servicio (S036) |
| Menú del mapa | `MapMenu` | El que sale al mantener pulsado un punto del mapa: consultar la información de las capas y ver las coordenadas (S036) |
| Mapa base | `Basemap` | |
| Ortofoto | `Orthophoto` | PNOA del IGN (S009) |
| Barra de escala | `ScaleBar` | |
| Encuadre (del mapa) | `MapViewport` | Centro y escala con que se ve el mapa |
| Tesela | `Tile` | Trozo del mapa que se descarga y se guarda en la caché |
| Zona sin conexión | `OfflineRegion` | Área de un proyecto con las fuentes elegidas descargadas hasta un nivel de detalle (S037) |
| Nivel de detalle | `DetailLevel` | «Normal», «Alto» o «Máximo»: el zoom máximo de tesela de una zona sin conexión (S037) |
| Fuente descargable | `DownloadableSource` | Mapa base o capa WMS por teselas que se puede guardar en una zona sin conexión; no lo son el Catastro ni los WMS en «una imagen» (S037) |
| Todo el proyecto | `ProjectExtent` | Rectángulo que contiene los puntos del proyecto con un margen, como área de una zona sin conexión (S037) |
| Actualizar (una zona) | `OfflineDownloader.renew` | Marca las teselas de una zona como caducadas para que, con conexión, se renueven al verlas en el mapa; no vuelve a descargar la zona entera (S037/RF-26) |
| Círculo de precisión | `AccuracyCircle` | Radio = precisión horizontal (S002/RF-14) |
| Panel de posición | `PositionPanel` | Parte inferior del mapa del proyecto (S009) |
| Datos del proyecto | `ProjectInfo` | SC, altura, unidades y datum de las correcciones (S009) |
| Superficie (MDT/TIN) | `Surface` | Modelo de triángulos importado de un LandXML, o ráster de cotas (S045); un proyecto puede tener varias (S042) |
| Superficie activa | `ActiveSurface` | La que usan el replanteo y el recolector, TIN o ráster; una o ninguna por proyecto (S042, S045) |
| Ráster de cotas | `ElevationRaster` | Malla regular de celdas con una cota por celda (GeoTIFF o ASCII Grid), importada como una superficie más; se consulta por interpolación bilineal entre centros de celda y se convierte entre altitud ortométrica y altura elipsoidal con el geoide (S045) |
| Celda sin dato | `NoDataCell` | La que el fichero declara sin valor, o no es un número; no tiene cota y su posición es «Fuera de la superficie» (S045) |
| Desplazamiento vertical | `VerticalOffset` | Distancia sobre (positiva) o bajo la superficie a la que se quiere estar; uno por proyecto (S042) |
| Cota de la superficie | `SurfaceElevation` | Cota de la superficie en una posición Este y Norte, interpolada en su triángulo (S042) |
| Fuera de la superficie | `OutsideSurface` | Posición sin triángulo: fuera del contorno o en un hueco; no tiene cota (S042) |
| Origen de la cota | `ElevationSource` | `POINT` («Cota del punto») o `SURFACE` («Superficie») (S042) |
| Dibujo CAD | `CadDrawing` | Un DXF importado en un proyecto; un proyecto puede tener varios (S040) |
| Capa de diseño | `DesignLayer` | Una capa del DXF separada por tipo (puntos o líneas), con su color y su interruptor (S040) |
| Elemento del dibujo | `CadEntity` | Un punto o una línea (polilínea, arco, círculo, spline) del dibujo, con los bloques ya desplegados (S040) |
| Con cota / sin cota | `hasElevation` | Un elemento del dibujo con altura propia, o sin ella (S040) |
| GeoPackage | `GeoPackage` (`.gpkg`) | Fichero SQLite con capas vectoriales (QGIS); se importa como un dibujo CAD, solo lectura (S048); los puntos del proyecto se exportan a uno (S053) |
| Capa de GeoPackage | `GpkgLayerInfo` | Una tabla del GeoPackage con geometría: su tipo, número de objetos, SRS y columnas (S048) |
| Shapefile | `Shapefile` | Conjunto de ficheros `.shp`, `.shx`, `.dbf`, `.prj` y `.cpg` con el mismo nombre; se importa de un ZIP o de ficheros sueltos como un dibujo CAD, solo lectura (S030), y los puntos, líneas y polígonos del proyecto se exportan a un ZIP con uno por tipo de geometría (S055) |
| KML/KMZ | `Kml` | Fichero XML de Google Earth (KMZ: ZIP con un KML y sus imágenes); en WGS84, tomado como el datum del proyecto; se importa como un dibujo CAD, solo lectura, y los puntos, líneas y polígonos del proyecto se exportan a uno (S031) |
| Carpeta de KML | `KmlFolder` | Un `Folder` o `Document` del KML, salvo el raíz; cada una con marcas es una capa de diseño, con su ruta como nombre (S031) |
| ZIP de fotos | `PhotoArchive` | ZIP con las fotos de los puntos, byte a byte como están guardadas, y su índice `fotos.csv` (S056) |
| Origen del dibujo | `CadOrigin` | `DXF`, `GEOPACKAGE`, `SHAPEFILE` o `KML`: de qué fichero viene un dibujo (S048, S030, S031) |
| Zona de importación | `ImportArea` | «Todo el contenido» o un radio de 100 m a 100 km alrededor del centro del proyecto (S048) |
| Contorno de polígono | `POLYGON_RING` | Línea cerrada que sale del exterior o de un hueco de un polígono importado (S048) |
| Campo de nombre | `nameField` | Columna de la capa con la que se etiqueta y se nombra cada objeto; sin ella, su número en la capa (S048) |
| Atributos del objeto | `GpkgValue` | Valores de las columnas de un objeto del GeoPackage, que se ven en su ficha (S048) |
| PK | `Chainage` | Distancia a lo largo de una línea (S041) o de un eje (S049) desde su primer punto más el PK inicial del eje, en el SC del proyecto |
| Desplazamiento lateral | `LateralOffset` | Distancia perpendicular a la línea a la que se quiere estar; positiva a la derecha del sentido de la línea (S041) |
| Distancia lateral | `CrossTrack` | Distancia perpendicular con signo entre la punta y la línea (S041) |
| Error lateral | `CrossTrackError` | Distancia lateral menos el desplazamiento lateral (S041) |
| Replanteo de línea | `LineStakeout` | Replanteo de una línea del dibujo (S041) o del proyecto (S027) con sus cinco modos |
| Modo de línea | `LineStakeoutMode` | Por segmentos, A la línea, A lo largo de la línea, Tramo A–B o Intervalos (S041, S027) |
| Línea de diseño | `DesignLine` | La línea del proyecto (S025) que se replantea; sus vértices son sus puntos (S027) |
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
| Perfil de equipo | `EquipmentProfile` | Modelo, n.º de serie, firmware (lo introduce el usuario); en la interfaz, la entrada «Datos del receptor» (S043) |
| Lista de configuración | `ConfigList` | Pasos ordenados para dejar el receptor listo para una tarea, con el ajuste y la pantalla del receptor; los comprobables llevan una comprobación (S043) |
| Comprobación previa | `ReceiverCheck` | Resultado Correcto, Aviso, Fallo o Sin datos de mirar las últimas épocas del receptor; nunca impide medir (S043) |
| Eje | `Alignment` | Trazado en planta de una obra lineal con rectas, arcos y clotoides, y su rasante si la tiene; referencia de diseño, importada o definida a mano (S049); también puede tomarse en campo con el receptor (S052) |
| Tramo del eje | `AlignmentElement` | Recta, arco o clotoide de un eje, con su curvatura de inicio y de fin (positiva a la izquierda) (S049) |
| Rasante | `VerticalProfile` | Perfil vertical de un eje: vértices con PK y cota y acuerdos verticales (S049) |
| Acuerdo vertical | `VerticalCurve` | Parábola simétrica o círculo que suaviza un vértice de la rasante (S049) |
| Kv | `Kv` | Parámetro de una parábola vertical: su longitud es Kv · \|Δi\| (S049) |
| Eje activo | `ActiveAlignment` | El eje del proyecto sobre el que se trabaja; uno o ninguno (S049) |
| Punto singular | `KeyStation` | PK en el que cambia el tipo de tramo del eje o el vértice de la rasante (S049, S054) |
| Espesor | `Thickness` | Capa de material sobre o bajo la rasante que se descuenta del desmonte o terraplén (S054) |
| Calculado sobre eje | `PointOrigin.ON_ALIGNMENT` | Origen de un punto guardado desde un PK y un desplazamiento de un eje (S049) |
| Sección de diseño | `CrossSection` (`SectionKind.DESIGN`) | Puntos con nombre de la plataforma en un PK, de izquierda a derecha del eje (S050) |
| Código de sección | `SectionCode` | Nombre de un punto de sección (hasta 12 caracteres); independiente de los códigos de levantamiento (S050) |
| Sección tipo | `SectionTemplate` | Sección definida por tramos desde el eje: ancho, pendiente y si sigue el peralte (S050) |
| Peralte | `Superelevation` | Tabla de PK con la pendiente transversal de cada lado; varía linealmente entre filas (S050) |
| Punto de rasante | `SectionTemplate.gradeCode` | Punto de la sección al que se refiere la rasante: el eje o un código; la sección gira alrededor de él (S050) |
| Perfil de terreno | `CrossSection` (`SectionKind.GROUND`) | Sección de terreno natural en un PK; la produce S052 y la usa S051 (S050) |
| Talud | `SlopeRule` | Plano inclinado que parte del borde de explanación; su relación es metros horizontales por cada metro vertical (1,5 es 3:2) (S051) |
| Punto de talud | `SlopeCatch` | Donde el talud corta el terreno: cabeza (`SlopeKind.CUT`, desmonte) o pie (`SlopeKind.FILL`, terraplén) (S051) |
| Borde de explanación | `SlopeEdge`, `ManualEdge` | Punto exterior de la sección de diseño de un lado, o el escrito a mano, desde el que parte el talud (S051) |
| Altura de talud | `SlopeStakeInfo.heightM` | Diferencia de cota entre el borde de explanación y el punto de talud (S051) |
| Desplazamiento de estaca | `SlopeStakeInfo.stakeOffsetM` | Distancia, hacia fuera sobre la sección, a la que se clava la estaca respecto al punto de talud (S051) |
| Toma | `LinearSurvey` | Sesión de medida de un eje, de un perfil longitudinal o de un perfil transversal; agrupa puntos normales del proyecto (S052) |
| Eje tomado | `AlignmentSource.FIELD` | Eje cuyas rectas pasan por los puntos medidos de una toma, con arcos solo donde se da el radio; origen «Tomado en campo» (S052) |
| Perfil longitudinal | `LinearSurveyKind.LONGITUDINAL` | Puntos del terreno con su PK y su cota a lo largo de un eje, comparados con su rasante (S052) |
| Perfil transversal | `LinearSurveyKind.CROSS` | Puntos del terreno de un PK ordenados por desplazamiento; al guardarlo es un perfil de terreno de S050 (S052) |
| Tolerancia de eje | `LinearSurvey.DEFAULT_AXIS_TOLERANCE_M` | Distancia máxima al eje (0,50 m por defecto) con que un punto entra en un perfil longitudinal (S052) |
| Tolerancia de sección | `LinearSurvey.DEFAULT_SECTION_TOLERANCE_M` | Distancia máxima a la línea de la sección (0,10 m por defecto) con que un punto es del PK (S052) |
| Capa del DXF | `DxfLayer` | Cada capa de dibujo de un DXF exportado: una por código y las de etiquetas (S029) |
| Color ACI | `AciColor` | Uno de los 255 colores indexados estándar de los CAD (S029) |
| Módulo experimental | `receiver-experimental` | Parte de la app que habla con la interfaz local no documentada del receptor; solo existe en el flavor `experimental` y está apagada de fábrica (S044) |
| Aviso legal | `ExperimentalNotice` | Texto que el usuario acepta al activar el módulo experimental: la interfaz no es oficial, su uso puede infringir los términos del fabricante y puede dejar de funcionar (S044) |
| LCC (Cónica Conforme de Lambert) | `LambertConic1Sp`, `LambertConic2Sp` | Proyección cónica conforme definida por un paralelo de origen con factor de escala (1SP) o por dos paralelos estándar (2SP) (S032) |
| Mercator (variantes A y B) | `MercatorA`, `MercatorB` | Proyección cilíndrica conforme definida por un factor de escala en el ecuador (A) o por un paralelo estándar de escala verdadera (B) (S032) |
| Oblique Mercator (OM) | `ObliqueMercatorA`, `ObliqueMercatorB` | Mercator Oblicua de Hotine: en la variante A el falso Este y Norte se dan en el origen natural y en la B en el centro de la proyección (S032) |
| Estereográfica | `ObliqueStereographic`, `PolarStereographicA`, `PolarStereographicB`, `PolarStereographicC` | Proyección conforme sobre un plano: oblicua (origen cualquiera) o polar en tres variantes (S032) |
| Meridiano de origen | `primeMeridianDeg` | Longitud respecto a Greenwich del meridiano desde el que un SC cuenta sus longitudes (Madrid, París, Lisboa…) (S032) |
| SC personalizado | `CustomCrs` | SC definido por el usuario con elipsoide, datum con su transformación, proyección, unidades y altura (S035) |
| Mis SC | `MyCrsList` | Lista de los SC personalizados guardados en el móvil, junto a la biblioteca (S035) |
| Historial del proyecto | `ProjectHistory` | Lista de los cambios de SC de un proyecto, con «Volver al SC anterior» (S035) |
| Coordenadas de cuadrícula | `GridCoordinates` | Las de la proyección del SC del proyecto, tal cual (S058) |
| Coordenadas de terreno | `GroundCoordinates` | Las de cuadrícula escaladas respecto a un origen con un factor único, para que las distancias sean las del terreno (S058) |
| Plano de terreno | `TerrainPlane` | El origen y el factor de un proyecto en coordenadas de terreno (S058) |
| Hoja de campo | `FieldSheet` | Hoja inferior no modal sobre el mapa, con dos alturas (plegada y expandida), que lleva los controles de recolectar o de replantear (S060) |
| Pastilla | `SettingPill` | Control de 56 dp con el nombre de un ajuste y su valor en texto; al tocarla se abre su selector (S060) |
| Placa de guiado | `GuidancePlate` | Zona de la hoja de replanteo con la flecha, la distancia, los desplazamientos y la diferencia de cota; en verde con «En el punto» dentro de las tolerancias (S060) |
| Motivo de bloqueo | `MeasureReason` | Línea que dice por qué «Medir» no se puede pulsar y la acción que lo resuelve con un toque (S060) |
| Barra de grupos | `GroupBar` | Fila de tres botones de igual ancho («Importar», «Exportar» y «Herramientas») bajo la barra superior de la lista de Objetos; cada uno abre su menú de grupo (S059) |
| Menú de grupo | `GroupMenu` | Menú corto, con una fila por opción (icono y nombre, y descripción en «Herramientas»), que abre cada botón de la barra de grupos (S059) |
