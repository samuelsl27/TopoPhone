# Spec 034 — Biblioteca de SC mundial

> Hito: H5 · Depende de: S033, S032, S008, S006 · Estado: spec (entrevista 21/11/2026)

## Contexto y objetivo
Hoy el proyecto solo puede estar en uno de los diez SC de España (S006, S008). Un topógrafo que trabaja fuera de España, o con un SC oficial de una comunidad o de un país vecino, no puede crear su proyecto. Con las proyecciones (S032) y las transformaciones de datum (S033) en el núcleo, la app puede ofrecer los SC del registro EPSG que sabe calcular.

Esta spec añade una biblioteca de SC con búsqueda por país, código o nombre, recientes y favoritos, una ficha que explica cada SC (datum, proyección, unidades, ámbito, transformación usada) y los datums verticales con sus geoides. Los geoides y las rejillas de desplazamiento horizontal (que S033 dejó a esta spec) se descargan con verificación SHA-256 solo cuando el usuario los pide. Los SC de España de S008 siguen ahí y siguen funcionando sin conexión. Sin cuentas ni nube: la red solo se usa para bajar rejillas que el usuario elige (constitución nº 4).

## Usuarios / actores
- Topógrafo que trabaja fuera de España o con SC de otros países.
- Topógrafo español que necesita un SC autonómico o histórico (por ejemplo, un huso UTM no habitual).

## Historias de usuario
- H1: Como topógrafo quiero encontrar mi SC por país, por código EPSG o por nombre, y volver rápido a los que uso.
- H2: Como topógrafo quiero ver qué es exactamente un SC antes de usarlo en el proyecto.
- H3: Como topógrafo quiero descargar el geoide de mi zona y trabajar con alturas ortométricas.
- H4: Como topógrafo quiero que los SC y geoides que ya usa un proyecto sigan funcionando sin conexión.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Biblioteca de SC**: el catálogo de SC horizontales (con su datum, proyección y unidades) incluido en la app, generado del registro EPSG (ADR-3).
- **Ficha del SC**: pantalla con todos los datos del SC.
- **Datum vertical**: la referencia de las alturas: elipsoidal o un modelo de geoide (EGM08-REDNAP, EGM96, EGM2008 u otro).
- **Rejilla instalada**: rejilla (geoide o desplazamiento) descargada y verificada en el almacenamiento privado de la app.
- **SC calculable**: SC cuya proyección sabe calcular S032 (o las de S008 y S009) y que tiene una transformación que S033 sabe calcular entre su datum y el de las correcciones.

### Buscar y elegir (H1)
- RF-1: CUANDO el usuario elija el SC en el formulario de proyecto o en Ajustes (S006/RF-5, RF-13), EL SISTEMA ofrecerá, además de los SC de España de S008, la biblioteca de SC.
- RF-2: EL SISTEMA buscará en la biblioteca por país, por código EPSG y por nombre, sin distinguir mayúsculas ni tildes, y mostrará los resultados ordenados con los de España primero.
- RF-3: EL SISTEMA mostrará en cada resultado el nombre, el código, el país y si es proyectado o geográfico.
- RF-4: EL SISTEMA incluirá en la biblioteca todos los SC activos del registro EPSG que sean calculables, sea cual sea su datum, y ningún otro.
- RF-5: CUANDO el usuario filtre por país, EL SISTEMA mostrará los SC cuyo ámbito de uso incluye ese país.
- RF-6: CUANDO el usuario abra el selector de SC sin haber escrito, EL SISTEMA mostrará primero sus favoritos y después los últimos SC elegidos.
- RF-7: CUANDO el usuario marque o desmarque un SC como favorito, EL SISTEMA lo guardará en el móvil, sin nube, y lo mantendrá aunque se actualice la biblioteca.
- RF-8: EL SISTEMA guardará como recientes los diez últimos SC elegidos en un proyecto, sin repetir ninguno.

