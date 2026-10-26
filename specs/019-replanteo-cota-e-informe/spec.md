# Spec 019 — Cota de diseño e informe del replanteo

> Hito: H2 · Depende de: S018 · Estado: aprobada

## Contexto y objetivo
El replanteo de S018 lleva el jalón hasta el punto de diseño en planta, pero deja la altura como un simple dato. En obra la altura importa tanto como la posición: hay que saber cuánto falta para llegar a la cota de proyecto, si hay que excavar (desmonte) o rellenar (terraplén), y después hay que poder justificar el trabajo con un documento que diga qué se replanteó y cuánto se desvió.

Esta spec añade dos cosas. Primera, la cota de diseño: el replanteo muestra, junto a la diana horizontal, un nivel vertical con el desmonte o el terraplén y una tolerancia vertical, y guarda con cada punto replanteado si cumple. Segunda, el informe de replanteo: un resumen en pantalla y un CSV con todos los puntos replanteados, y las columnas del replanteo en el CSV de puntos de S012.

Esta spec sustituye a S018/RF-12 (la diferencia de cota como dato sin tolerancia) y precisa S018/RF-28 (la diferencia de cota se calcula contra la cota de diseño usada). Cierra la puerta de campo G2.

Los datos de campo siguen siendo sagrados (constitución nº 1): la cota de diseño que se escribe a mano no toca el punto de diseño, y cada replanteo queda guardado con las tolerancias con que se midió, para que el informe no cambie si el usuario las modifica después.

## Usuarios / actores
- Topógrafo en campo con el jalón y el RS3 en FIX (o una reproducción para probarlo), que replantea un punto y su cota.
- Técnico en oficina que revisa o entrega el informe de lo replanteado.
- Receptor RS3 o una reproducción como fuente de las épocas.

## Historias de usuario
- H1: Como topógrafo quiero ver cuánto me falta en vertical para llegar a la cota de diseño, y si es desmonte o terraplén, para ajustar la altura de la punta sin hacer cuentas.
- H2: Como topógrafo quiero un aviso al entrar en la tolerancia vertical, para saber cuándo estoy en cota sin mirar la pantalla.
- H3: Como técnico quiero un informe de lo replanteado con las desviaciones horizontal y vertical, para entregarlo o archivarlo.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec (los de S018 siguen valiendo):
- **Altura de la punta**: la altura de la punta del jalón en el SC del proyecto, la misma que muestra el panel de posición (altitud ortométrica o elipsoidal, según el SC; S008).
- **Cota de diseño**: la altura a la que hay que llegar: la del punto de diseño o la que escribe el usuario.
- **Diferencia de cota**: la altura de la punta menos la cota de diseño, con signo.
- **Desmonte**: la punta está por encima de la cota de diseño. **Terraplén**: está por debajo.
- **Tolerancia vertical**: el valor absoluto máximo de la diferencia de cota para darla por llegada.
- **Replanteo con cota**: el que tiene cota de diseño y punta con altura.

### Cota de diseño (H1)
- RF-1: MIENTRAS el replanteo esté abierto, EL SISTEMA tomará como cota de diseño la altura del punto de diseño en el SC del proyecto (S018/RF-4).
- RF-2: CUANDO el usuario escriba una cota de diseño en el replanteo, EL SISTEMA la usará en lugar de la del punto de diseño, sin modificar este.
- RF-3: CUANDO el usuario pulse "Usar la del punto" o pase a otro punto de diseño, EL SISTEMA descartará la cota escrita y volverá a la cota del punto de diseño.
- RF-4: SI el valor escrito como cota de diseño no es un número, ENTONCES EL SISTEMA no lo aplicará e indicará el motivo.
- RF-5: SI no hay cota de diseño (punto de diseño sin altura y nada escrito) o la punta no tiene altura, ENTONCES EL SISTEMA no mostrará el nivel vertical ni el desmonte o terraplén, e indicará cuál de las dos falta.

