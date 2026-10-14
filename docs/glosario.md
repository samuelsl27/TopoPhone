# Glosario español ↔ código

Términos de la interfaz (español) y su nombre en el código (inglés). Si un concepto nuevo no aparece aquí, se añade antes de usarlo.

| Interfaz (es) | Código (en) | Nota |
|---|---|---|
| Proyecto | `Project` | |
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
