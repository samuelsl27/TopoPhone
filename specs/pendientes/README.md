# Specs pendientes de TopoPhone

Borradores de spec de todo lo que falta por hacer (28/10/2026). Cada carpeta tiene **solo `spec.md`**, sin plan ni tareas: se escribieron sin entrevista, a partir de la fila de `docs/hoja-de-ruta.md`, del inventario funcional y de las specs ya implementadas. Cada decisión que no estaba en esas fuentes va como `[SUPOSICIÓN: …]` en «Dudas abiertas» y es una pregunta para la entrevista.

`rf_report` solo mira `specs/NNN-*/spec.md`, así que esta carpeta no cuenta como trabajo en curso.

## Cómo se empieza una
1. Entrevista sobre sus suposiciones (skill `spec-generator`) y cambios al borrador.
2. `git mv specs/pendientes/NNN-slug specs/NNN-slug` y su fila de la hoja de ruta pasa de `pendiente` a `spec`.
3. Plan y tareas con las plantillas de `docs/plantillas/`, y desde ahí el flujo de siempre (una tarea por prompt).

## Índice (25 specs, 520 RF, 129 suposiciones)
La columna «Depende» es la que dice el borrador, que corrige o completa la de la hoja de ruta (en negrita lo que la hoja no tiene).

| Spec | Hito | Depende | RF | Supos. | Qué es |
|---|---|---|---|---|---|
| [020 base-shift](020-base-shift/spec.md) | H2 | S011, S010, **S008, S015, S016, S018, S046, S047** | 16 | 4 | Desplazamiento ECEF conocido − medido aplicado a los puntos siguientes |
| [021 conexion-wifi-tcp](../021-conexion-wifi-tcp/spec.md) | H2 | S015, **S004, S005, S007** | 18 | 2 | Cliente TCP al receptor (hotspot o red local), NTRIP por datos móviles |
| [022 gnss-movil-y-receptor-virtual](022-gnss-movil-y-receptor-virtual/spec.md) | H2 | **S003, S005, S047** (la hoja dice S021) | 19 | 4 | GNSS del móvil (nunca FIX) y receptor virtual con escenarios |
| [045 raster-mdt](../045-raster-mdt/spec.md) | H2 | S042 | 25 | 7 | Ráster de cotas como superficie y replanteo de cota (cierra la puerta G2b) |
| [023 bibliotecas-codigos](023-bibliotecas-codigos/spec.md) | H3 | S006, **S028, S013** | 22 | 5 | Bibliotecas de códigos con atributos, importar y exportar |
| [024 recolectar-con-codigos](024-recolectar-con-codigos/spec.md) | H3 | S023, **S010, S011** | 21 | 6 | Selector de códigos y atributos al medir |
| [025 lineas-y-poligonos](025-lineas-y-poligonos/spec.md) | H3 | S024, **S011** | 26 | 6 | Líneas y polígonos del proyecto con vértices que son puntos |
| [026 informacion-geometrica](026-informacion-geometrica/spec.md) | H3 | S025, **S040, S008** | 15 | 6 | Longitud, área, azimut, pendiente, ángulos; también de líneas de un DXF |
| [027 replanteo-lineas](027-replanteo-lineas/spec.md) | H3 | S025, S041, **S049** | 18 | 5 | Replantear las líneas propias con el motor de S041 y crear un eje |
| [029 exportar-dxf](../029-exportar-dxf/spec.md) | H4 | S025 (los puntos solo S012) | 21 | 6 | DXF ASCII R12 con capas por código y etiquetas |
| [030 shapefile](../030-shapefile/spec.md) | H4 | S048, S040, S006 | 30 | 4 | Importar un Shapefile (ZIP o ficheros sueltos) como capa de diseño |
| [055 exportar-shapefile](../055-exportar-shapefile/spec.md) | H4 | S012, S024, S025, S030 | 23 | 3 | Exportar a un ZIP con PointZ, PolyLineZ y PolygonZ, con .prj y .cpg |
| [031 kml-kmz](../031-kml-kmz/spec.md) | H4 | S048, S040, S006, S012, S024, S025, S017b | 32 | 7 | Importar un KML o KMZ como capa de diseño y exportar a KML o KMZ, con fotos opcionales en el KMZ |
| [056 exportar-fotos](../056-exportar-fotos/spec.md) | H4 | S017b, S012 | 14 | 4 | Las fotos de los puntos en un ZIP con un índice `fotos.csv` |
| [038 cogo](../038-cogo/spec.md) | H6 | S011, S008, S026 | 31 | 4 | Inversa, poligonal e intersecciones; resultados como puntos |
| [032 proyecciones-completas](../032-proyecciones-completas/spec.md) | H5 | S008 | 21 | 3 | LCC 1SP/2SP, Mercator A/B, Oblique Mercator A/B, Estereográfica oblicua y polar A/B/C, y meridiano de origen distinto de Greenwich (entrevista 20/11/2026) |
| [033 transformaciones-datum](../033-transformaciones-datum/spec.md) | H5 | S032, S008 | 14 | 4 | Helmert 3/4/6/7, Molodensky-Badekas y rejillas |
| [034 biblioteca-sc-mundial](../034-biblioteca-sc-mundial/spec.md) | H5 | S033, S032, S008, **S006** | 21 | 5 | Catálogo de SC con búsqueda, datums verticales y geoides |
| [035 sc-personalizado](035-sc-personalizado/spec.md) | H5 | S034, S033, S032 | 24 | 5 | Editor de SC, «Mis SC», cambio del SC del proyecto |
| [039 localizacion](039-localizacion/spec.md) | H6 | S035, **S011, S008** | 22 | 5 | Semejanza 2D por mínimos cuadrados con pares H/V |
| [043 receptor-guiado](043-receptor-guiado/spec.md) | H7 | S016, **S015, S021, S005, S046** | 21 | 4 | Listas de configuración, comprobaciones previas y Reach Panel en un WebView |
| [044 modulo-experimental-reach](../044-modulo-experimental-reach/spec.md) | H7 | S043, **S021, S046** | 20 | 4 | Flavor `experimental` con interfaz no documentada |
| [050 peraltes-y-transversales](050-peraltes-y-transversales/spec.md) | H8 | S054, S049 | 21 | 5 | Secciones, peraltes y cota de cualquier punto de la sección |
| [051 taludes](051-taludes/spec.md) | H8 | S050, **S054, S042** | 18 | 5 | Cabezas y pies de talud contra el terreno |
| [052 toma-de-datos-lineal](052-toma-de-datos-lineal/spec.md) | H8 | S054, S050, **S049, S010, S017** | 23 | 5 | Tomar un eje con el receptor y perfiles longitudinal y transversal |

