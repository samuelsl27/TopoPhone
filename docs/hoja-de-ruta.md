# Hoja de ruta de TopoPhone

Las specs se implementan **en orden**. Cada hito termina con una puerta de validación de campo (`docs/validacion-campo/protocolo.md`).

Estados: `pendiente` → `spec` → `plan` → `tareas` → `en curso` → `implementada`.

Las secciones "Fuera de alcance" de cada spec apuntan a la spec posterior que cubre cada exclusión (`→ S0NN`).

## H0 · Trazadora RTK (cadena completa mínima)
Objetivo: demostrar cuanto antes RS3 → NTRIP → coordenadas ETRS89/UTM + EGM08-REDNAP.

| Spec | Alcance | Depende de | Estado |
|---|---|---|---|
| 001 esqueleto-app | Proyecto Gradle multimódulo con build-logic, interfaz Compose con navegación (Levantamiento / Receptor / Ajustes), tema con modo sol, recursos es/en, Hilt, infraestructura de tests, detekt/ktlint, CI, script de trazabilidad de RF | — | pendiente |
| 002 cadena-rtk-minima | Conexión SPP a un RS3 ya emparejado, lectura de GGA+GST, NTRIP v1/v2 con mountpoint manual, RTCM al mismo socket y GGA al caster, h = alt + N, ETRS89/UTM 28–31, EGM08-REDNAP, altura del jalón + 0,134 m (ARP del RS3), una sola pantalla. **Puerta G0** | 001 | pendiente |

## H1 · Primer levantamiento útil

| Spec | Alcance | Depende de | Estado |
|---|---|---|---|
| 003 flujo-nmea-y-simulador | Parser completo (GGA, GSA, GST, GSV, RMC, VTG, ZDA, EBP, ETC), épocas agrupadas por hora, detección de datos obsoletos, tramas RTCM3 (CRC24Q), transporte de replay (×1/×5, bucle), grabación de sesiones, componente de barra de estado | 002 | pendiente |
| 004 conexion-bluetooth-robusta | CompanionDeviceManager y emparejamiento con PIN, permisos con justificación, servicio `connectedDevice` con notificación, reconexión con espera exponencial, diagnóstico del caso FW33 (Emlid Flow conectado), wakelock y pantalla siempre encendida, último dispositivo recordado | 003 | pendiente |
| 005 ntrip-perfiles-y-sourcetable | Altas, bajas y edición de perfiles con contraseñas cifradas, descarga y lectura del sourcetable, mountpoints ordenados por distancia, envío de GGA según el campo `nmea`, v2 por bloques (chunked), contadores de mensajes RTCM, reconexión, alarma por edad de las correcciones | 004 | pendiente |
| 006 coordenadas-espana | ETRS89 UTM 28–31 (huso forzado o sugerido), REGCAN95 UTM 27/28, coordenadas geográficas, altura elipsoidal o EGM08-REDNAP (Península y Baleares, y Canarias), factor de escala y convergencia, vectores de referencia | 003 | pendiente |
| 007 proyectos | Altas, bajas y edición de proyectos, búsqueda, duplicado y carpetas; metadatos; SC del conjunto España; datum de las correcciones; unidades m/ft/usft y DD/DMS/gon; base de datos v1 con todos los tipos de objeto; banco de pruebas de migraciones | 006 | pendiente |
| 008 mapa-proyecto | Mapa vectorial + ortofoto PNOA, preset de Catastro, rover con círculo de precisión y color según la solución, capa de objetos, barra de escala, atribución, norte arriba | 007 | pendiente |
| 009 recolectar-puntos | Altura del jalón (1,8 m por defecto), control de precisión (solo FIX, límites H/V), modos instantáneo y promediado, nombres autoincrementales, descripción, metadatos de calidad, guardado atómico, sonido y vibración | 008, 005 | pendiente |
| 010 objetos-y-puntos-manuales | Lista de objetos (buscar, mostrar u ocultar, editar, duplicar, borrar), ficha del punto, punto manual (proyectado o geográfico), recálculo al corregir la altura de antena | 009 | pendiente |
| 011 exportar-csv | Todas las columnas, PENZD/PNEZD, separador y decimal configurables, selección de columnas, coordenadas globales o locales, guardar (SAF) o compartir | 010 | pendiente |
| 012 copia-seguridad-local | ZIP de copia completa con manifiesto y restauración, copias automáticas en una carpeta SAF, paquetes `.topoproj` para importar y exportar entre dispositivos. **Puerta G1** | 011 | pendiente |

