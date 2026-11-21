# Hoja de ruta de TopoPhone

Las specs se implementan **en el orden de las tablas**, no por su número: los números son identificadores estables y desde S017b no van seguidos (el replanteo y la importación de diseño pasan delante de las fotos). Cada hito termina con una puerta de validación de campo (`docs/validacion-campo/protocolo.md`).

Ampliación de S006 (punto central del proyecto, RF-32 a RF-38): implementada el 27/10/2026 (Room v18). S048 (GeoPackage) está implementada (Room v19) y la usa. S049 (ejes y rasantes) está implementada (Room v20). S054 (replanteo por eje) está implementada (Room v21). S053 (exportar GeoPackage) está implementada (sin cambios de Room). S045 (ráster de cotas) está implementada (Room v22). S020 (base shift) está implementada (Room v23). S017b (fotos) está implementada (Room v24). S050 (peraltes y secciones transversales) está implementada (Room v25). S051 (taludes) está implementada (Room v26). S052 (toma de datos lineal) está implementada (Room v27). S023 (bibliotecas de códigos) está implementada (Room v28). S024 (recolectar con códigos) está implementada (Room v29). S025 (líneas y polígonos) está implementada (Room v30). S026 (información geométrica) está implementada (sin cambios de Room). S027 (replanteo de líneas) está implementada (Room v31). S029 (exportar DXF) está implementada (sin cambios de Room). S030 (importar Shapefile) está implementada (sin cambios de Room, v31). S055 (exportar Shapefile) está implementada (sin cambios de Room, v31). S031 (importar y exportar KML/KMZ) está implementada (sin cambios de Room, v31). S056 (exportar las fotos de los puntos a un ZIP) está implementada (sin cambios de Room, v31). S036 (capas WMS) está implementada (Room v32). S037 (mapas sin conexión) está implementada (sin cambios de Room, v32). S038 (COGO: inversa, poligonal e intersecciones) está implementada (Room v33). S021 (conexión Wi-Fi/TCP con receptores añadidos a mano) está implementada (Room v34). S022 (GNSS del móvil y receptor virtual) está implementada (Room v35). S043 (receptor guiado: listas de configuración, comprobaciones previas, interfaz web del receptor y perfil de equipo) está implementada (Room v36).

Orden de trabajo actual del diseño importado: S019 → S028 → S040 → S041 → S042 → S045 (S017b, fotos, hecha después).

Las specs `pendiente` tienen un borrador sin entrevista en `specs/pendientes/` (índice y conflictos en su `README.md`); al tomar una se mueve a `specs/NNN-slug` y pasa a `spec`.

Estados: `pendiente` → `spec` → `plan` → `tareas` → `en curso` → `implementada`.

Las secciones "Fuera de alcance" de cada spec apuntan a la spec posterior que cubre cada exclusión (`→ S0NN`).

## H0 · Trazadora RTK (cadena completa mínima)
Objetivo: demostrar cuanto antes RS3 → NTRIP → coordenadas ETRS89/UTM + EGM08-REDNAP.

| Spec | Alcance | Depende de | Estado |
|---|---|---|---|
| 001 esqueleto-app | Proyecto Gradle multimódulo con build-logic, interfaz Compose con navegación (Levantamiento / Receptor / Ajustes), tema con modo sol, recursos es/en, Hilt, infraestructura de tests, detekt/ktlint, CI, script de trazabilidad de RF | — | en curso |
| 002 cadena-rtk-minima | Conexión SPP a un RS3 ya emparejado, lectura de GGA+GST, NTRIP v1/v2 con mountpoint manual, RTCM al mismo socket y GGA al caster, h = alt + N, ETRS89/UTM 28–31, EGM08-REDNAP, altura del jalón + 0,134 m (ARP del RS3), una sola pantalla. **Puerta G0** | 001 | en curso |

## H1 · Primer levantamiento útil

