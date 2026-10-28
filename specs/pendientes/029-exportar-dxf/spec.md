# Spec 029 — Exportar a DXF

> Hito: H4 · Depende de: S025 (los puntos solo necesitan S012) · Estado: borrador (sin entrevista, 28/10/2026)

## Contexto y objetivo
El trabajo de campo acaba casi siempre en un CAD: el delineante quiere los puntos con su cota y su nombre escritos al lado, las líneas como polilíneas y todo ordenado en capas por código, con colores, para dibujar el plano sin rehacer nada. El CSV (S012) obliga a importar los puntos a mano y no trae líneas; el GeoPackage (S053) y el Shapefile (S030) son para el SIG, no para el CAD.

Esta spec exporta los puntos del proyecto, y sus líneas y polígonos cuando exista S025, a un DXF de texto en versión R12, el que lee cualquier CAD. Lo importado de un DXF (S040) y las superficies no se exportan. La exportación no modifica nada del proyecto (constitución nº 1).

## Usuarios / actores
- Delineante o técnico de oficina que dibuja el plano en un CAD.
- Topógrafo que entrega el levantamiento en DXF.

## Historias de usuario
- H1: Como delineante quiero un DXF con capas por código, con el color de cada código, para dibujar sin retocar.
- H2: Como delineante quiero las etiquetas de nombre, cota y descripción junto a cada punto, y poder elegir cuáles.
- H3: Como topógrafo quiero elegir las coordenadas del SC del proyecto (locales) o geográficas (globales), y exportar todo o una selección.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Capa del DXF**: cada capa de dibujo del CAD; una por código (S023, S024) y las de etiquetas.
- **Color ACI**: uno de los 255 colores indexados estándar de los CAD.
- **Coordenadas locales**: las del SC del proyecto. **Coordenadas globales**: longitud y latitud del datum de los cálculos (como S012/RF-6).

### Contenido (H1, H3)
- RF-1: CUANDO el usuario elija "Exportar DXF" en el menú de un proyecto o en el de su lista de objetos, EL SISTEMA preparará la exportación con todos los puntos activos del proyecto, ocultos incluidos, y nunca los de la papelera (como S012/RF-1, RF-3).
- RF-2: CUANDO el usuario elija "Exportar" con puntos seleccionados en la lista de objetos, EL SISTEMA exportará solo esos puntos (como S012/RF-2).
- RF-3: DONDE el proyecto tenga líneas o polígonos propios (S025), EL SISTEMA los escribirá como polilíneas 3D, los polígonos como polilíneas cerradas, en la capa de su código.
- RF-4: EL SISTEMA escribirá cada punto como un punto del DXF en la capa de su código; los puntos sin código, y todos mientras no existan códigos, van a la capa "SIN_CODIGO".
- RF-5: EL SISTEMA dará a cada capa de código el nombre del código limpiado para el CAD (en mayúsculas, hasta 31 caracteres, solo letras, cifras, "_", "-" y "$", sustituyendo el resto por "_") y sin repetir ninguno.
- RF-6: EL SISTEMA dará a cada capa de código el color ACI más cercano al color del código y, si no lo tiene, el color 7.

### Etiquetas y alturas (H2)
- RF-7: EL SISTEMA ofrecerá las casillas "Nombre", "Cota" y "Descripción", marcadas "Nombre" y "Cota" por defecto, y escribirá cada etiqueta marcada como un texto junto al punto, en la capa "NOMBRES", "COTAS" o "DESCRIPCIONES".
- RF-8: EL SISTEMA permitirá elegir la altura del texto, de 0,01 m a 100 m, con 0,5 m por defecto, y la escribirá en las unidades del dibujo.
- RF-9: EL SISTEMA escribirá la altura del proyecto como Z de cada punto y de cada vértice, y 0 si no tiene, y contará esos casos en el resumen.
- RF-10: EL SISTEMA escribirá los números con el punto decimal, sin redondear por debajo de 0,1 mm (4 decimales en la unidad lineal del proyecto, 9 en grados) y sin usar la configuración regional.

### Sistema de coordenadas (H3)
- RF-11: EL SISTEMA escribirá por defecto coordenadas locales, con el Este como X y el Norte como Y, en la unidad lineal del proyecto.
- RF-12: DONDE el usuario elija coordenadas globales, EL SISTEMA escribirá la longitud como X, la latitud como Y, en grados decimales, y la altura elipsoidal como Z.
- RF-13: SI el SC del proyecto es geográfico, ENTONCES EL SISTEMA solo ofrecerá coordenadas globales (como S012/RF-10).
- RF-14: EL SISTEMA indicará en el resumen la unidad de las coordenadas, porque el DXF R12 no puede declararla.