### Desmonte, terraplén y nivel vertical (H1, H2)
- RF-6: MIENTRAS haya un replanteo con cota, EL SISTEMA mostrará "Desmonte" (hay que excavar) o "Terraplén" (hay que rellenar) con el valor absoluto de la diferencia de cota, en las unidades lineales del proyecto, a cualquier distancia del punto y sea cual sea la diferencia, incluso de varios metros.
- RF-7: MIENTRAS haya un replanteo con cota, EL SISTEMA mostrará junto al mapa o a la diana horizontal, a cualquier distancia del punto, un nivel vertical con la banda de la tolerancia vertical y una burbuja que está en el centro con la punta en la cota de diseño, más arriba en desmonte y más abajo en terraplén, con su escala ajustada sola en pasos de 1, 2 y 5 por cada potencia de diez para que la burbuja y la banda estén siempre a la vista.
- RF-8: MIENTRAS el valor absoluto de la diferencia de cota sea igual o menor que la tolerancia vertical, EL SISTEMA mostrará el nivel en verde y el texto "En cota" (RNF-T8).
- RF-9: CUANDO el valor absoluto de la diferencia de cota pase a ser igual o menor que la tolerancia vertical, EL SISTEMA avisará con un sonido y una vibración, y CUANDO lo supere en más de 5 mm después de haber estado dentro, con otro sonido y otra vibración distintos; ambos avisos distintos de los de la tolerancia horizontal (S018/RF-20).
- RF-10: EL SISTEMA permitirá cambiar la tolerancia vertical, de 1 cm a 1 m, con 3 cm por defecto, en las unidades lineales del proyecto, y la recordará para todos los proyectos.
- RF-11: MIENTRAS la guía de S018 esté apagada por S018/RF-22, RF-24, RF-25 o RF-35, EL SISTEMA apagará también el nivel vertical y sus avisos, e indicará el mismo motivo.
- RF-12: EL SISTEMA mantendrá la llegada de S018/RF-19 a RF-21 (diana verde, "Dentro de la tolerancia" y avisos) dependiendo solo de la distancia horizontal, con o sin cota de diseño.

### Medir y guardar el punto replanteado (H3)
- RF-13: CUANDO termine la medida de un replanteo con cota, EL SISTEMA guardará con el punto replanteado la cota de diseño usada, la diferencia de cota entre lo medido y esa cota (medido menos diseño), sin redondear, y las tolerancias horizontal y vertical vigentes al medir. Con una superficie activa guarda también contra qué cota se replanteó y, si fue la superficie, cuál, su cota en lo medido y el desplazamiento vertical (S042/RF-28).
- RF-14: CUANDO termine la medida, EL SISTEMA marcará el punto replanteado como "dentro de la tolerancia" si la distancia horizontal guardada es igual o menor que la tolerancia horizontal y, en un replanteo con cota, el valor absoluto de la diferencia de cota guardada es igual o menor que la tolerancia vertical.
- RF-15: CUANDO se guarde el punto replanteado, EL SISTEMA mostrará en la confirmación (S018/RF-29) y en su ficha (S018/RF-32) la cota de diseño usada, la diferencia de cota como desmonte, terraplén o en cota, y si está dentro de la tolerancia.
- RF-16: MIENTRAS haya una medida en marcha en el replanteo, EL SISTEMA no permitirá cambiar la cota de diseño ni la tolerancia vertical (S018/RF-30).