| Spec | Alcance | Depende de | Estado |
|---|---|---|---|
| 003 flujo-nmea-y-simulador | Parser completo (GGA, GSA, GST, GSV, RMC, VTG, ZDA, EBP, ETC), épocas agrupadas por hora, detección de datos obsoletos, transporte de replay (×1/×5, bucle) de sesiones y de ficheros NMEA importados, grabación de sesiones con correcciones y límite de espacio, componente de barra de estado | 002 | en curso |
| 004 conexion-bluetooth-robusta | CompanionDeviceManager y emparejamiento con PIN, permisos con justificación, servicio `connectedDevice` con notificación, reconexión con espera exponencial, diagnóstico del caso FW33 (Emlid Flow conectado), wakelock, ayuda para los fabricantes que cierran apps (RNF-T2), último dispositivo recordado y conexión automática al abrir | 003 | en curso |
| 005 estructura-interfaz | Organización de la interfaz como la app de referencia, con diseño propio: Receptor como lista de receptores y, con uno conectado, cabecera y menú de pantallas (Estado, Coordenadas, Correcciones, Sesiones grabadas) con resumen; indicador de estado en el extremo derecho de la barra superior; reubicación de todo lo de S002–S004 | 004 | en curso |
| 006 proyectos | Lista de proyectos en Levantamiento; altas, bajas y edición de proyectos, búsqueda, duplicado y carpetas; papelera de 30 días; vista de proyecto (sin mapa hasta S009); metadatos; SC ETRS89/UTM con EGM08-REDNAP (los demás de España llegan en S008); datum de las correcciones; unidades m/ft/usft y DD/DMS/gon; almacenamiento de proyectos con banco de pruebas de migraciones (cada spec posterior añade sus datos con una migración probada) | 005 | implementada |
| 007 ntrip-perfiles-y-sourcetable | Altas, bajas y edición de perfiles con contraseñas cifradas, casters públicos de España (IGN y redes autonómicas), descarga y lectura del sourcetable, mountpoints ordenados por distancia, envío de GGA según el campo `nmea`, TLS opcional, tramas RTCM3 (CRC24Q) y contadores de mensajes RTCM, reconexión y arranque automáticos, alarma por edad de las correcciones (v2 por bloques ya está en S002) | 005 | en curso |
| 008 coordenadas-espana | ETRS89 UTM 29–31 y geográficas, REGCAN95 UTM 27/28 y geográficas, ED50 UTM 29–31 (rejilla SPED2ETV2, adelantada desde S033), altitud EGM08-REDNAP (Península y Baleares, y Canarias) o altura elipsoidal, huso propuesto al crear el proyecto y aviso fuera de él, factor de escala, convergencia y factor combinado, vectores de referencia; estos SC disponibles en los proyectos | 006 | implementada |
| 009 mapa-proyecto | Mapa a pantalla completa en la vista de proyecto con panel de posición y datos del proyecto; mapas base vectoriales del IGN y de OpenStreetMap, ortofoto PNOA y Catastro superpuesto; caché para trabajar sin cobertura; rover con círculo de precisión y color según la solución; barra de escala, atribución, norte arriba | 006, 008 | en curso |
| 010 recolectar-puntos | Altura del jalón (1,8 m por defecto), control de precisión (solo FIX, límites H/V), modos instantáneo y promediado de hasta 60 s, nombres autoincrementales, descripción, metadatos de calidad, guardado atómico, sonido y vibración, pantalla siempre encendida (RNF-T2), puntos en el mapa (capa de objetos) | 009, 007 | en curso |
| 011 objetos-y-puntos-manuales | Lista de objetos (buscar, mostrar u ocultar, editar con historial de cambios, duplicar, borrar a la papelera de puntos), ficha del punto, punto manual (proyectado o geográfico), recálculo al corregir la altura de antena | 010 | en curso |
| 012 exportar-csv | Todos los puntos o una selección; todas las columnas, PENZD/PNEZD con cabecera, separador y decimal configurables, selección de columnas, coordenadas globales o locales, UTF-8; guardar (SAF) o compartir | 011 | en curso |
| 013 copia-seguridad-local | ZIP de copia completa con restauración y deshacer, copias automáticas en una carpeta SAF (cada hora con cambios, las 24 últimas), paquetes `.topoproj` para importar y exportar entre dispositivos, perfiles NTRIP sin contraseñas | 012 | en curso |
| 014 log-e-informe-de-sistema | Log de la app (rotativo, de 5 a 50 MB elegidos en Ajustes, sin secretos) e informe de sistema exportable con el log, las sesiones recientes (hasta 25 MB) y los datos de la app y del móvil, sin contraseñas ni usuarios NTRIP (RNF-T12). **Puerta G1** | 013 | en curso |

