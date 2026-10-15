# Inventario funcional: Emlid Flow + Reach RS3 → TopoPhone

Recopilado en octubre de 2026 a partir de la documentación oficial (Emlid Flow 15.11, firmware Reach 33.1), del manual en PDF (`docs/referencias/emlid/`) y de la especificación ETC_rev_3. Es una referencia de **qué** hace la app de referencia, no un requisito: lo que se construye lo deciden las specs.

La columna **TopoPhone** indica cómo se cubre cada función:
- `S0NN`: la spec que la implementa.
- `Guía`: se configura en Emlid Flow o Reach Panel, con una checklist o asistente en S042.
- `Exp`: módulo experimental, S043.
- `—`: fuera de alcance.

Las claves de fuente se resuelven al final.

## A. Conexión y gestión del receptor
| Función | Fuente | TopoPhone |
|---|---|---|
| Conexión BLE (FW ≥ 32.2), NTRIP por BLE | R-conn | — (protocolo no documentado). Se sustituye por SPP: S002/S004 |
| Bluetooth Classic SPP con PIN (salida de posición y entrada de correcciones) | R-mock, R-fg | S002, S004 |
| Una sola conexión Bluetooth a la vez (FW 33) | R-cl | S004 (diagnóstico) |
| Wi-Fi: hotspot `reach:XX:XX` / 192.168.42.1; modo cliente en 2,4 GHz | R-conn, R-wifi | S020 (TCP), Guía (configurar Wi-Fi) |
| Reach Panel en el navegador, en la IP del receptor | R-rp | S042 (WebView) |
| Nombre del receptor, LED de identificación, info, n.º de serie | R-lora, R-sn | S042 (perfil de equipo), Exp (lectura) |
| Actualización de firmware, versiones beta, volver a la estable | R-upd | Guía |
| Reiniciar, apagar, restaurar ajustes | F-acl, R-rst | Guía |
| Ajustes Bluetooth (visibilidad, dispositivos emparejados) | R-mock | Guía |
| Datos móviles del receptor (SIM, APN) | R-lte | Guía |
| Encendido automático, modo nocturno, pitidos | R-pwr, F-gl | Guía |
| Receptor virtual | F-acl | S021 |
| Ubicación del móvil (1–3 m) | F-mob | S021 |
| Informe de sistema, depuración de datos brutos | R-fsr, R-ssr, R-dbg | S042 (informe propio, RNF-T12); Guía para el del receptor |

## B. Estado y monitorización
| Función | Fuente | TopoPhone |
|---|---|---|
| Barra de estado: SINGLE/FLOAT/FIX, satélites, batería, grabación, inclinación, correcciones | R-ntrip, R-lte, R-tilt | S002 (mínima), S003 (componente); batería → Exp |
| Pantalla de estado: posición, precisión, satélites, PDOP, SNR rover/base, edad, línea base | R-gl, EarthScope | S014 |
| Skyplot (Flow no lo tiene; solo Reach Panel) | Foro: skyplot | S014 (desde GSV) |
| Mensajes de las correcciones (esperando, errores NTRIP, bytes) | R-ntrip, F-acl | S007 |
| Posición de la base en el mapa y en la ficha del punto | F-acl | S014 (EBP / RTCM 1005-1006) |
| Metadatos por punto: solución, RMS, PDOP, GDOP, satélites por constelación, mountpoint, altura de antena, inclinación, hora con zona, modelo y n.º de serie, SC, base | F-acl, F-sor | S010, S015 |
| Estados y avisos de ETC | R-nmea, ETC_rev_3 | S015 |

