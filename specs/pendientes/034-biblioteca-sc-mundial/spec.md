# Spec 034 — Biblioteca de SC mundial

> Hito: H5 · Depende de: S033, S032, S008, S006 · Estado: borrador (sin entrevista, 28/10/2026)

## Contexto y objetivo
Hoy el proyecto solo puede estar en uno de los ocho SC de España (S006, S008). Un topógrafo que trabaja fuera de España, o con un SC oficial de una comunidad o de un país vecino, no puede crear su proyecto. Con las proyecciones (S032) y las transformaciones de datum (S033) en el núcleo, la app puede ofrecer miles de SC del registro EPSG, siempre que sepa calcularlos.

Esta spec añade una biblioteca de SC con búsqueda por país, código o nombre, una ficha que explica cada SC (datum, proyección, unidades, ámbito, transformación usada) y los datums verticales con sus geoides, que se descargan con verificación SHA-256 solo cuando el usuario los pide. Los SC de España de S008 siguen ahí y siguen funcionando sin conexión. Sin cuentas ni nube: la red solo se usa para bajar rejillas que el usuario elige (constitución nº 4).

## Usuarios / actores
- Topógrafo que trabaja fuera de España o con SC de otros países.
- Topógrafo español que necesita un SC autonómico o histórico (por ejemplo, un huso UTM no habitual).

## Historias de usuario
- H1: Como topógrafo quiero encontrar mi SC por país, por código EPSG o por nombre.
- H2: Como topógrafo quiero ver qué es exactamente un SC antes de usarlo en el proyecto.
- H3: Como topógrafo quiero descargar el geoide de mi zona y trabajar con alturas ortométricas.
- H4: Como topógrafo quiero que los SC y geoides que ya usa un proyecto sigan funcionando sin conexión.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Biblioteca de SC**: el catálogo de SC horizontales (con su datum, proyección y unidades) incluido en la app, generado del registro EPSG (ADR-3).
- **Ficha del SC**: pantalla con todos los datos del SC.
- **Datum vertical**: la referencia de las alturas: elipsoidal o un modelo de geoide (EGM08-REDNAP, EGM96, EGM2008 u otro oficial).
- **Rejilla instalada**: rejilla (geoide o desplazamiento) descargada y verificada en el almacenamiento privado de la app.

### Buscar y elegir (H1)
- RF-1: CUANDO el usuario elija el SC en el formulario de proyecto o en Ajustes (S006/RF-5, RF-13), EL SISTEMA ofrecerá, además de los SC de España de S008, la biblioteca de SC.
- RF-2: EL SISTEMA buscará en la biblioteca por país, por código EPSG y por nombre, sin distinguir mayúsculas ni tildes, y mostrará los resultados ordenados con los de España primero.
- RF-3: EL SISTEMA mostrará en cada resultado el nombre, el código, el país y si es proyectado o geográfico.
- RF-4: EL SISTEMA ofrecerá solo los SC cuya proyección (S032) y cuya transformación de datum hasta ETRS89/WGS84 (S033) sabe calcular, y los demás no aparecerán.
- RF-5: CUANDO el usuario filtre por país, EL SISTEMA mostrará los SC cuyo ámbito de uso incluye ese país.

### Ficha del SC (H2)
- RF-6: CUANDO el usuario abra la ficha de un SC, EL SISTEMA mostrará su nombre, código, datum, elipsoide, proyección con sus parámetros, unidades lineales, ámbito de uso, y la transformación que se usa hasta el datum de las correcciones con su nombre, precisión y ámbito (S033/RF-12).
- RF-7: DONDE haya varias transformaciones posibles, EL SISTEMA propondrá la de mayor precisión cuyo ámbito cubra el centro del proyecto y permitirá elegir otra.
- RF-8: SI no hay transformación disponible entre el datum del SC y el de las correcciones, ENTONCES EL SISTEMA no permitirá elegir ese SC e indicará el motivo.
- RF-9: SI la transformación necesita una rejilla que no está instalada, ENTONCES EL SISTEMA mostrará su nombre, tamaño y licencia y permitirá descargarla (RF-14).
- RF-10: CUANDO el usuario cree un proyecto con un SC de la biblioteca, EL SISTEMA guardará en el proyecto la definición completa del SC y de la transformación, para que no cambie si la biblioteca se actualiza.