## H2 · Receptor completo, inclinación, replanteo e importación de diseño

| Spec | Alcance | Depende de | Estado |
|---|---|---|---|
| 015 estado-receptor-detallado | Pantalla de estado: posición, DOP, skyplot (GSV), SNR del rover y de la base por satélite y banda (MSM), base (EBP o RTCM 1005/1006) y línea base, con avisos de línea base larga y de cambio de base; la base en el mapa y guardada en cada punto (ficha y CSV) | 014 | en curso |
| 016 inclinacion-rs3 | Modo de inclinación por receptor (calculada por la app, compensada por el receptor o jalón vertical) con un asistente que lo detecta con una prueba de inclinación; estados y avisos de ETC con la guía de inicialización en ocho; punta del jalón en el mapa y en las coordenadas; bloqueo sin estado 30 y descarte de épocas con aviso; condición de inclinación máxima; precisión con los coeficientes de ETC; inclinación y rumbo guardados por punto (ficha y CSV) | 015, 010 | en curso |
| 017 recolectar-avanzado | Modo automático por tiempo o distancia con omisión de los puntos que no cumplen el control de precisión; promediado de hasta 60 min con estadísticas en vivo (desviación típica, estabilidad de la media y gráfico de dispersión), pausa ante los cortes y datos guardados en la ficha y el CSV | 016 | en curso |
| 018 replanteo-puntos | Replanteo por dirección (rumbo de marcha o del IMU) y por distancia (N/E), zoom automático, diana métrica (verde ≤ 2,5 cm, configurable), ayuda visual ≤ 40 cm, anterior/siguiente, avisos de FIX y de inclinación, sonido, punto replanteado `_stkd`, pantalla siempre encendida (RNF-T2) | 016 | implementada |
| 019 replanteo-cota-e-informe | Cota de diseño (desmonte/terraplén con tolerancia) e informe de replanteo CSV | 018 | implementada |
| 028 importar-csv-txt | Asignación de columnas, PENZD/PNEZD, UTF-8 o Windows-1252, coma decimal, vista previa, informe de errores, nombres repetidos | 011 | implementada |
| 040 capa-cad-dxf | Lectura de DXF por streaming (R12–R2018, ≤ 100 MB), bloques (INSERT), arcos, círculos y splines como polilíneas, índice espacial; sus puntos y líneas se ven en el mapa como capa de diseño; los DWG no se leen (licencia, RNF-T14): se avisa y se convierten a DXF fuera; capas por tipo (puntos y líneas), varios dibujos por proyecto y cota opcional; su aviso de distancia usa el centro del proyecto de S006/RF-37; S048 le añade el origen del dibujo (DXF o GeoPackage), el nombre de cada elemento, la marca de contorno de polígono y los atributos | 009 | implementada |
| 041 replanteo-cad | Replanteo de puntos, vértices, centros de bloque, líneas y segmentos del DXF; por segmentos, a la línea, a lo largo de la línea con desplazamiento, encadenamiento hasta A/B e intervalos (el motor de líneas, que S027 reutiliza para las líneas propias); con cota interpolada y vértices originales marcados por S040 | 040, 019 | implementada |
| 042 superficies-landxml | Importar superficies TIN de un LandXML (varias por proyecto, una activa), desplazamiento vertical, desmonte/terraplén respecto a la superficie en la posición del jalón, cota de diseño de la superficie para puntos y líneas sin cota, y cota bajo la punta en el recolector | 019, 041 | implementada |
| 045 raster-mdt | Importar un raster de cotas (GeoTIFF y ASCII Grid) como superficie, con su SC y su datum vertical, y replantear solo la cota: desmonte/terraplén contra el raster en la posición del jalón, con tolerancia; una superficie activa por proyecto, como las de LandXML; el ráster convierte entre altitud ortométrica y elipsoidal con el geoide | 042 | implementada |
| 017b fotos-de-puntos | Fotos de los puntos, varias por punto: desde el recolector, durante la medida o justo después de guardar, y desde la ficha (ver, añadir y borrar con rastro en el historial); viajan con el punto en la papelera, las copias de seguridad y los paquetes | 017 | implementada |
| 020 base-shift | Desplazamiento ECEF (conocido − medido) aplicado a los puntos siguientes, registrado en cada punto y desactivable. Orden fijo: bruto → punta del jalón → shift → SC → localización | 010, 011, 008, 015, 016, 018, 046, 047 | implementada |
| 021 conexion-wifi-tcp | Cliente TCP (hotspot 192.168.42.1:9001 o IP de la red local) con el receptor añadido a mano, con identidad propia que sobrevive a cambiar su IP; tráfico del receptor por Wi-Fi y NTRIP por datos móviles; correcciones al puerto TCP de entrada del receptor; permiso de red local | 015, 004, 005, 007 | implementada |
| 022 gnss-movil-y-receptor-virtual | GNSS del propio móvil (nunca se presenta como FIX) y receptor virtual con escenarios guionizados | 003, 005, 047 | implementada |
| 046 antena-y-jalon | Catálogo de modelos Reach con su desplazamiento al centro de fase (valores de Emlid) y perfiles de antena propios, jalones guardados con nombre y jalón activo por receptor, medida vertical o inclinada, selector rápido en el recolector, el replanteo y el receptor; el jalón y la antena se guardan en cada punto y un cambio solo afecta a los siguientes | 016, 011 | implementada |
| 047 tipos-de-solucion | Tipos de solución admitidos (FIX, FLOAT, SINGLE) en lugar de "solo FIX", con confirmación al admitir FLOAT o SINGLE, promediado con las épocas del mejor tipo, aviso de solución degradada y tipo guardado en cada punto | 010, 017, 018 | implementada |