### Ficha del SC (H2)
- RF-9: CUANDO el usuario abra la ficha de un SC, EL SISTEMA mostrará su nombre, código, datum, elipsoide, proyección con sus parámetros, unidades lineales, ámbito de uso, y la transformación que se usa hasta el datum de las correcciones con su nombre, precisión y ámbito (S033/RF-14).
- RF-10: DONDE haya varias transformaciones posibles, EL SISTEMA propondrá la de mayor precisión cuyo ámbito cubra el centro del proyecto y permitirá elegir otra.
- RF-11: SI el datum del SC no es el de las correcciones del proyecto, ENTONCES EL SISTEMA lo permitirá, mostrará en la ficha la transformación que se aplica y avisará de su precisión declarada (S033/RF-14).
- RF-12: SI la transformación necesita una rejilla que no está instalada, ENTONCES EL SISTEMA mostrará su nombre, tamaño, licencia y fuente y permitirá descargarla (RF-19).
- RF-13: CUANDO el usuario cree un proyecto con un SC de la biblioteca, EL SISTEMA guardará en el proyecto la definición completa del SC y de la transformación, para que no cambie si la biblioteca se actualiza.

### Datums verticales y geoides (H3, H4)
- RF-14: EL SISTEMA ofrecerá como altura del proyecto la altura elipsoidal o un geoide de los que la ficha del SC lista como aplicables a su zona.
- RF-15: CUANDO el usuario cree un proyecto con un geoide que no está instalado, EL SISTEMA creará el proyecto, guardará la altura elipsoidal en cada punto y ofrecerá la descarga.
- RF-16: SI el geoide elegido no está instalado, ENTONCES EL SISTEMA no calculará la altitud ortométrica, mostrará la altura como no disponible con el motivo y ofrecerá la descarga.
- RF-17: EL SISTEMA incluirá en la app el EGM08-REDNAP de la Península y de Canarias y la rejilla SPED2ETV2 (S008/RF-4) y no exigirá descargarlos.
- RF-18: SI un proyecto usa una rejilla que no está instalada (por ejemplo, tras restaurar una copia en otro móvil), ENTONCES EL SISTEMA abrirá el proyecto con las coordenadas que dependan de ella como no disponibles y ofrecerá descargarla.

### Descargas de rejillas (H3, H4)
- RF-19: CUANDO el usuario pulse «Descargar» en una rejilla, EL SISTEMA mostrará su tamaño, su licencia y su fuente, pedirá confirmación, también con datos móviles y sea cual sea el tamaño, y la descargará por la red con internet (RNF-T4).
- RF-20: DONDE la licencia de una rejilla no sea abierta o no esté clara, EL SISTEMA la ofrecerá igualmente, con un aviso de la licencia y el enlace a la fuente, y no la incluirá nunca en el APK ni en el repositorio.
- RF-21: CUANDO termine una descarga, EL SISTEMA comprobará la suma SHA-256 del fichero contra la de la biblioteca y, si no coincide, lo descartará, no lo instalará e indicará que está dañado.
- RF-22: SI la descarga se interrumpe, ENTONCES EL SISTEMA no dejará un fichero a medias y permitirá reintentarla (RNF-T6).
- RF-23: EL SISTEMA mostrará la lista de rejillas instaladas con su tamaño y el proyecto que las usa, y permitirá borrar las que ningún proyecto use.
- RF-24: DONDE el SC de un proyecto no dependa de ninguna rejilla descargada, EL SISTEMA trabajará completamente sin conexión.

### Compatibilidad (H1, H4)
- RF-25: EL SISTEMA mantendrá los proyectos existentes con su SC de S008 sin modificar sus datos ni sus coordenadas.
- RF-26: EL SISTEMA reconocerá los SC de la biblioteca por su código EPSG al importar un GeoPackage (S048/RF-8).

