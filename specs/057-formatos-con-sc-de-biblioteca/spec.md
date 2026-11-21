# Spec 057 — Formatos con un SC de la biblioteca

> Hito: H5 · Depende de: S034, S030, S031, S040, S042, S045, S048, S049, S029, S053, S055 · Estado: spec (entrevista 21/11/2026)

## Contexto y objetivo
S034 permite crear un proyecto con cualquiera de los 5.106 SC de la biblioteca, pero en ese proyecto no funcionan la importación ni la exportación de DXF, Shapefile, GeoPackage, KML y KMZ, LandXML y ráster: sus menús los esconden con un aviso (S034/RF-29), porque esos formatos solo conocen los diez SC de España. Un topógrafo que trabaja en Francia con Lambert-93 puede medir, replantear y exportar CSV, pero no abrir el DXF del proyecto ni entregar un Shapefile.

Esta spec hace que todos esos formatos funcionen con cualquier SC de la biblioteca, como SC del proyecto y como SC de origen de lo que se importa: el fichero se reconoce o se elige en el selector de la biblioteca, se reproyecta al SC del proyecto (con el cambio de datum cuando haga falta, avisando de su precisión) y, al exportar, se declara el SC del proyecto con su definición completa. Los formatos con SC de España siguen igual.

## Usuarios / actores
- Topógrafo que trabaja con un SC de la biblioteca y recibe o entrega diseños y datos en DXF, Shapefile, GeoPackage, KML, LandXML o ráster.
- Topógrafo español que recibe un fichero en el SC de otro país o de un datum antiguo.

## Historias de usuario
- H1: Como topógrafo con un proyecto en un SC de la biblioteca quiero importar un DXF, un LandXML o un ráster en cualquier SC de la biblioteca.
- H2: Como topógrafo quiero que un Shapefile, un GeoPackage o un GeoTIFF que declara su SC se reconozca solo, también si no es de España.
- H3: Como topógrafo quiero exportar a DXF, Shapefile, GeoPackage y KML con el SC del proyecto bien declarado.
- H4: Como topógrafo quiero saber con qué precisión se ha cambiado de datum antes de aceptar la importación.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **SC de origen**: el sistema de coordenadas en que está un fichero que se importa.
- **Cambio de datum**: el paso de las coordenadas del datum del SC de origen al del SC del proyecto, con la transformación de la biblioteca (S034/RF-10).
- **Definición del SC**: su descripción completa en WKT (la del `.prj` y la de `gpkg_spatial_ref_sys`), generada del registro EPSG.

### Elegir y reconocer el SC de origen (H1, H2)
- RF-1: CUANDO el usuario deba elegir el SC de origen de un DXF, de un LandXML (superficies y ejes) o de un ráster, EL SISTEMA abrirá el selector de la biblioteca (S034/RF-1 a RF-8) con el SC del proyecto el primero y marcado.
- RF-2: EL SISTEMA ofrecerá como SC de origen cualquier SC de la biblioteca, proyectado o geográfico, y los diez de España.
- RF-3: CUANDO el usuario elija el SC de origen, EL SISTEMA propondrá su unidad lineal (metro, pie o pie topográfico de EE. UU.) y permitirá cambiarla (como S040/RF-6 y S045/RF-8).
- RF-4: CUANDO un Shapefile, un GeoPackage o un GeoTIFF declare su SC con un código EPSG que está en la biblioteca, EL SISTEMA lo reconocerá y lo propondrá con su nombre (S030/RF-8, S048/RF-8, S045/RF-8).
- RF-5: CUANDO el `.prj` de un Shapefile no lleve código EPSG, EL SISTEMA lo buscará en la biblioteca por su nombre, sin distinguir mayúsculas ni signos, y lo propondrá si encuentra uno solo.
- RF-6: SI el SC declarado por una capa no está en la biblioteca, o no es único, o el fichero no lo declara, ENTONCES EL SISTEMA no importará la capa hasta que el usuario elija uno en el selector (como S048/RF-9).