## H3 · Códigos y líneas

| Spec | Alcance | Depende de | Estado |
|---|---|---|---|
| 023 bibliotecas-codigos | Bibliotecas de códigos gestionadas en el móvil (código, descripción, geometría, color, atributos Text/Integer/Number/List), importar y exportar CSV, bibliotecas por defecto, códigos sin duplicados, biblioteca vinculada al proyecto | 006, 013, 028 | implementada |
| 024 recolectar-con-codigos | Selector de códigos con recientes, códigos creados sobre la marcha (solo para el proyecto), formulario de atributos, cambio de código, símbolos por código | 023, 010, 011 | implementada |
| 025 lineas-y-poligonos | Empezar o continuar una línea, cerrarla en polígono, crear una línea desde puntos existentes, editar vértices | 024, 011 | implementada |
| 026 informacion-geometrica | Longitud 2D/3D, área, perímetro, azimut, pendiente, tabla por segmento, ángulo entre 3 puntos, distancias de cuadrícula o de terreno | 025, 040, 008 | implementada |
| 027 replanteo-lineas | Replanteo de las líneas propias del proyecto con el motor de líneas de S041: por segmentos, a la línea, a lo largo de la línea con desplazamiento, encadenamiento hasta A/B, intervalos | 025, 041, 049 | implementada |

## H4 · Intercambio de datos