## Requisitos no funcionales
- Transversales que aplican: RNF-T4 (descargas por la red con internet), RNF-T6 (migración sin pérdida y descarga atómica), RNF-T7, RNF-T8 (zonas de 56 dp y resultados legibles), RNF-T9 y RNF-T14.
- RNF-T14: cada rejilla muestra su licencia y su atribución; el APK y el repositorio no llevan rejillas con licencia incompatible con GPL-3.0; la biblioteca cita el registro EPSG como fuente.
- Constitución nº 2: cada SC de la biblioteca se contrasta con pyproj (coordenadas ≤ 1 mm, ida y vuelta ≤ 0,1 mm) con tres puntos por SC, y la altitud con cada geoide a ≤ 1 mm frente a pyproj con la misma rejilla.
- La biblioteca no aumenta el APK en más de 2 MB y una búsqueda tarda menos de 300 ms en el móvil de referencia.
- Las descargas verifican el SHA-256 de cada fichero antes de usarlo.

## Casos límite
| Caso | RF |
|---|---|
| Buscar «25830», «etrs89 utm 30» o «Francia» | RF-2, RF-5 |
| SC de un datum distinto de ETRS89, REGCAN95 o ITRF/WGS84 | RF-4, RF-11 |
| Dos transformaciones posibles a ETRS89 | RF-10 |
| Descarga sin cobertura o cortada | RF-19, RF-22 |
| SHA-256 distinto | RF-21 |
| Rejilla de licencia dudosa | RF-20 |
| Geoide no instalado al crear o abrir un proyecto | RF-15, RF-16, RF-18 |
| Proyecto de S006/S008 al actualizar | RF-25 |
| Borrar una rejilla en uso | RF-23 |
| Un favorito que una actualización de la biblioteca elimina | RF-7 |
| Posición fuera del ámbito del SC | S006/RF-30 |

## Fuera de alcance
- Crear o editar un SC → S035.
- Cambiar el SC de un proyecto ya creado → S035.
- Listas propias de SC y «Mis SC» (los favoritos de esta spec son solo una marca) → S035.
- Alturas con datums verticales sin geoide publicado o con transformaciones verticales compuestas → no previsto.
- Proyecciones que no calculamos (Albers, Cassini…) → no previsto; sus SC no aparecen (RF-4).
- Transformaciones que dependen del tiempo (ITRF a ETRS89 con época) → no previsto (la solución es base shift o localización, S020 y S039).
- Descargar mapas o capas → S036 y S037.
- Alojar los ficheros de rejillas en un servidor propio → no previsto: se descargan del CDN de PROJ.

## Criterios de finalización
- Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Catálogo reproducible con `tools/geodesia` desde un pyproj fijado, con vectores pyproj de al menos tres puntos por SC y de cada geoide y transformación.
- Demo en el emulador: buscar un SC de otro país, ver su ficha, crear un proyecto, descargar un geoide, ver el error con el SHA-256 alterado y abrir el proyecto sin red.
- Demo de campo: queda para el usuario.

## Dudas abiertas
- [NECESITA ACLARACIÓN: el tope de 2 MB del APK puede no bastar con todos los SC calculables, que son miles. Se mide al generar el catálogo; si no cabe, el plan propone comprimirlo o relajar el tope y se pregunta antes.]
- [SUPOSICIÓN: el datum de las correcciones sigue siendo ETRS89, REGCAN95 o ITRF/WGS84 (S006); el datum del SC puede ser otro mientras haya transformación (RF-11).]
- [SUPOSICIÓN: los geoides y las rejillas se bajan del CDN de PROJ (cdn.proj.org) con las licencias que declara.]
- [SUPOSICIÓN: los proyectos guardan una copia de la definición del SC (RF-13) aunque ocupe unos cientos de bytes.]

## Cambios en documentos anteriores (no se aplican hasta aprobar)
- `docs/hoja-de-ruta.md`: S034 pasa a `spec` (ya aplicado al abrirla); su fila incluye la descarga de rejillas de desplazamiento de S033; S035 y S039 siguen dependiendo de ella.
- S006/RF-8 y S008/RF-1: los SC de España pasan a ser una parte de la biblioteca.
- `docs/glosario.md`: se añaden «Biblioteca de SC», «Ficha del SC», «Datum vertical», «Rejilla instalada» y «SC calculable».
