# Spec 012 — Exportar CSV

> Hito: H1 · Depende de: S011 · Estado: aprobada (con suposiciones a revisar; ver "Dudas abiertas")

## Contexto y objetivo
Con S010 y S011 el topógrafo mide, escribe y corrige puntos, pero todavía no los puede sacar del móvil. El trabajo de campo acaba casi siempre en otro programa: QGIS, un CAD, un programa de topografía o una hoja de cálculo. El CSV es el formato que todos leen. Esta spec exporta los puntos de un proyecto, o los seleccionados en la lista de objetos, a un fichero CSV:
- con todas las columnas, en los formatos clásicos PENZD y PNEZD, o con las columnas que se elijan;
- con el separador y el signo decimal que pida el programa de destino;
- guardándolo donde se quiera o compartiéndolo con otra app.

Lo que sale del móvil tiene que valer lo mismo que lo que se midió (constitución nº 1 y nº 2): ningún redondeo por debajo de 0,1 mm, ni puntos que falten o sobren.

## Usuarios / actores
- Topógrafo que termina un trabajo en campo y se lleva los puntos a la oficina, o los envía a un compañero.
- Técnico de oficina que abre el fichero en QGIS, en un CAD o en una hoja de cálculo.

## Historias de usuario
- H1: Como topógrafo quiero exportar todos los puntos de un proyecto, o solo los que elija, sin que se cuele ninguno de la papelera.
- H2: Como topógrafo quiero elegir el formato del fichero según el programa que lo va a leer.
- H3: Como técnico quiero que los números y los textos se escriban de forma que mi programa los lea sin retocarlos.
- H4: Como topógrafo quiero guardar el fichero donde quiera o mandarlo directamente por correo o mensajería.

## Requisitos funcionales (criterios de aceptación en EARS)
### Qué puntos (H1)
- RF-1: CUANDO el usuario elija "Exportar CSV" en el menú de un proyecto o en el de su lista de objetos, EL SISTEMA preparará la exportación con todos los puntos activos del proyecto, ocultos incluidos.
- RF-2: CUANDO el usuario elija "Exportar" con puntos seleccionados en la lista de objetos, EL SISTEMA preparará la exportación solo con esos puntos.
- RF-3: EL SISTEMA no exportará nunca los puntos de la papelera.
- RF-4: SI no hay ningún punto que exportar, ENTONCES EL SISTEMA lo indicará y no creará el fichero.

### Formato (H2)
- RF-5: EL SISTEMA ofrecerá cuatro formatos: "Todas las columnas", "PENZD", "PNEZD" y "Personalizado".
- RF-6: EL SISTEMA escribirá en "Todas las columnas", por este orden:
  - nombre, descripción y origen (medido o manual);
  - Este, Norte y altura en el SC del proyecto;
  - latitud, longitud y altura elipsoidal;
  - SC del proyecto, sistema de alturas y unidad lineal;
  - de la medida: altura del jalón, solución, satélites, HDOP, edad de las correcciones, precisiones horizontal y vertical, modo, épocas usadas y descartadas, si viene de una reproducción, condiciones del control de precisión que incumplió, inicio y fin de la medida, la base con la que se midió (S015/RF-22) su inclinación (S016/RF-24), el tiempo promediado, las desviaciones típicas y si se midió en automático (S017/RF-14);
  - si está editado y de qué punto es copia.
- RF-7: EL SISTEMA escribirá en PENZD el nombre, el Este, el Norte, la altura del proyecto y la descripción, por este orden, y en PNEZD lo mismo con el Norte antes que el Este.
- RF-8: CUANDO el usuario elija "Personalizado", EL SISTEMA permitirá marcar qué columnas de "Todas las columnas" se escriben, en el orden de esa lista.
- RF-9: EL SISTEMA escribirá en todos los formatos una primera fila con los nombres de las columnas.
- RF-10: SI el SC del proyecto es geográfico, ENTONCES EL SISTEMA no ofrecerá PENZD ni PNEZD, porque no hay Este ni Norte.

### Números y textos (H3)
- RF-11: EL SISTEMA escribirá Este, Norte y alturas en la unidad lineal del proyecto con 4 decimales, y latitud y longitud en grados decimales con signo y 9 decimales.
- RF-12: EL SISTEMA dejará vacía la celda de un valor que el punto no tiene, como la altura de un punto sin altura o la calidad de un punto manual.
- RF-13: EL SISTEMA permitirá elegir el separador de columnas (coma, punto y coma o tabulador) y el signo decimal (punto o coma), con coma y punto por defecto.
- RF-14: SI el separador y el signo decimal son los dos la coma, ENTONCES EL SISTEMA lo indicará y no permitirá exportar.
- RF-15: SI un texto contiene el separador, comillas o un salto de línea, ENTONCES EL SISTEMA lo escribirá entre comillas dobles, con las comillas que contenga duplicadas.
- RF-16: EL SISTEMA escribirá el fichero en UTF-8 sin BOM.