### Informe de replanteo (H3)
- RF-17: CUANDO el usuario elija "Informe de replanteo" en el menú de un proyecto o en el de su lista de objetos, EL SISTEMA mostrará un resumen con los puntos de diseño replanteados, cuántos están dentro y fuera de la tolerancia, y las desviaciones horizontales y verticales máxima y media, contando solo el último replanteo de cada punto de diseño y, en la desviación vertical, solo los replanteos con cota.
- RF-18: CUANDO el usuario elija "Informe de replanteo" con puntos seleccionados en la lista de objetos, EL SISTEMA preparará el informe solo con esos puntos replanteados.
- RF-19: EL SISTEMA no incluirá nunca en el informe los puntos de la papelera (S011/RF-22).
- RF-20: SI el proyecto no tiene ningún punto replanteado que informar, ENTONCES EL SISTEMA lo indicará y no creará el fichero.
- RF-21: CUANDO el usuario pulse "Exportar" en el resumen, EL SISTEMA generará un CSV con una fila por cada punto replanteado, no solo el último de cada punto de diseño, y estas columnas por este orden: nombre del punto de diseño, número de replanteo (1 para `_stkd`, 2 para `_stkd2`), nombre del punto replanteado, descripción, Este, Norte y cota de diseño, Este, Norte y altura medidos, diferencias de Norte y Este, distancia horizontal, diferencia de cota, tolerancias horizontal y vertical, si está dentro de la tolerancia, si es el último replanteo de su punto de diseño, solución e inicio de la medida en UTC, en ISO 8601 como en S012 (RNF-T10). Desde S041 añade al final las columnas dibujo, capa, tipo de elemento, modo, PK y desplazamiento lateral, vacías en los puntos replanteados que vienen de un punto del proyecto (S041/RF-30), y desde S042 las columnas origen de la cota, superficie, cota de la superficie y desplazamiento vertical, vacías en los replanteos sin superficie activa (S042/RF-31).
- RF-22: DONDE el SC del proyecto sea geográfico, EL SISTEMA escribirá en el CSV del informe la latitud y la longitud en lugar del Este y el Norte (S012/RF-10, RF-11).
- RF-23: EL SISTEMA escribirá el CSV del informe con las reglas de formato de S012/RF-9, RF-11 y RF-12 a RF-16 y con el separador y el signo decimal elegidos en S012/RF-13, dejando vacía la celda de un valor que el punto no tiene, como la cota de un replanteo sin cota.
- RF-24: CUANDO el usuario pulse "Guardar" o "Compartir" en el informe, EL SISTEMA actuará como en S012/RF-17 a RF-20, proponiendo como nombre del fichero el del proyecto, "replanteo" y la fecha y hora.

- RF-26: SI un punto replanteado no tiene tolerancias guardadas (se replanteó antes de esta spec), ENTONCES EL SISTEMA lo contará en el resumen como "sin evaluar", ni dentro ni fuera de la tolerancia, lo incluirá en las desviaciones que tenga y dejará vacías en el CSV sus tolerancias y su "dentro de la tolerancia".

### Columnas del replanteo en el CSV de puntos (H3)
- RF-25: DONDE el formato de S012 sea "Todas las columnas" o "Personalizado", EL SISTEMA añadirá después de las columnas de S012/RF-6 las del replanteo (punto de diseño, cota de diseño, diferencias de Norte y Este, distancia horizontal, diferencia de cota y dentro de la tolerancia), vacías en los puntos que no son replanteados, sin cambiar PENZD ni PNEZD.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T2: pantalla encendida con el replanteo abierto.
  - RNF-T5: el CSV se guarda con el selector de ficheros o se comparte con FileProvider.
  - RNF-T6: los datos nuevos de un punto replanteado se guardan en la misma transacción que el punto; al actualizar la app los puntos y replanteos existentes se conservan, con las columnas nuevas vacías (migración probada).
  - RNF-T7 y RNF-T8: textos en recursos; zonas táctiles de 56 dp; "Desmonte", "Terraplén" y "En cota" nunca solo con color; TalkBack lee la diferencia de cota ("desmonte de 4 cm") al entrar en cada zona y a petición, sin anunciar cada refresco; los avisos son sonido y vibración.
  - RNF-T9: unidades lineales del proyecto en el nivel, la cota, las tolerancias y el informe.
  - RNF-T10: la hora del informe sale de la hora UTC del receptor.
  - RNF-T13: el nivel se refresca a 5 Hz como máximo y calcularlo a 10 Hz no bloquea el hilo principal más de 16 ms.
- Constitución nº 2: la diferencia de cota y la distancia horizontal del informe coinciden con las calculadas aparte a menos de 1 mm, y el resumen (conteos, máximos y medias) con el calculado aparte a menos de 0,1 mm.
- El nivel refleja una época nueva en menos de 300 ms desde que llega.
- Generar el resumen y el CSV de un proyecto con 1.000 puntos replanteados tarda menos de 2 s en el móvil de referencia (RNF-T13).