### Datums verticales y geoides (H3, H4)
- RF-11: EL SISTEMA ofrecerá como altura del proyecto la altura elipsoidal o un geoide de los que la ficha del SC lista como aplicables a su zona.
- RF-12: SI el geoide elegido no está instalado, ENTONCES EL SISTEMA no calculará la altitud ortométrica, mostrará la altura como no disponible con el motivo y ofrecerá la descarga.
- RF-13: EL SISTEMA incluirá en la app el EGM08-REDNAP de la Península y de Canarias (S008/RF-4) y no exigirá descargarlos.
- RF-14: CUANDO el usuario pulse «Descargar» en una rejilla, EL SISTEMA mostrará su tamaño y su licencia, pedirá confirmación y la descargará por la red con internet (RNF-T4).
- RF-15: CUANDO termine una descarga, EL SISTEMA comprobará la suma SHA-256 del fichero contra la de la biblioteca y, si no coincide, lo descartará, no lo instalará e indicará que está dañado.
- RF-16: SI la descarga se interrumpe, ENTONCES EL SISTEMA no dejará un fichero a medias y permitirá reintentarla (RNF-T6).
- RF-17: EL SISTEMA mostrará la lista de rejillas instaladas con su tamaño y el proyecto que las usa, y permitirá borrar las que ningún proyecto use.
- RF-18: SI un proyecto usa una rejilla que no está instalada (por ejemplo, tras restaurar una copia en otro móvil), ENTONCES EL SISTEMA abrirá el proyecto con las coordenadas que dependan de ella como no disponibles y ofrecerá descargarla.
- RF-19: DONDE el SC de un proyecto no dependa de ninguna rejilla descargada, EL SISTEMA trabajará completamente sin conexión.

### Compatibilidad (H1, H4)
- RF-20: EL SISTEMA mantendrá los proyectos existentes con su SC de S008 sin modificar sus datos ni sus coordenadas.
- RF-21: EL SISTEMA reconocerá los SC de la biblioteca por su código EPSG al importar un GeoPackage (S048/RF-8).

## Requisitos no funcionales
- Transversales que aplican: RNF-T4 (descargas por la red con internet), RNF-T6 (migración sin pérdida y descarga atómica), RNF-T7, RNF-T8 (zonas de 56 dp y resultados legibles), RNF-T9 y RNF-T14.
- RNF-T14: cada rejilla muestra su licencia y su atribución; no se incluyen en el repositorio ni en el APK rejillas con licencia incompatible con GPL-3.0; la biblioteca cita el registro EPSG como fuente.
- Constitución nº 2: cada SC de la biblioteca se contrasta con pyproj (coordenadas ≤ 1 mm, ida y vuelta ≤ 0,1 mm) con tres puntos por SC, y la altitud con cada geoide a ≤ 1 mm frente a pyproj con la misma rejilla.
- La biblioteca no aumenta el APK en más de 2 MB y una búsqueda tarda menos de 300 ms en el móvil de referencia.
- Las descargas verifican el SHA-256 de cada fichero antes de usarlo.

## Casos límite
| Caso | RF |
|---|---|
| Buscar «25830», «etrs89 utm 30» o «Francia» | RF-2, RF-5 |
| SC sin proyección o transformación soportada | RF-4, RF-8 |
| Dos transformaciones posibles a ETRS89 | RF-7 |
| Descarga sin cobertura o cortada | RF-14, RF-16 |
| SHA-256 distinto | RF-15 |
| Geoide no instalado en un proyecto | RF-12, RF-18 |
| Proyecto de S006/S008 al actualizar | RF-20 |
| Borrar una rejilla en uso | RF-17 |
| Posición fuera del ámbito del SC | S006/RF-30 |

## Fuera de alcance
- Crear o editar un SC → S035.
- Cambiar el SC de un proyecto ya creado → S035.
- Alturas con datums verticales sin geoide publicado o con transformaciones verticales compuestas → no previsto.
- Proyecciones que no calculamos (S032) y transformaciones con el tiempo (S033) → no previsto.
- Descargar mapas o capas → S036 y S037.
- Alojar los ficheros de geoides en un servidor propio → no previsto: se descargan del CDN de PROJ.

## Criterios de finalización
- Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Catálogo reproducible con `tools/geodesia` desde un pyproj fijado, con vectores pyproj de al menos tres puntos por SC y de cada geoide y transformación.
- Demo en el emulador: buscar un SC de otro país, ver su ficha, crear un proyecto, descargar un geoide, ver el error con el SHA-256 alterado y abrir el proyecto sin red.
- Demo de campo: queda para el usuario.

## Dudas abiertas
- [SUPOSICIÓN: el catálogo incluye solo SC activos del registro EPSG con proyección TM, LCC u OM, o geográficos, y transformación a ETRS89/WGS84; no aparecen los demás.]
- [SUPOSICIÓN: los geoides se bajan del CDN de PROJ (cdn.proj.org); si un geoide no se puede redistribuir, se enlaza la fuente oficial y no se descarga.]
- [SUPOSICIÓN: la descarga puede ser por datos móviles tras confirmar el tamaño; no hay límite por Wi-Fi.]
- [SUPOSICIÓN: el datum de las correcciones sigue siendo ETRS89, REGCAN95 o ITRF/WGS84 (S006); fuera de ellos el usuario usa base shift o localización.]
- [SUPOSICIÓN: los proyectos guardan una copia de la definición del SC (RF-10) aunque ocupe unos cientos de bytes.]

## Cambios en documentos anteriores (no se aplican hasta aprobar)
- `docs/hoja-de-ruta.md`: S034 en estado `spec`; su fila incluye la descarga de rejillas de desplazamiento de S033; S035 y S039 siguen dependiendo de ella.
- S006/RF-8 y S008/RF-1: los SC de España pasan a ser una parte de la biblioteca.
- `docs/glosario.md`: se añaden «Biblioteca de SC», «Ficha del SC», «Datum vertical» y «Rejilla instalada».