| Spec | Alcance | Depende de | Estado |
|---|---|---|---|
| 029 exportar-dxf | DXF ASCII R12: capas por código, colores ACI, etiquetas, polilíneas 3D, coordenadas globales o locales | 012 (códigos, líneas y polígonos: 024, 025) | implementada |
| 030 shapefile | Importar un Shapefile desde un ZIP o desde ficheros sueltos como capa de diseño (capas marcables, SC leído del `.prj` y reproyectado, recorte a una zona alrededor del centro del proyecto, atributos y campo de nombre, replanteo con S041) | 048, 040, 006 (punto central) | implementada |
| 031 kml-kmz | Importar un KML o KMZ como capa de diseño (una capa por carpeta, color y atributos, altitudes opcionales, recorte a una zona alrededor del centro del proyecto, replanteo con S041) y exportar los puntos, líneas y polígonos a KML o KMZ, con carpetas y color por código y fotos opcionales dentro del KMZ; WGS84 tomado como el datum del proyecto | 048, 040, 006 (punto central), 012, 024, 025, 017b | implementada |
| 048 geopackage | Importar un `.gpkg` de QGIS como capa de diseño (capas marcables, SC leído y reproyectado, recorte a una zona alrededor del centro del proyecto, atributos y campo de nombre, replanteo con S041) | 040, 006 (punto central) | implementada |
| 053 exportar-geopackage | Exportar los puntos del proyecto, y sus líneas y polígonos cuando exista S025, a un `.gpkg` con su SC declarado y los datos de calidad como atributos | 012 (líneas y polígonos: 025) | implementada |
| 055 exportar-shapefile | Exportar los puntos del proyecto, y sus líneas y polígonos cuando exista S025, a un ZIP con `PointZ`, `PolyLineZ` y `PolygonZ`, con `.prj` y `.cpg`, nombres de columna abreviados y los datos de calidad como atributos | 012, 024, 025, 030 | implementada |
| 056 exportar-fotos | Exportar las fotos de los puntos del proyecto, o de una selección, a un ZIP, byte a byte como están guardadas, con un índice `fotos.csv` que las relaciona con sus puntos | 017b, 012 | implementada |

## H5 · Sistemas de coordenadas mundiales y capas

| Spec | Alcance | Depende de | Estado |
|---|---|---|---|
| 032 proyecciones-completas | LCC 1SP/2SP, Mercator A/B, Oblique Mercator A/B, Estereográfica oblicua y polar A/B/C y meridiano de origen distinto de Greenwich (otras solo si el catálogo las necesita; sin demo visible) | 008 | implementada |
| 033 transformaciones-datum | Helmert 3/4/6/7 (PV y CF), Molodensky-Badekas 10, rejillas de desplazamiento horizontal (ED50 con SPED2ETV2 ya está en S008; el meridiano de origen ya lo resolvió S032) | 032, 008 | implementada |
| 034 biblioteca-sc-mundial | Catálogo con búsqueda por país, código o nombre; ficha del SC; datums verticales; descarga de geoides y de rejillas de desplazamiento horizontal con SHA-256 | 033, 032, 006 | spec |
| 057 formatos-con-sc-de-biblioteca | Importar y exportar DXF, Shapefile, GeoPackage, KML/KMZ, LandXML y ráster en un proyecto con un SC de la biblioteca de S034, y reconocer esos SC por su EPSG al leer un `.prj`, un `.gpkg` o un KML (decidido en la aprobación de S034, 21/11/2026) | 034 | pendiente |
| 035 sc-personalizado | Editor de SC personalizado, "Mis SC", importar y exportar JSON, cambio del SC del proyecto (mismo datum), cuadrícula o terreno | 034, 033, 032 | pendiente |
| 036 capas-wms | Biblioteca WMS en el móvil (GetCapabilities 1.1.1/1.3.0), presets españoles (IGN, Catastro, ICV con sus ortofotos históricas, ICGC, GRAFCAN, Andalucía, Navarra, País Vasco, Madrid, Galicia…), orden, visibilidad y opacidad por proyecto (también en la copia y el paquete), y menú de pulsación larga del mapa con la consulta de información de las capas y las coordenadas del punto | 009, 008, 013 | implementada |
| 037 mapas-sin-conexion | Descarga del mapa base (IGN, PNOA y OpenFreeMap con su tope) y de las capas WMS por teselas de una zona del proyecto (radio, encuadre o todo el proyecto con margen), con tamaño previo, pausa y reanudación, y gestión del almacenamiento | 036, 009, 006 (centro del proyecto) | implementada |