## Casos límite
| Caso | RF |
|---|---|
| Punto de diseño sin altura y sin cota escrita | RF-5 |
| Cota escrita que no es un número | RF-4 |
| Punta sin altura | RF-5 |
| Cambiar de punto de diseño con una cota escrita | RF-3 |
| Cota escrita para un punto de diseño sin altura | RF-2 |
| Diferencia de cota de varios metros, o lejos del punto | RF-6, RF-7 |
| Oscilar sobre el borde de la tolerancia vertical | RF-9 (5 mm de margen) |
| FIX perdido, inclinación sin estado 30, corte de datos, aún sin posición | RF-11 |
| Dentro en horizontal y fuera en vertical, o al revés | RF-12, RF-14 |
| Cambiar la cota o la tolerancia durante la medida | RF-16 |
| Replantear dos veces el mismo punto | RF-17, RF-21 |
| Replanteo sin cota en el informe | RF-14, RF-17, RF-23 |
| Tolerancias cambiadas después de replantear | RF-13 |
| Punto replanteado antes de esta spec, sin tolerancias guardadas | RF-26 |
| Punto replanteado en la papelera | RF-19 |
| Proyecto sin puntos replanteados | RF-20 |
| Proyecto en geográficas | RF-22 |
| Proyecto en pies o pies topográficos de EE. UU. | RF-6, RF-23 |
| Selección con puntos que no son replanteados | RF-18 (se ignoran) |
| El usuario cancela el selector de ficheros o no se puede escribir | RF-24 |
| Selección de columnas guardada antes de las columnas nuevas | RF-25 (no las incluye hasta que se marcan) |

## Fuera de alcance
- Replanteo de la cota sobre una superficie de LandXML → S042.
- Replanteo de la cota contra un raster de cotas → S045.
- Cota de diseño de los vértices, centros de bloque y líneas de un DXF → S041.
- Replanteo de la cota de las líneas propias → S027.
- Informe en PDF o con firma → no previsto.
- Informe con fotos de los puntos → S031.
- Informe de varios proyectos a la vez → no previsto.
- Medir solo al estar dentro de la tolerancia horizontal o vertical → no previsto.
- Cota de diseño distinta para un mismo punto de diseño que se conserve entre sesiones → no previsto.
- Desmonte y terraplén como volúmenes (m³) → no previsto.
- Importar los puntos de diseño de un CSV → S028.

## Criterios de finalización
- Cada RF tiene un test automático en verde que lo cita, y `rf_report` no muestra RF huérfanos.
- Test de la diferencia de cota, del desmonte o terraplén y de "dentro de la tolerancia" frente a un cálculo aparte (≤ 1 mm), con puntos de diseño con y sin altura y con cota escrita, en la Península y en Canarias.
- Test del resumen y del CSV del informe frente a un cálculo aparte (≤ 0,1 mm el resumen), con puntos replanteados varias veces y con y sin cota.
- Test de migración de la base de datos con un proyecto de la versión anterior con puntos replanteados.
- Reproducción sintética de un replanteo con cota, que sube y baja la punta alrededor de la cota de diseño, y su resultado esperado calculado aparte.
- Demo en el emulador con esa reproducción:
  - el nivel vertical con el desmonte, el terraplén y "En cota", y sus avisos;
  - escribir una cota a mano y volver a la del punto;
  - medir y ver en la ficha la cota, la diferencia y si cumple;
  - ver el resumen del informe, exportar el CSV y abrirlo en QGIS;
  - exportar el CSV de puntos con las columnas del replanteo.
- La puerta de campo G2 (`docs/validacion-campo/protocolo.md`, ensayos d, e con una cota de diseño y g) es del usuario, con el RS3.

## Dudas abiertas
- Resuelto: el nivel vertical y el valor se muestran siempre, a cualquier distancia y con cualquier diferencia (RF-6, RF-7).
- Resuelto: desmonte es la punta por encima de la cota de diseño (hay que excavar) y terraplén por debajo (hay que rellenar).
- Suposición a revisar: la cota escrita a mano se descarta al cambiar de punto de diseño y no se recuerda (RF-3). Recordarla por punto de diseño queda fuera de alcance.
- Suposición a revisar: la spec tiene 26 RF, algo por encima de los unos 20 que sugiere la skill, pero cabe en unas 25 tareas; no se propone dividirla.
