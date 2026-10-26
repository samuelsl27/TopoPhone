# Hoja de ruta de TopoPhone

Las specs se implementan **en el orden de las tablas**, no por su número: los números son identificadores estables y desde S017b no van seguidos (el replanteo y la importación de diseño pasan delante de las fotos). Cada hito termina con una puerta de validación de campo (`docs/validacion-campo/protocolo.md`).

Orden de trabajo actual del diseño importado: S019 → S028 → S040 → S041 → S042 → S045 (S017b, fotos, aparcada).

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
| 040 capa-cad-dxf | Lectura de DXF por streaming (R12–R2018, ≤ 100 MB), bloques (INSERT), arcos, círculos y splines como polilíneas, índice espacial; sus puntos y líneas se ven en el mapa como capa de diseño; los DWG no se leen (licencia, RNF-T14): se avisa y se convierten a DXF fuera; capas por tipo (puntos y líneas), varios dibujos por proyecto y cota opcional | 009 | implementada |
| 041 replanteo-cad | Replanteo de puntos, vértices, centros de bloque, líneas y segmentos del DXF; por segmentos, a la línea, a lo largo de la línea con desplazamiento, encadenamiento hasta A/B e intervalos (el motor de líneas, que S027 reutiliza para las líneas propias); con cota interpolada y vértices originales marcados por S040 | 040, 019 | implementada |
| 042 superficies-landxml | Importar superficies TIN de un LandXML (varias por proyecto, una activa), desplazamiento vertical, desmonte/terraplén respecto a la superficie en la posición del jalón, cota de diseño de la superficie para puntos y líneas sin cota, y cota bajo la punta en el recolector | 019, 041 | tareas |
| 045 raster-mdt | Importar un raster de cotas (GeoTIFF y ASCII Grid) como superficie, con su SC y su datum vertical, y replantear solo la cota: desmonte/terraplén contra el raster en la posición del jalón, con tolerancia; una superficie activa por proyecto, como las de LandXML | 042 | pendiente |
| 017b fotos-de-puntos | Fotos de los puntos, varias por punto: desde el recolector, durante la medida o justo después de guardar, y desde la ficha (ver, añadir y borrar con rastro en el historial); viajan con el punto en la papelera, las copias de seguridad y los paquetes | 017 | tareas |
| 020 base-shift | Desplazamiento ECEF (conocido − medido) aplicado a los puntos siguientes, registrado en cada punto y desactivable. Orden fijo: bruto → shift → SC → localización | 011, 010 | pendiente |
| 021 conexion-wifi-tcp | Cliente TCP (hotspot 192.168.42.1:9001 o IP de la red local), tráfico del receptor por Wi-Fi y NTRIP por datos móviles, descubrimiento mDNS, correcciones al puerto TCP de entrada del receptor, permiso de red local | 015 | pendiente |
| 022 gnss-movil-y-receptor-virtual | GNSS del propio móvil (nunca se presenta como FIX) y receptor virtual con escenarios guionizados | 021 | pendiente |

## H3 · Códigos y líneas

| Spec | Alcance | Depende de | Estado |
|---|---|---|---|
| 023 bibliotecas-codigos | Bibliotecas de códigos gestionadas en el móvil (código, descripción, geometría, color, atributos Text/Integer/Number/List), importar y exportar CSV, bibliotecas por defecto, códigos sin duplicados, biblioteca vinculada al proyecto | 006 | pendiente |
| 024 recolectar-con-codigos | Selector de códigos con recientes, códigos creados sobre la marcha (solo para el proyecto), formulario de atributos, cambio de código, símbolos por código | 023 | pendiente |
| 025 lineas-y-poligonos | Empezar o continuar una línea, cerrarla en polígono, crear una línea desde puntos existentes, editar vértices | 024 | pendiente |
| 026 informacion-geometrica | Longitud 2D/3D, área, perímetro, azimut, pendiente, tabla por segmento, ángulo entre 3 puntos, distancias de cuadrícula o de terreno | 025 | pendiente |
| 027 replanteo-lineas | Replanteo de las líneas propias del proyecto con el motor de líneas de S041: por segmentos, a la línea, a lo largo de la línea con desplazamiento, encadenamiento hasta A/B, intervalos | 025, 041 | pendiente |