## C. Configuración de base y rover
| Función | Fuente | TopoPhone |
|---|---|---|
| Ajustes GNSS: modo cinemático/estático, máscara de elevación 15°, máscara SNR 35, constelaciones, 1/5/10 Hz | R-gl, R-cl | Guía (S042 comprueba la frecuencia) |
| Base: manual, promediado en SINGLE, promediado en FIX; altura de antena (+134 mm) | R-bmeth, R-bman, R-bfix, R-bsgl | Guía; Exp (antena y rol) |
| Mensajes RTCM3 de la base (1006, MSM4 1074/1084/1094/1124, 1230) | R-rtcm | Guía |
| Altura de antena única (FW 32) | R-cl | S015 (modo compensado), Guía |
| Ajustes del recolector: inclinación, solo FIX, límites H/V, instantáneo/promediado/automático, jalón de 1,8 m | F-col, F-elev | S010, S015, S016 |
| Activar o desactivar el IMU | R-tilt | Guía |

## D. Correcciones: entrada y salida
| Función | Fuente | TopoPhone |
|---|---|---|
| NTRIP desde el móvil | R-ntrip | S002, S007 (cliente propio → RTCM por SPP/TCP) |
| Perfiles NTRIP (dirección, puerto 2101, usuario, contraseña, mountpoint del sourcetable, envío de GGA) | R-ntrip, F-acl | S007 |
| NTRIP en el propio receptor, Emlid Corrections, LoRa, UHF | R-ntrip, R-ec, R-lora, R-uhf | Guía |
| Entrada por Bluetooth, TCP o serie | R-fg, R-tcpgps | S002 (Bluetooth), S020 (TCP) |
| Salida de la base: LoRa, NTRIP, NTRIP local, TCP, serie; salida doble (FW 33) | R-dual, R-dji | Guía |
| Emlid Caster (caster.emlid.com:2101) | R-caster, K1 | S007 (como un perfil NTRIP más) |

## E. Salida de posición y registro
| Función | Fuente | TopoPhone |
|---|---|---|
| Posición por Bluetooth, TCP o serie; NMEA/LLH/XYZ/ENU/ERB | R-mock, Emesent | Guía (configurar), S003 (consumir NMEA) |
| Frases NMEA, talker GN/GP, frecuencias, EBP y ETC | R-nmea | S003 |
| "Compensate / Do not compensate receiver position" | R-cl, R-afm | S015 (asistente que detecta el modo) |
| Logs: datos brutos (RINEX 3.03/UBX), posición, correcciones de la base; presets; arranque automático; descarga | R-ppk, R-sng | Guía; Exp (iniciar/parar, listar) |
| Stop & Go para PPK | R-sng | — |

## F. Específico del RS3
| Función | Fuente | TopoPhone |
|---|---|---|
| IMU de 6 ejes calibrado en fábrica; inicialización en FIX con movimiento en ocho; aviso "Mover el receptor"; bloqueo de recolección y replanteo | R-tilt, R-web | S015 |
| Inclinación por defecto desde FW 31.5; GGA/GST compensados desde FW 32 | R-cl | S015 |
| ETC: `$GNETC,hora,estado,aviso,rumbo,dir,incl,cn,ce,cu*CS`. Estados: vacío off, 0 fatal, 10 setup, 20 alignment, 30 compensating. Avisos: 0 ninguno, 10 movimiento rápido, 20 GNSS malo, 30 fallo de filtro | ETC_rev_3 | S003 (parseo), S015 (lógica) |
| Compensación: ETC a la misma frecuencia que GGA, emparejados por hora; altura de antena = jalón + **0,134 m**; ENU con Δe = −H·sinθ·sinα, Δn = −H·sinθ·cosα, Δu = −H·cosθ antes de proyectar | ETC_rev_3 | S015 |
| Precisión de la inclinación = coeficiente × altura de antena (mm); total = RTK + inclinación por eje; 2D = RTK₂D + √(e² + n²) | ETC_rev_3 | S015 |
| Precisión publicada: RTK 7 mm + 1 ppm H / 14 mm + 1 ppm V; inclinación RTK + 2 mm + 0,3 mm/° (≤ 60°) | R-web | S015 (umbrales) |
| Orientación del mapa con el IMU en el replanteo | R-tilt, F-so | S017 |
| Batería de 5200 mAh (18 h como rover con inclinación) | R-pwr | Exp (lectura) |
| Funciones solo del RS4 Pro (replanteo AR, captura visual) | F-so, F-vc | — |