### Guardar o compartir (H4)
- RF-17: CUANDO el usuario pulse "Guardar", EL SISTEMA pedirá con el selector de ficheros del móvil dónde guardarlo y lo escribirá allí.
- RF-18: CUANDO el usuario pulse "Compartir", EL SISTEMA ofrecerá el fichero a las apps del móvil que lo acepten.
- RF-19: EL SISTEMA propondrá como nombre del fichero el del proyecto, el formato y la fecha y hora.
- RF-20: SI no se puede escribir el fichero, ENTONCES EL SISTEMA indicará que no se ha exportado.
- RF-21: EL SISTEMA recordará el último formato, separador, signo decimal y columnas elegidos, para todos los proyectos.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T7 y RNF-T8 (textos en recursos, 56 dp, TalkBack).
  - RNF-T9: los números del fichero no dependen del idioma de la interfaz; solo del signo decimal elegido.
  - RNF-T10: las fechas del fichero van en UTC.
  - RNF-T11: el fichero no lleva nada de los perfiles NTRIP.
  - RNF-T13: la exportación no bloquea la interfaz.
- Constitución nº 2: leído de nuevo, cada valor del fichero difiere del guardado menos de 0,05 mm (o 5e-10° en latitud y longitud).
- Con 10 000 puntos, el fichero se escribe en menos de 3 s en el móvil de referencia.

## Casos límite
| Caso | RF |
|---|---|
| Proyecto sin puntos, o con todos en la papelera | RF-3, RF-4 |
| Puntos ocultos | RF-1 (se exportan) |
| Punto sin altura | RF-12 |
| Punto manual (sin jalón ni calidad) | RF-12 |
| Punto editado | RF-6 (coordenadas actuales y la marca de editado) |
| Descripción con comas, punto y coma, comillas o saltos de línea | RF-15 |
| Separador y decimal los dos coma | RF-14 |
| Proyecto en geográficas | RF-10 |
| Proyecto en pies o pies topográficos de EE. UU. | RF-11 |
| Nombre de proyecto con caracteres que no valen en un nombre de fichero | RF-19 |
| El usuario cancela el selector de ficheros | RF-17 (no se escribe nada) |
| No se puede escribir (sin espacio, permiso retirado) | RF-20 |

## Fuera de alcance
- Paquete del proyecto (`.topoproj`) y copias de seguridad → S013.
- Importar CSV → S028.
- DXF, Shapefile y KML/KMZ → S029 a S031.
- Códigos, atributos, líneas y polígonos en el fichero → S024 y S025 (añadirán sus columnas).
- Coordenadas de una localización → S039.
- Codificaciones distintas de UTF-8 (Windows-1252) → no previsto.
- Cambiar el orden de las columnas del formato personalizado → no previsto.
- Exportar varios proyectos a la vez → no previsto.

## Criterios de finalización
- Cada RF tiene un test automático en verde que lo cita, y `rf_report` no muestra RF huérfanos.
- Test de ida y vuelta: el CSV leído con otra herramienta (Python) da los mismos valores que la base de datos, con todas las combinaciones de separador y signo decimal.
- Demo en el emulador:
  - exportar todo con "Todas las columnas" y guardarlo;
  - exportar una selección en PENZD con punto y coma y coma decimal, y compartirla;
  - abrir los ficheros en QGIS.
- Demo de campo: queda para el usuario.

## Dudas abiertas
Resueltas con una suposición. Están marcadas para revisarlas; si alguna no te vale, se cambia la spec antes de implementarla.
- [SUPOSICIÓN: los nombres de las columnas van en inglés (Name, Easting, Northing, Elevation, Description…), como en la app de referencia, para que otros programas las reconozcan; no dependen del idioma de la app.]
- [SUPOSICIÓN: las filas van en el orden en que se crearon los puntos, del más antiguo al más reciente.]
- [SUPOSICIÓN: las fechas se escriben en ISO 8601 con la Z de UTC (2026-10-18T18:09:31Z).]
- [SUPOSICIÓN: las líneas acaban en CRLF, como pide el RFC 4180.]
- [SUPOSICIÓN: latitud, longitud y altura elipsoidal son las del datum de los cálculos (ETRS89 o REGCAN95), también en un proyecto ED50, como en la ficha del punto (S011).]
- [SUPOSICIÓN: no se ofrece el espacio como separador, porque las descripciones llevan espacios.]
- [SUPOSICIÓN: el nombre del fichero es "<proyecto>_<formato>_<AAAAMMDD-HHMM>.csv", con los caracteres que no valen en un nombre de fichero cambiados por "_".]
- [SUPOSICIÓN: la exportación es de los puntos del proyecto tal como están; no cambia nada en el proyecto ni en los puntos.]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`, fila S012: "PENZD/PNEZD con cabecera", "todos los puntos o una selección" y "UTF-8".
- `docs/glosario.md`: se añaden "Exportar" (`export`), "Formato de exportación" (`ExportFormat`: todas las columnas, PENZD, PNEZD, personalizado), "Separador de columnas" (`columnSeparator`) y "Signo decimal" (`decimalSeparator`).