## H4 · Intercambio de datos

| Spec | Alcance | Depende de | Estado |
|---|---|---|---|
| 029 exportar-dxf | DXF ASCII R12: capas por código, colores ACI, etiquetas, polilíneas 3D, coordenadas globales o locales | 025 | pendiente |
| 030 shapefile | Importar desde ZIP; exportar PointZ, PolyLineZ y PolygonZ con `.prj` y `.cpg` | 025 | pendiente |
| 031 kml-kmz-y-fotos | Importar y exportar KML/KMZ (WGS84), fotos en un ZIP | 025, 017b | pendiente |

## H5 · Sistemas de coordenadas mundiales y capas

| Spec | Alcance | Depende de | Estado |
|---|---|---|---|
| 032 proyecciones-completas | LCC 1SP/2SP y Oblique Mercator A/B (otras solo si el catálogo las necesita) | 008 | pendiente |
| 033 transformaciones-datum | Helmert 3/4/6/7 (PV y CF), Molodensky-Badekas 10, rejillas de desplazamiento horizontal (ED50 con SPED2ETV2 ya está en S008) | 032 | pendiente |
| 034 biblioteca-sc-mundial | Catálogo con búsqueda por país, código o nombre; ficha del SC; datums verticales; descarga de geoides con SHA-256 | 033 | pendiente |
| 035 sc-personalizado | Editor de SC personalizado, "Mis SC", importar y exportar JSON, cambio del SC del proyecto (mismo datum), cuadrícula o terreno | 034 | pendiente |
| 036 capas-wms | Biblioteca WMS en el móvil (GetCapabilities 1.1.1/1.3.0), presets españoles (IGN, Catastro, ICV, ICGC, GRAFCAN…), orden, visibilidad y opacidad por proyecto | 009 | pendiente |
| 037 mapas-sin-conexion | Descarga del mapa base y de capas WMS de la zona del proyecto, gestión del almacenamiento | 036 | pendiente |

## H6 · Herramientas Pro

| Spec | Alcance | Depende de | Estado |
|---|---|---|---|
| 038 cogo | Inversa, poligonal, intersecciones (distancia-distancia, dirección-dirección, distancia-dirección); los resultados se guardan como puntos de origen COGO | 011 | pendiente |
| 039 localizacion | Hasta 50 pares H/V, semejanza 2D por mínimos cuadrados con plano inclinado, residuos, exclusión de componentes, aplicar al proyecto y guardar como SC | 035 | pendiente |

## H7 · Receptor avanzado

| Spec | Alcance | Depende de | Estado |
|---|---|---|---|
| 043 receptor-guiado | Listas de configuración del receptor, comprobaciones previas, Reach Panel en un WebView, perfil de equipo | 016 | pendiente |
| 044 modulo-experimental-reach | Flavor `experimental` con aviso legal: lectura de información, batería y configuración, estado por Socket.IO, escritura de altura de antena e inicio/parada de logs | 043 | pendiente |

## Puertas de validación
| Puerta | Al cerrar | Qué se demuestra |
|---|---|---|
| G0 | S002 | FIX sobre un vértice REGENTE; las coordenadas en pantalla coinciden con pyproj aplicado al GGA grabado (≤ 1 mm) |
| G1 | S014 | Un trabajo real: CSV abierto en QGIS y comparado con Emlid Flow (≤ 2 cm H / 3 cm V) |
| G2 | S019 | Inclinación y replanteo: 5 puntos replanteados y comprobados después (≤ 2,5 cm); punta calculada frente a jalón vertical; replanteo de una cota de diseño |
| G2b | S045 | Diseño importado: puntos de un CSV y líneas de un DXF replanteados, y una cota replanteada contra una superficie LandXML y contra un raster, comprobadas después (≤ 2,5 cm H, ≤ 3 cm V) |
| G3–G7 | fin de cada hito | Según el protocolo de campo |