## H6 · Herramientas Pro

| Spec | Alcance | Depende de | Estado |
|---|---|---|---|
| 038 cogo | Inversa, poligonal (tramos por azimut, ángulo o deflexión, con el borrador guardado en el proyecto), intersecciones (distancia-distancia, dirección-dirección, distancia-dirección); los resultados se guardan como puntos de origen COGO | 011, 008, 026 | implementada |
| 039 localizacion | Hasta 50 pares H/V, semejanza 2D por mínimos cuadrados con plano inclinado, residuos, exclusión de componentes, aplicar al proyecto y guardar como SC | 035, 011, 008, 020 (orden de cálculo) | pendiente |

## H7 · Receptor avanzado

| Spec | Alcance | Depende de | Estado |
|---|---|---|---|
| 043 receptor-guiado | Listas de configuración del receptor, comprobaciones previas, Reach Panel en un WebView, perfil de equipo | 016, 015, 021, 022, 046, 005 | implementada |
| 044 modulo-experimental-reach | Flavor `experimental` con aviso legal: búsqueda mDNS de receptores en la red, lectura de información, batería y configuración, estado por Socket.IO, escritura de altura de antena e inicio/parada de logs | 043, 021, 046 | implementada |

## H8 · Obra lineal

| Spec | Alcance | Depende de | Estado |
|---|---|---|---|
| 049 ejes-y-rasantes | Ejes en planta (rectas, arcos y clotoides) y rasantes con acuerdos desde LandXML, polilíneas de un DXF, CSV de PK y cota o a mano; varios ejes por proyecto con uno activo; marcas de PK en el mapa; puntos sobre eje | 040, 042, 006 | implementada |
| 054 replanteo-por-eje | Replanteo por PK y desplazamiento con intervalo, puntos singulares, espesor y cota de la rasante, y análisis en vivo de la posición del jalón respecto al eje | 049, 018, 019 | implementada |
| 050 peraltes-y-transversales | Peraltes y perfiles transversales (secciones tipo con códigos), cota con peralte y replanteo por PK y código con interpolación entre PK, y el perfil de terreno que usan S051 y S052 | 054, 049 | implementada |
| 051 taludes | Replanteo de cabezas y pies de talud contra los perfiles transversales o una sección manual, con seguimiento en alzado | 050, 054, 042 (opcional) | implementada |
| 052 toma-de-datos-lineal | Tomar un eje con el receptor, perfil longitudinal y perfil transversal a lo largo del eje | 054, 050, 049, 010, 017 | implementada |

## Puertas de validación
| Puerta | Al cerrar | Qué se demuestra |
|---|---|---|
| G0 | S002 | FIX sobre un vértice REGENTE; las coordenadas en pantalla coinciden con pyproj aplicado al GGA grabado (≤ 1 mm) |
| G1 | S014 | Un trabajo real: CSV abierto en QGIS y comparado con Emlid Flow (≤ 2 cm H / 3 cm V) |
| G2 | S019 | Inclinación y replanteo: 5 puntos replanteados y comprobados después (≤ 2,5 cm); punta calculada frente a jalón vertical; replanteo de una cota de diseño |
| G2b | S045 | Diseño importado: puntos de un CSV y líneas de un DXF replanteados, y una cota replanteada contra una superficie LandXML y contra un raster, comprobadas después (≤ 2,5 cm H, ≤ 3 cm V) |
| G3–G7 | fin de cada hito | Según el protocolo de campo |