## H2 · Receptor completo, inclinación y replanteo

| Spec | Alcance | Depende de | Estado |
|---|---|---|---|
| 013 estado-receptor-detallado | Pantalla de estado: posición, DOP, skyplot (GSV), SNR por constelación y señal, base (EBP o RTCM 1005/1006), línea base, SNR de la base (MSM) | 012 | pendiente |
| 014 inclinacion-rs3 | Estados y avisos de ETC, guía de inicialización con el movimiento en ocho, punta del jalón calculada en la app (por defecto) o compensada por el receptor, asistente que detecta el modo, bloqueo sin estado 30 + FIX, inclinación y rumbo guardados por punto, precisión con los coeficientes de ETC | 013, 009 | pendiente |
| 015 recolectar-avanzado | Modo automático por tiempo o distancia, promediado de hasta 60 min con estadísticas en vivo, fotos por punto | 014 | pendiente |
| 016 replanteo-puntos | Replanteo por dirección (rumbo de marcha o del IMU) y por distancia (N/E), zoom automático, diana métrica (verde ≤ 2,5 cm, configurable), ayuda visual ≤ 40 cm, anterior/siguiente, avisos de FIX y de inclinación, sonido, punto replanteado `_stkd` | 014 | pendiente |
| 017 replanteo-cota-e-informe | Cota de diseño (desmonte/terraplén con tolerancia) e informe de replanteo CSV | 016 | pendiente |
| 018 base-shift | Desplazamiento ECEF (conocido − medido) aplicado a los puntos siguientes, registrado en cada punto y desactivable. Orden fijo: bruto → shift → SC → localización | 010, 009 | pendiente |
| 019 conexion-wifi-tcp | Cliente TCP (hotspot 192.168.42.1:9001 o IP de la red local), tráfico del receptor por Wi-Fi y NTRIP por datos móviles, descubrimiento mDNS, correcciones al puerto TCP de entrada del receptor, permiso de red local | 013 | pendiente |
| 020 gnss-movil-y-receptor-virtual | GNSS del propio móvil (nunca se presenta como FIX) y receptor virtual con escenarios guionizados | 019 | pendiente |

## H3 · Códigos y líneas

| Spec | Alcance | Depende de | Estado |
|---|---|---|---|
| 021 bibliotecas-codigos | Bibliotecas de códigos gestionadas en el móvil (código, descripción, geometría, color, atributos Text/Integer/Number/List), importar y exportar CSV, bibliotecas por defecto, códigos sin duplicados, biblioteca vinculada al proyecto | 007 | pendiente |
| 022 recolectar-con-codigos | Selector de códigos con recientes, códigos creados sobre la marcha (solo para el proyecto), formulario de atributos, cambio de código, símbolos por código | 021 | pendiente |
| 023 lineas-y-poligonos | Empezar o continuar una línea, cerrarla en polígono, crear una línea desde puntos existentes, editar vértices | 022 | pendiente |
| 024 informacion-geometrica | Longitud 2D/3D, área, perímetro, azimut, pendiente, tabla por segmento, ángulo entre 3 puntos, distancias de cuadrícula o de terreno | 023 | pendiente |
| 025 replanteo-lineas | Por segmentos, a la línea, a lo largo de la línea con desplazamiento, encadenamiento hasta A/B, intervalos | 023, 016 | pendiente |

## H4 · Intercambio de datos

