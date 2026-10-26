# Spec 053 — Exportar a GeoPackage

> Hito: H4 · Depende de: S012 (las líneas y polígonos, además, de S025) · Estado: aprobada (26/10/2026)

## Contexto y objetivo
El técnico SIG que recibe los datos del campo quiere abrirlos en QGIS sin conversiones: un solo fichero con sus capas, el sistema de coordenadas declarado y los datos de calidad de cada punto como atributos. El CSV (S012) obliga a declarar el sistema a mano y pierde los tipos de las columnas, y el Shapefile (S030) trunca los nombres de los atributos a 10 caracteres.

Esta spec exporta los puntos del proyecto, y sus líneas y polígonos cuando exista S025, a un `.gpkg`. No modifica nada del proyecto (constitución nº 1). La importación de un `.gpkg` es S048.

## Usuarios / actores
- Técnico SIG que recibe los datos del campo en QGIS.
- Topógrafo que entrega un trabajo con sus datos de calidad.

## Historias de usuario
- H1: Como técnico SIG quiero abrir el fichero exportado en QGIS con su sistema de coordenadas ya declarado.
- H2: Como topógrafo quiero que cada punto lleve sus datos de calidad como atributos, con su tipo.
- H3: Como topógrafo quiero exportar todos los puntos o solo una selección, y guardar o compartir el fichero.

## Requisitos funcionales (criterios de aceptación en EARS)
### Contenido (H1, H2, H3)
- RF-1: CUANDO el usuario elija "Exportar GeoPackage" en el menú de un proyecto o en el de su lista de objetos, EL SISTEMA preparará un fichero con una capa de puntos con todos los puntos activos del proyecto, ocultos incluidos, y nunca los de la papelera (como S012/RF-1, RF-3).
- RF-2: CUANDO el usuario elija "Exportar" con puntos seleccionados en la lista de objetos, EL SISTEMA preparará la capa de puntos solo con esos puntos (como S012/RF-2).
- RF-3: DONDE el proyecto tenga líneas o polígonos propios (S025), EL SISTEMA añadirá al fichero una capa de líneas y una de polígonos con sus vértices.
- RF-4: EL SISTEMA escribirá como atributos de la capa de puntos las columnas de "Todas las columnas" de S012/RF-6, con el nombre de la columna como nombre del atributo y con su tipo (texto, entero o decimal), y dejará vacío el atributo de un valor que el punto no tiene.
- RF-5: EL SISTEMA escribirá las coordenadas sin redondear y con la altura del proyecto como tercera coordenada de cada punto que la tenga.

### Sistema de coordenadas (H1)
- RF-6: EL SISTEMA escribirá cada capa en el sistema de coordenadas del proyecto, con su código declarado en el fichero.
- RF-7: EL SISTEMA permitirá elegir en su lugar las coordenadas geográficas del datum del proyecto.
- RF-8: SI el sistema de coordenadas del proyecto no tiene un código reconocido, ENTONCES EL SISTEMA declarará en el fichero su definición completa y lo indicará en el resumen.

### Guardar y errores (H3)
- RF-9: EL SISTEMA propondrá como nombre del fichero el del proyecto y la fecha y hora (como S012/RF-19).
- RF-10: CUANDO el usuario pulse "Guardar" o "Compartir", EL SISTEMA pedirá dónde guardarlo con el selector de ficheros del móvil o lo ofrecerá a las apps del móvil (como S012/RF-17 y RF-18).
- RF-11: SI no hay ningún punto que exportar, ENTONCES EL SISTEMA lo indicará y no creará el fichero (como S012/RF-4).
- RF-12: SI no se puede escribir el fichero, ENTONCES EL SISTEMA indicará que no se ha exportado y no dejará un fichero a medias (RNF-T6).
- RF-13: EL SISTEMA no modificará ningún dato del proyecto al exportar.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T5: guardar con `CreateDocument` o compartir mediante FileProvider.
  - RNF-T6: la exportación se escribe entera o no se escribe.
  - RNF-T7: textos en recursos; zonas táctiles de 56 dp (RNF-T8).
  - RNF-T9: las coordenadas se escriben en metros o en grados, sin unidades del proyecto.
- Exportar 10.000 puntos tarda menos de 5 s en el móvil de referencia.
- El `.gpkg` exportado se abre en QGIS sin errores, con el mismo número de puntos, las mismas coordenadas y los mismos atributos que el proyecto, y su sistema de coordenadas es el del proyecto.
- Constitución nº 2: las coordenadas geográficas exportadas coinciden con pyproj a menos de 1 mm.

## Casos límite
| Caso | RF |
|---|---|
| Exportar sin puntos | RF-11 |
| Exportar con proyecto geográfico | RF-6, RF-7 |
| Sistema de coordenadas sin código reconocido | RF-8 |
| Exportar con espacio insuficiente | RF-12 |
| Puntos sin altura o manuales sin calidad | RF-4, RF-5 |
| Proyecto sin líneas ni polígonos | RF-3 |

## Fuera de alcance
- Exportar las capas de diseño importadas o las superficies → no previsto.
- Exportar fotos de los puntos dentro del fichero → S031.
- Estilos de QGIS dentro del fichero → no previsto.
- Importar un GeoPackage → S048.
- Shapefile → S030; DXF → S029.

## Criterios de finalización
- Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Un `.gpkg` exportado con puntos se abre en QGIS con las mismas coordenadas y atributos (comprobado con la herramienta de QGIS).
- Demo en el emulador: exportar un proyecto con y sin selección, en el sistema del proyecto y en geográficas, y abrir el fichero en QGIS.
- Demo de campo: queda para el usuario.

## Dudas abiertas
- [SUPOSICIÓN: la exportación de líneas y polígonos (RF-3) se implementa solo tras S025; hasta entonces se exportan solo los puntos.]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`: nueva fila S053 en H4, en estado `spec`.
- `docs/glosario.md`: se añade "GeoPackage" si no lo añade S048.