### Reproyección y cambio de datum (H1, H4)
- RF-7: EL SISTEMA pasará las coordenadas del SC de origen al del proyecto con la proyección inversa del SC de origen, el cambio de datum si lo hay y la proyección del SC del proyecto, sin redondear ningún valor intermedio.
- RF-8: CUANDO el SC de origen tenga otro datum que el del proyecto, EL SISTEMA usará la transformación de la biblioteca más precisa cuyo ámbito cubra la zona del fichero, o el centro del proyecto si no se conoce, y mostrará su nombre y su precisión declarada en la vista previa.
- RF-9: EL SISTEMA avisará de la precisión declarada del cambio de datum antes de importar y, SI pasa de 1 m o no está declarada, ENTONCES el aviso será destacado y la importación pedirá confirmación.
- RF-10: SI el cambio de datum necesita una rejilla que no está instalada, ENTONCES EL SISTEMA no importará nada, dirá qué rejilla falta con su tamaño y ofrecerá descargarla (S034/RF-12, RF-19).
- RF-11: SI un punto cae fuera de la rejilla del cambio de datum o fuera del ámbito del SC del proyecto, ENTONCES EL SISTEMA no lo importará y lo contará como ignorado con ese motivo (como S040/RF-18).
- RF-12: CUANDO el SC de origen sea el del proyecto, EL SISTEMA no reproyectará nada y usará las coordenadas tal cual.

### Importar cada formato (H1, H2)
- RF-13: EL SISTEMA importará un DXF (S040) en cualquier SC de origen de la biblioteca, con sus puntos, líneas y bloques reproyectados al SC del proyecto.
- RF-14: EL SISTEMA importará las superficies de un LandXML (S042) y los ejes de un LandXML o de un DXF (S049) en cualquier SC de origen de la biblioteca; los ejes se expresan en el SC del proyecto, donde se calculan los PK.
- RF-15: EL SISTEMA importará un ráster (S045) en cualquier SC de origen de la biblioteca sin remuestrearlo y calculará su cota en una posición pasando la posición al SC del ráster.
- RF-16: EL SISTEMA importará las capas de un Shapefile (S030) y de un GeoPackage (S048) en el SC de cada capa, con el recorte a una zona alrededor del centro del proyecto, sus atributos y su campo de nombre.
- RF-17: EL SISTEMA importará un KML o un KMZ (S031) tomando sus coordenadas WGS84 como del datum de las correcciones del proyecto, sin transformación de datum, también con un SC de la biblioteca.
- RF-18: EL SISTEMA mostrará en el resumen de cada importación el SC de origen de cada capa, la transformación con su precisión, y los puntos importados e ignorados con su motivo.

### Alturas (H1)
- RF-19: CUANDO un ráster o una superficie declare sus cotas como altitud ortométrica, EL SISTEMA las pasará al sistema de alturas del proyecto con el geoide del proyecto (S034/RF-14).
- RF-20: SI el proyecto no tiene geoide y las cotas del fichero son ortométricas, o el geoide no está instalado, ENTONCES EL SISTEMA importará la geometría, marcará las cotas como no convertibles con el motivo y ofrecerá la descarga si falta el geoide.

### Exportar con el SC del proyecto (H3)
- RF-21: EL SISTEMA exportará a DXF (S029) las coordenadas del SC del proyecto, globales o locales, con un SC de la biblioteca; con un SC geográfico solo ofrecerá las globales (como S029/RF-13).
- RF-22: EL SISTEMA exportará a GeoPackage (S053) cada capa en el SC del proyecto, con su código EPSG y su definición del SC en `gpkg_spatial_ref_sys`, o en las geográficas del datum del proyecto a elección del usuario.
- RF-23: EL SISTEMA exportará a Shapefile (S055) con el `.prj` que contiene la definición del SC del proyecto y su unidad, o con la del datum si se eligen las geográficas.
- RF-24: EL SISTEMA exportará a KML y KMZ (S031) la longitud y la latitud del datum de las correcciones del proyecto con 9 decimales, sin transformación de datum.
- RF-25: SI la definición de un SC no se puede expresar en un `.prj` o en un GeoPackage, ENTONCES EL SISTEMA no la escribirá, exportará igualmente las coordenadas y lo indicará en el resumen (S055/RF-16, S053/RF-8).
- RF-26: EL SISTEMA incluirá la definición del SC de cada SC de la biblioteca, generada del registro EPSG, para escribirla en los ficheros sin conexión.