## G. Levantamiento y proyecto (todo incluido: no hay planes de pago)
| Función | Fuente | TopoPhone |
|---|---|---|
| Proyectos: nombre, autor, descripción, SC al crearlo, unidades, ángulos, biblioteca vinculada, carpetas, búsqueda | Manual §2.1, F-proj | S006 |
| SC: biblioteca por país o código, global, personalizado (elipsoide, TM/LCC 1SP/2SP/OM A/B, Helmert 3/4/6/7, MB 10, geoide) | Manual §2.2–2.4 | S008 (España), S031–S034 |
| Cambiar el SC después (mismo datum), ft topográfico de EE. UU., cuadrícula/terreno, DD/DMS | F-editcs, F-acl | S006, S025, S034 |
| Recolector: instantáneo, promediado (≤ 60 min), automático por tiempo o distancia, fotos, atributos, colores, sonido, nombres autoincrementales | Manual §3.1, F-col | S010, S016, S023 |
| Punto manual (local o global) | F-pt, manual §3.3 | S011 |
| Lista de objetos: buscar, mostrar u ocultar, duplicar y editar, borrar, cambiar código | F-acl | S011, S023 |
| Replanteo de puntos: por dirección o por distancia, zoom automático, verde a 2,5 cm, ayuda a 40 cm, azimut, `_stkd`, informe | Manual §3.2, F-so, F-sor | S017, S018 |
| Replanteo de cota (desmonte/terraplén con tolerancia) | F-elev | S018 |
| Base shift | Manual §3.3, R-bshift | S019 |
| Bibliotecas de códigos ("Puntos y líneas" + biblioteca), atributos Text/Integer/Number/List (≤ 100), CSV Code/Description/Type/Color/Attribute_*; códigos sobre la marcha | Manual §2.5, §3.7, F-cl | S022, S023 |
| Líneas: nueva o continuar, cerrar en polígono, asistente desde puntos, editar vértices, información de la línea | Manual §3.4, F-lcol, F-poly, F-lwiz, F-led | S024, S025 |
| Replanteo de líneas: por segmentos, a la línea, a lo largo, encadenamiento hasta A/B, intervalos y desplazamientos | Manual §3.5, F-lso | S026 |
| Biblioteca WMS (URL → capas), capas del proyecto, orden, visibilidad y opacidad, mapa vectorial o satélite | Manual §2.6, §2.7, §3.8 | S009, S035 |
| COGO: inversa, poligonal, intersecciones | F-inv, F-trav, F-int | S037 |
| Localización: ≤ 50 pares H/V, residuos, ajuste horizontal + plano inclinado, guardar como SC | F-loc | S038 |
| Dibujos CAD DXF (≤ 100 MB, colores, bloques, arcos → polilíneas) y su replanteo | F-cad | S039, S040 |
| Superficies LandXML (una activa, desplazamiento vertical, desmonte/terraplén) | F-srf | S041 |
| Importar: CSV (Point/Easting/Northing/Elevation + atributos), PENZD, PNEZD, DXF, KML, SHP en ZIP | Manual §2.1.4, F-proj | S027, S029, S030, S039 |
| Exportar: CSV (todas las columnas, PENZD/PNEZD, personalizado), DXF, SHP, KML, fotos en ZIP; global o local | Manual §4, F-exp | S012, S028, S029, S030 |
| Cuenta, espacios de trabajo, equipos, sincronización con Flow 360, rovers en directo | Manual §1, F-team, F-ws | — (solo local). Sustituto: S013 (copias y paquetes) |

## H. Ajustes y app
| Función | Fuente | TopoPhone |
|---|---|---|
| Pestañas Levantamiento / Receptores / Perfil | F-acct | S001 (Levantamiento / Receptor / Ajustes) |
| Ajustes por defecto: unidades, SC, bibliotecas | F-def | S006, S022 |
| Idiomas | F-acl | S001 (es, en) |
| Unidades m/ft/usft; DD/DMS (+ gon en TopoPhone) | F-acl | S006 (RNF-T9) |
| Permisos y segundo plano | R-conn | S004 (RNF-T1, RNF-T2) |
| Planes, facturación, códigos de licencia | F-plan | — |