| Spec | Alcance | Depende de | Estado |
|---|---|---|---|
| 026 importar-csv-txt | Asignación de columnas, PENZD/PNEZD, UTF-8 o Windows-1252, coma decimal, vista previa, informe de errores, nombres repetidos | 010 | pendiente |
| 027 exportar-dxf | DXF ASCII R12: capas por código, colores ACI, etiquetas, polilíneas 3D, coordenadas globales o locales | 023 | pendiente |
| 028 shapefile | Importar desde ZIP; exportar PointZ, PolyLineZ y PolygonZ con `.prj` y `.cpg` | 023 | pendiente |
| 029 kml-kmz-y-fotos | Importar y exportar KML/KMZ (WGS84), fotos en un ZIP | 023, 015 | pendiente |

## H5 · Sistemas de coordenadas mundiales y capas

| Spec | Alcance | Depende de | Estado |
|---|---|---|---|
| 030 proyecciones-completas | LCC 1SP/2SP y Oblique Mercator A/B (otras solo si el catálogo las necesita) | 006 | pendiente |
| 031 transformaciones-datum | Helmert 3/4/6/7 (PV y CF), Molodensky-Badekas 10, rejillas de desplazamiento horizontal (SPED2ETV2 para ED50) | 030 | pendiente |
| 032 biblioteca-sc-mundial | Catálogo con búsqueda por país, código o nombre; ficha del SC; datums verticales; descarga de geoides con SHA-256 | 031 | pendiente |
| 033 sc-personalizado | Editor de SC personalizado, "Mis SC", importar y exportar JSON, cambio del SC del proyecto (mismo datum), cuadrícula o terreno | 032 | pendiente |
| 034 capas-wms | Biblioteca WMS en el móvil (GetCapabilities 1.1.1/1.3.0), presets españoles (IGN, Catastro, ICV, ICGC, GRAFCAN…), orden, visibilidad y opacidad por proyecto | 008 | pendiente |
| 035 mapas-sin-conexion | Descarga del mapa base y de capas WMS de la zona del proyecto, gestión del almacenamiento | 034 | pendiente |

## H6 · Herramientas Pro

| Spec | Alcance | Depende de | Estado |
|---|---|---|---|
| 036 cogo | Inversa, poligonal, intersecciones (distancia-distancia, dirección-dirección, distancia-dirección); los resultados se guardan como puntos de origen COGO | 010 | pendiente |
| 037 localizacion | Hasta 50 pares H/V, semejanza 2D por mínimos cuadrados con plano inclinado, residuos, exclusión de componentes, aplicar al proyecto y guardar como SC | 033 | pendiente |
| 038 capa-cad-dxf | Lectura de DXF por streaming (R12–R2018, ≤ 100 MB), bloques (INSERT), arcos, círculos y splines como polilíneas, índice espacial | 027, 008 | pendiente |
| 039 replanteo-cad | Replanteo de puntos, vértices, centros de bloque, líneas y segmentos del DXF | 038, 025 | pendiente |
| 040 superficies-landxml | Importar una superficie TIN (una activa), desplazamiento vertical, desmonte/terraplén respecto a la superficie | 017 | pendiente |

## H7 · Receptor avanzado

| Spec | Alcance | Depende de | Estado |
|---|---|---|---|
| 041 receptor-guiado | Listas de configuración del receptor, comprobaciones previas, Reach Panel en un WebView, perfil de equipo | 014 | pendiente |
| 042 modulo-experimental-reach | Flavor `experimental` con aviso legal: lectura de información, batería y configuración, estado por Socket.IO, escritura de altura de antena e inicio/parada de logs | 041 | pendiente |

## Puertas de validación
| Puerta | Al cerrar | Qué se demuestra |
|---|---|---|
| G0 | S002 | FIX sobre un vértice REGENTE; las coordenadas en pantalla coinciden con pyproj aplicado al GGA grabado (≤ 1 mm) |
| G1 | S012 | Un trabajo real: CSV abierto en QGIS y comparado con Emlid Flow (≤ 2 cm H / 3 cm V) |
| G2 | S020 | Inclinación y replanteo: 5 puntos replanteados y comprobados después (≤ 2,5 cm); punta calculada frente a jalón vertical |
| G3–G7 | fin de cada hito | Según el protocolo de campo |