### Compatibilidad (H1, H3)
- RF-27: EL SISTEMA mantendrá sin cambios lo que importa y exporta con los diez SC de España y con los proyectos ya creados (S029 a S031, S040, S042, S045, S048, S049, S053, S055).
- RF-28: EL SISTEMA volverá a ofrecer en los menús de un proyecto con un SC de la biblioteca las importaciones y exportaciones que S034/RF-29 desactivó, y quitará ese aviso.

## Requisitos no funcionales
- Transversales que aplican: RNF-T5 (importar por contenido, exportar con `CreateDocument` o compartir), RNF-T7, RNF-T8, RNF-T9 y RNF-T14.
- Constitución nº 2: la reproyección de un punto del SC de origen al del proyecto coincide con pyproj con la misma transformación a ≤ 1 mm, y la ida y vuelta cierra a ≤ 0,1 mm; se contrasta con al menos tres SC de otros países y dos cambios de datum (uno de parámetros y uno con rejilla).
- Reproyectar 100.000 puntos con un cambio de datum de parámetros tarda menos de 2 s en el móvil de referencia, y con rejilla, menos de 4 s.
- Las definiciones de los SC no aumentan el APK en más de 1 MB.

## Casos límite
| Caso | RF |
|---|---|
| DXF de un proyecto en Lambert-93 importado en Lambert-93 | RF-12, RF-13 |
| Shapefile en ED50 sobre un proyecto en un SC de la biblioteca | RF-8, RF-9 |
| `.prj` sin código EPSG | RF-5, RF-6 |
| Capa sin SC o con uno que no está en la biblioteca | RF-6 |
| Cambio de datum con rejilla sin descargar | RF-10 |
| Punto fuera de la rejilla | RF-11 |
| Precisión del cambio de datum de 3 m o desconocida | RF-9 |
| Ráster en altitud ortométrica sin geoide | RF-20 |
| SC sin definición expresable en `.prj` | RF-25 |
| KML en un proyecto con correcciones ITRF/WGS84 | RF-17, RF-24 |
| Proyecto de España | RF-27 |

## Fuera de alcance
- Crear o editar un SC → S035.
- Cambiar el SC de un proyecto ya creado → S035.
- Leer DWG → no previsto (RNF-T14).
- Transformaciones con el tiempo (ITRF a ETRS89 con época) → no previsto.
- WGS 84 real en el KML con un cambio de datum (se toma como el datum de las correcciones, como en S031) → no previsto.
- Remuestrear o reproyectar un ráster entero → no previsto (S045).
- Elegir a mano la transformación de cada importación → no previsto: se usa la más precisa y se avisa de su precisión (RF-8, RF-9).

## Criterios de finalización
- Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Vectores pyproj de reproyección con tres SC de otros países, dos cambios de datum y las definiciones de los SC, generados por `tools/geodesia`.
- Demo en el emulador: un proyecto en un SC de la biblioteca, importar un Shapefile en otro SC, ver el aviso de precisión, y exportar a GeoPackage y a Shapefile.
- Demo de campo: queda para el usuario.

## Dudas abiertas
- [SUPOSICIÓN: el nombre de un `.prj` sin código EPSG (por ejemplo «ETRS_1989_UTM_Zone_30N» de ESRI) se busca en la biblioteca normalizado; si el nombre de ESRI no coincide con el de EPSG, el usuario elige el SC (RF-6).]
- [SUPOSICIÓN: un LandXML o un DXF no traen SC; se usa el del proyecto como propuesta y el usuario puede elegir otro (RF-1).]
- [SUPOSICIÓN: la altura de un ráster o de una superficie declarada como ortométrica se pasa con el geoide del proyecto, no con uno propio del fichero (RF-19).]
- [NECESITA ACLARACIÓN: si 25 tareas no bastan para todo (diez formatos, la reproyección y las definiciones WKT), el plan propondrá dividirla en importaciones y exportaciones, y se preguntará antes.]

## Cambios en documentos anteriores (no se aplican hasta aprobar)
- `docs/hoja-de-ruta.md`: S057 pasa a `spec`; su alcance dice que quita el aviso de S034/RF-29.
- S034/RF-29: queda sustituido por S057/RF-28.
- S031/RF-7 y RF-24: valen también con un SC de la biblioteca (sin cambios de comportamiento).
- `docs/glosario.md`: se añaden «SC de origen» y «Definición del SC».