## Claves de fuente
| Clave | URL |
|---|---|
| Manual | `docs/referencias/emlid/Espanol_Emlid_Flow_Emlid_Flow_360_Documentacion_del-usuario.pdf` (Flow 8.1, FW 30, 2023) |
| ETC_rev_3 | `docs/referencias/emlid/ETC_rev_3.pdf` — https://files.emlid.com/docs/ETC_rev_3.pdf |
| R-conn | https://docs.emlid.com/reachrs3/before-you-start/connecting-to-reach/ |
| R-pwr | https://docs.emlid.com/reachrs3/before-you-start/power-supply/ |
| R-ntrip | https://docs.emlid.com/reachrs3/rtk-quickstart/reachrs3-as-rover/rtk-over-ntrip-service/ |
| R-lora | https://docs.emlid.com/reachrs3/rtk-quickstart/reachrs3-kit/rtk-over-lora/ |
| R-uhf | https://docs.emlid.com/reachrs3/rtk-quickstart/reachrs3-as-rover/rtk-over-uhf/ |
| R-tilt | https://docs.emlid.com/reachrs3/rtk-quickstart/reachrs3-kit/tilt-compensation/ |
| R-caster | https://docs.emlid.com/reachrs3/rtk-quickstart/reachrs3-kit/rtk-over-emlid-ntrip-caster/ |
| R-ec | https://docs.emlid.com/reachrs3/rtk-quickstart/reachrs3-as-rover/rtk-over-emlid-corrections/ |
| R-bmeth | https://docs.emlid.com/reachrs3/base-setup/choosing-base-setup-method/ |
| R-bman | https://docs.emlid.com/reachrs3/base-setup/setting-up-base-over-known-point/manual/ |
| R-bfix | https://docs.emlid.com/reachrs3/base-setup/setting-up-base-over-known-point/averaged-in-fix/ |
| R-bsgl | https://docs.emlid.com/reachrs3/base-setup/averaging-base-position-in-single/ |
| R-bshift | https://docs.emlid.com/reachrs3/base-setup/setting-up-base-over-known-point/base-shift/ |
| R-dual | https://docs.emlid.com/reachrs3/base-setup/dual-base-output/ |
| R-ppk | https://docs.emlid.com/reachrs3/ppk-quickstart/how-to-record-and-download-logs/ppk-logging/ |
| R-sng | https://docs.emlid.com/reachrs3/ppk-quickstart/how-to-record-and-download-logs/stop-n-go-with-ef-logging/ |
| R-cl | https://docs.emlid.com/reachrs3/changelogs/ |
| R-spec | https://docs.emlid.com/reachrs3/specifications/specs/ |
| R-nmea | https://docs.emlid.com/reachrs3/specifications/nmea-format/ |
| R-rtcm | https://docs.emlid.com/reachrs3/specifications/rtcm3-format/ |
| R-upd | https://docs.emlid.com/reachrs3/firmware-update/updater/ |
| R-wifi | https://docs.emlid.com/reachrs3/firmware-update/connecting-reach-to-the-internet/connecting-to-the-internet/ |
| R-lte | https://docs.emlid.com/reachrs3/firmware-update/connecting-reach-to-the-internet/enabling-cellular-modem/ |
| R-rp | https://docs.emlid.com/reachrs3/troubleshooting/how-to-access-RP/ |
| R-fsr / R-ssr | https://docs.emlid.com/reachrs3/troubleshooting/how-to-download-FSR/ · …/how-to-download-SSR/ |
| R-dbg | https://docs.emlid.com/reachrs3/troubleshooting/raw-data-debug/ |
| R-rst | https://docs.emlid.com/reachrs3/troubleshooting/restore-initial-settings/ |
| R-sn | https://docs.emlid.com/reachrs3/troubleshooting/how-to-find-SN/ |
| R-mock | https://docs.emlid.com/reachrs3/integration/mock-location/ |
| R-dji | https://docs.emlid.com/reachrs3/rtk-quickstart/reachrs3-as-base/dji-rtk/ |
| R-fg | https://docs.emlid.com/reachrs3/integration/fieldgenius-android/ |
| R-afm | https://docs.emlid.com/reachrs3/integration/arcgis-fieldmaps/ |
| R-tcpgps | https://docs.emlid.com/reachrs3/integration/tcpgps/ |
| R-gl | https://docs.emlid.com/reachrs3/glossary/ |
| R-web | https://emlid.com/reachrs3/ |
| F-acl | https://docs.emlid.com/emlid-flow/ef-changelogs/android-changelogs/ |
| F-col | https://docs.emlid.com/emlid-flow/survey-with-ef/points/collector/ |
| F-so | https://docs.emlid.com/emlid-flow/survey-with-ef/points/stakeout/ |
| F-sor | https://docs.emlid.com/emlid-flow/survey-with-ef/points/stakeout-report/ |
| F-vc | https://docs.emlid.com/emlid-flow/survey-with-ef/points/visual-capture/ |
| F-elev | https://docs.emlid.com/emlid-flow/survey-with-ef/checking-elevation/ |
| F-mob | https://docs.emlid.com/emlid-flow/survey-with-ef/navigating-with-mobile-location/ |
| F-srf | https://docs.emlid.com/emlid-flow/survey-with-ef/working-with-surfaces/ |
| F-cad | https://docs.emlid.com/emlid-flow/survey-with-ef/working-with-cad-drawings/ |
| F-lcol / F-lso | https://docs.emlid.com/emlid-flow/survey-with-ef/lines/line-collector/ · …/lines/line-stakeout/ |
| F-lwiz / F-led / F-poly | https://docs.emlid.com/emlid-flow/survey-with-ef/lines/line-wizard/ · …/edit-lines/ · …/polygons/ |
| F-inv / F-trav / F-int | https://docs.emlid.com/emlid-flow/edit-and-refine-data/calculations/inverse/ · …/traverse/ · …/intersection/ |
| F-pt | https://docs.emlid.com/emlid-flow/edit-and-refine-data/edit-geometries/point-creation/ |
| F-loc | https://docs.emlid.com/emlid-flow/setting-up-team-and-projects/cs-setup/localization/ |
| F-editcs | https://docs.emlid.com/emlid-flow/edit-and-refine-data/managing-cs/edit-cs/ |
| F-proj | https://docs.emlid.com/emlid-flow/setting-up-team-and-projects/creating-project/ |
| F-ws / F-team | https://docs.emlid.com/emlid-flow/setting-up-team-and-projects/preparing-workspace/ · …/workspace-and-team-management/managing-team-members/ |
| F-cl | https://docs.emlid.com/emlid-flow/setting-up-team-and-projects/code-libraries-management/ |
| F-def | https://docs.emlid.com/emlid-flow/edit-and-refine-data/work-with-defaults/ |
| F-exp | https://docs.emlid.com/emlid-flow/export-projects/ |
| F-plan | https://docs.emlid.com/emlid-flow/workspace-and-team-management/plan-management/ |
| F-acct / F-gl | https://docs.emlid.com/emlid-flow/workspace-and-team-management/account-management/ · https://docs.emlid.com/emlid-flow/glossary/ |
| K1 | https://docs.emlid.com/emlid-caster/ |
| EarthScope | https://cdn.serc.carleton.edu/files/getsi/teaching_materials/high-precision/emlid-reachrs2-instructor-guide-reachview3.v21.pdf |
| Emesent | https://knowledge.emesent.com/articles/software-setup |
| Foro: skyplot | https://community.emlid.com/t/skyplot-in-emlid-flow/43919 |

**No se pudo confirmar:** la referencia completa de ajustes del receptor (la antigua página "settings overview" da 404), las listas completas de valores de LoRa, el intervalo de logging y los baudios, y el protocolo BLE/Wi-Fi que usa Emlid Flow (no está documentado).