## Conflictos y huecos que han salido al redactarlas
**Ya corregido (28/10/2026):** las dependencias de la hoja de ruta (la columna «Depende» de la tabla de arriba es la de la hoja), el orden de cálculo de S020 (la punta del jalón va antes del shift, como dice `docs/arquitectura.md`), la nota de mDNS de S021 (solo con un nombre de servicio documentado; si no, S044), el perfil de terreno en la fila de S050, el «fuera de alcance» de S008 (cambiar de datum ya no apunta a S035, que cambia el SC dentro del mismo datum) y la definición de «Eje» del glosario (también puede tomarse en campo, S052).

**Sigue abierto:** lo que sigue es decisión de diseño que se toma en la entrevista de cada spec, o un cambio a una spec implementada que se aplica al aprobar la que lo pide (cada borrador lo lista en «Cambios en documentos anteriores»).

**Hoja de ruta y dependencias**
- S029, S030 y S031 dependen de S025 solo para líneas y polígonos (sus puntos ya se pueden exportar con S012). S031 depende de S017b, que sigue en `tareas`, y S052 de S017, que consta «en curso».
- S031 se dividió en KML/KMZ (S031) y las fotos en un ZIP (S056). S026 solapa con S038 (inversa y ángulo) y con S035 (cuadrícula o terreno); el inventario cita S026 donde parece que quería decir S035.

**Sistemas de coordenadas**
- `ProjectCrs` es hoy un enum cerrado y `GpkgCrsCatalog` (S048) solo reconoce esos sistemas: S034 obliga a generalizarlos y a migrar Room.
- La descarga de rejillas de desplazamiento ya tiene dueño: S034 (entrevista de S033, 20/11/2026), junto con los geoides. S032 (entrevista 20/11/2026) añade LCC, Mercator, Oblique Mercator, Estereográfica oblicua y polar y el meridiano de origen distinto de Greenwich; el resto de proyecciones (Albers, Cassini…) sigue sin dueño.
- S039 choca con S053 y S012: con una localización activa, el «sistema del proyecto» ya no corresponde al EPSG que se declara al exportar.
- S045 convierte entre altitud ortométrica y elipsoidal en un ráster, y S042 lo deja fuera para LandXML.

**Receptor**
- La búsqueda mDNS de receptores pasó de S021 a S044 (constitución nº 5), decidido en la entrevista de S021 (15/11/2026); S044 la recoge al abrirla (resuelto en su entrevista, 18/11/2026: se incluye).
- S043 añade «Guía» y «Equipo» a las cinco entradas del menú del receptor de S005/RF-9.

**Specs ya implementadas que habría que tocar**
- S012 (columnas de código de S024, coordenadas localizadas de S039) y S053 (columnas de código de S024).
- S019 (informe de replanteo de S027) y S049 (importación de ejes desde las líneas de S027).
- S009 (la política del Catastro «una imagen, sin guardar» choca con S037), S008 y S005.
- Cada spec que añada columnas desplaza los números de versión de Room: S017b pasa a la v22 o más.

**Información que falta**
- `docs/referencias/formatos/` solo tiene un `.gitkeep`: las reglas de DXF, Shapefile, KML y GeoTIFF de los borradores salen del conocimiento del redactor y habría que contrastarlas con la norma (o con ficheros de ejemplo) antes de implementar.
- S023 necesita un CSV real de bibliotecas de códigos de la app de referencia, que tendrías que aportar tú.
- Está por comprobar contra LandXML 1.2 si trae peraltes (S050 supone que solo trae secciones transversales).