### Textos, guardar y errores (H3)
- RF-15: EL SISTEMA escribirá el fichero en Windows-1252, la codificación de 8 bits que entienden los CAD con R12, y sustituirá por "?" los caracteres que no caben, contándolos en el resumen.
- RF-16: EL SISTEMA propondrá como nombre del fichero el del proyecto y la fecha y hora (como S012/RF-19).
- RF-17: CUANDO el usuario pulse "Guardar" o "Compartir", EL SISTEMA pedirá dónde guardarlo con el selector de ficheros del móvil o lo ofrecerá a las apps del móvil (como S012/RF-17 y RF-18).
- RF-18: SI no hay ningún punto que exportar, ENTONCES EL SISTEMA lo indicará y no creará el fichero (como S012/RF-4).
- RF-19: SI no se puede escribir el fichero, ENTONCES EL SISTEMA indicará que no se ha exportado y no dejará un fichero a medias (RNF-T6).
- RF-20: EL SISTEMA mostrará al terminar un resumen con los puntos, líneas, polígonos y capas escritos, los vértices sin altura y los caracteres sustituidos, y recordará las opciones elegidas para todos los proyectos (como S012/RF-21).
- RF-21: EL SISTEMA no modificará ningún dato del proyecto al exportar.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T5: guardar con `CreateDocument` o compartir mediante FileProvider.
  - RNF-T6: la exportación se escribe entera o no se escribe.
  - RNF-T7 y RNF-T8: textos en recursos; zonas táctiles de 56 dp.
  - RNF-T9: el fichero usa `Locale.ROOT`; ángulos y coordenadas globales en grados.
  - RNF-T13: la exportación no bloquea la interfaz.
- Exportar 10.000 puntos con dos etiquetas cada uno tarda menos de 5 s en el móvil de referencia y el fichero pesa menos de 10 MB.
- Constitución nº 2: leído de nuevo, cada coordenada del fichero difiere de la guardada menos de 0,05 mm (5e-10° en grados) y las globales coinciden con pyproj a menos de 1 mm.
- El color ACI elegido es el más cercano a la tabla estándar de 255 colores, comprobado frente a un cálculo aparte.

## Casos límite
| Caso | RF |
|---|---|
| Exportar sin puntos | RF-18 |
| Códigos con espacios, acentos, más de 31 caracteres o que dan el mismo nombre de capa | RF-5 |
| Código sin color | RF-6 |
| Proyecto sin códigos (antes de S024) | RF-4 |
| Puntos y vértices sin altura | RF-9 |
| Descripción con caracteres fuera de Windows-1252 | RF-15 |
| Proyecto geográfico | RF-13 |
| Proyecto en pies | RF-11, RF-14 |
| Exportar con espacio insuficiente | RF-19 |
| Proyecto sin líneas ni polígonos | RF-3 |

## Fuera de alcance
- Versiones posteriores a R12, DXF binario y DWG → no previsto.
- Exportar los dibujos importados (S040, S048) o las superficies → no previsto.
- Bloques, símbolos por código, tipos de línea y rellenos → no previsto.
- Texto girado según la línea o colocado por el usuario → no previsto.
- Shapefile → S030; KML/KMZ → S031; GeoPackage → S053.

## Criterios de finalización
- Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Un DXF exportado con códigos, etiquetas, líneas y un polígono se abre en QGIS (OGR) con las mismas coordenadas, capas y colores, y se relee con el lector de S040 sin diferencias (ida y vuelta).
- Coordenadas globales comparadas con pyproj (≤ 1 mm).
- Demo en el emulador: exportar con y sin selección, locales y globales, con y sin etiquetas, y abrir el fichero en QGIS.
- Demo de campo: queda para el usuario (abrir el fichero en el CAD de la oficina).

## Dudas abiertas
- [SUPOSICIÓN: las coordenadas se escriben en la unidad lineal del proyecto, no siempre en metros, porque el CAD del delineante suele trabajar en la unidad del proyecto (RF-11, RF-14).]
- [SUPOSICIÓN: las coordenadas globales llevan la altura elipsoidal como Z, no la altura del proyecto (RF-12).]
- [SUPOSICIÓN: un punto o vértice sin altura se escribe con Z = 0 y se avisa en el resumen, en vez de omitirlo (RF-9).]
- [SUPOSICIÓN: las etiquetas van en capas propias ("NOMBRES", "COTAS", "DESCRIPCIONES"), y no una por código, para poder apagarlas de una vez (RF-7).]
- [SUPOSICIÓN: con coordenadas globales la altura del texto se da en metros y se convierte a grados (1° ≈ 111.320 m) al escribirla (RF-8).]
- [SUPOSICIÓN: la dependencia de S025 de la hoja de ruta se mantiene, pero los puntos se exportan ya con S012 y las líneas y los códigos se añaden después, como en S053.]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`, fila S029: pasa a `spec`; "Depende de" pasa a "012 (códigos, líneas y polígonos: 024, 025)".
- `docs/glosario.md`: se añaden "Capa del DXF" (`DxfLayer`) y "Color ACI" (`AciColor`).
- S012: su "Fuera de alcance" ya apunta a S029; sin cambios.
