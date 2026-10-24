# Spec 028 — Importar puntos de un CSV o TXT

> Hito: H2 · Depende de: S011 · Estado: aprobada

## Contexto y objetivo
En obra y en oficina los puntos de diseño casi nunca se escriben a mano: vienen de un fichero de texto, de una hoja de cálculo exportada o de otro programa (un CAD, una estación total, otra app de campo). Con S011 solo se pueden añadir de uno en uno y S018 y S019 solo replantean los puntos que ya están en el proyecto. Esta spec trae esos puntos al proyecto: se elige un fichero CSV o TXT, se dice qué columna es cada cosa, se ve cómo quedaría y, con un botón, entran los puntos válidos y se informa de los que no valen.

Los datos de campo son sagrados (constitución nº 1): una importación nunca modifica ni sobrescribe un punto que ya existe, entra entera o no entra (una sola transacción), no se pierde nada de lo que traía el fichero (las coordenadas se guardan tal como vienen, sin redondear) y se puede deshacer al terminar.

Con esto, los puntos importados se replantean y se exportan como cualquier otro punto del proyecto, y se cumple la puerta G2b: "puntos de un CSV" replanteados con S018 y S019.

## Usuarios / actores
- Topógrafo en oficina o en campo, con un fichero de puntos de diseño o de coordenadas conocidas que quiere llevar al proyecto.
- Otra persona (proyectista, cliente) que entrega el fichero, a veces con coma decimal, con acentos en Windows-1252 o con una cabecera propia.

## Historias de usuario
- H1: Como topógrafo quiero elegir un fichero CSV o TXT y que la app lo lea aunque tenga otra codificación, otro separador o coma decimal.
- H2: Como topógrafo quiero decir qué columna es el nombre, el Este y el Norte (o la latitud y la longitud), la altura y la descripción, y ver cómo quedaría antes de importar.
- H3: Como topógrafo quiero que los nombres repetidos, las alturas vacías y las filas mal escritas no estropeen el proyecto y saber cuáles han fallado y por qué.
- H4: Como topógrafo quiero deshacer una importación equivocada con un botón.
- H5: Como topógrafo quiero replantear y exportar los puntos importados como cualquier otro.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Fila**: cada línea del fichero con datos, sin contar la cabecera ni las líneas vacías.
- **Fila válida**: la que tiene un nombre y unas coordenadas que se pueden leer y que caen dentro del ámbito del SC del proyecto.
- **Repetido**: un nombre que ya está en el proyecto (fuera de la papelera) o que ya ha salido antes en el fichero.
- **Asignación**: qué papel tiene cada columna del fichero (nombre, Este o latitud, Norte o longitud, altura, descripción o ninguno).

### Elegir y leer el fichero (H1)
- RF-1: CUANDO el usuario elija "Importar CSV/TXT" en el menú de un proyecto de la lista de proyectos o en el de su lista de objetos, EL SISTEMA abrirá el selector de ficheros del móvil y, con el fichero elegido, el asistente de importación de ese proyecto (RNF-T5).
- RF-2: SI el fichero está vacío, no es texto o no tiene ninguna fila con al menos dos columnas, ENTONCES EL SISTEMA no abrirá el asistente e indicará el motivo.
- RF-3: SI el fichero supera los 20 MB o las 50.000 filas, ENTONCES EL SISTEMA no abrirá el asistente e indicará el límite.
- RF-4: EL SISTEMA leerá el fichero como UTF-8, con o sin BOM, y, si no lo es, como Windows-1252, y permitirá elegir la otra codificación.
- RF-5: EL SISTEMA detectará el separador de columnas entre coma, punto y coma, tabulador y espacios, y permitirá elegir otro.
- RF-6: EL SISTEMA entenderá los textos entre comillas dobles, con las comillas duplicadas dentro, como en S012/RF-15.
- RF-7: EL SISTEMA detectará el signo decimal entre punto y coma, y permitirá elegir el otro.
- RF-8: SI el signo decimal elegido es la coma y el separador de columnas también, ENTONCES EL SISTEMA lo indicará y no permitirá importar (como S012/RF-14).
- RF-9: DONDE la primera fila no tenga ningún número en las columnas de coordenadas, EL SISTEMA la tratará como cabecera, con una casilla para cambiarlo.

### Asignar las columnas y el SC (H2)
- RF-10: EL SISTEMA propondrá la asignación por los nombres de la cabecera si los reconoce, también los de las columnas del CSV de S012, y, si no hay cabecera o no los reconoce, por el orden nombre, Este, Norte, altura y descripción (PENZD).
- RF-11: EL SISTEMA permitirá cambiar el papel de cada columna, con los formatos PENZD y PNEZD a un toque, y exigirá un nombre y dos coordenadas, sin repetir ningún papel.
- RF-12: EL SISTEMA propondrá las coordenadas en el SC del proyecto y permitirá elegir geográficas, con la latitud y la longitud en grados decimales, positivos al Norte y al Este.
- RF-13: DONDE las coordenadas sean geográficas, EL SISTEMA permitirá que la altura sea del sistema de alturas del proyecto o elipsoidal, salvo en un proyecto en ED50, que solo admite altitud (S011/RF-17).
- RF-14: EL SISTEMA propondrá para Este, Norte y altura la unidad lineal del proyecto y permitirá elegir metros, pies o pies topográficos de EE. UU. (RNF-T9).

### Opciones de la importación (H3)
- RF-15: SI la altura de una fila está vacía o no es un número, ENTONCES EL SISTEMA importará el punto sin altura (S011/RF-18).
- RF-16: DONDE el usuario marque "Tratar 0 como sin altura", desmarcada por defecto, EL SISTEMA importará sin altura los puntos cuya altura sea 0.
- RF-17: EL SISTEMA ofrecerá para los nombres repetidos "Saltar", "Renombrar" y "Cancelar la importación", con "Saltar" por defecto.
- RF-18: DONDE se elija "Renombrar", EL SISTEMA dará al repetido el siguiente nombre libre del proyecto, con la regla de S011/RF-16 a partir de su propio nombre.
- RF-19: EL SISTEMA no modificará nunca un punto que ya está en el proyecto.
- RF-20: EL SISTEMA quitará los espacios del principio y del final del nombre y de la descripción y conservará los ceros a la izquierda de un nombre numérico.

### Vista previa e informe de errores (H2, H3)
- RF-21: MIENTRAS el asistente esté abierto, EL SISTEMA mostrará las 20 primeras filas con la asignación aplicada y cuántas filas son válidas, repetidas y con error, sin crear ningún punto.
- RF-22: CUANDO el usuario cambie la codificación, el separador, el signo decimal, la cabecera, la asignación, el SC, la unidad o una opción, EL SISTEMA actualizará la vista previa y los recuentos.
- RF-23: SI una fila no tiene nombre, tiene unas coordenadas que no son números, tiene menos columnas que las necesarias o queda fuera del ámbito del SC del proyecto o del modelo de geoide con una altitud (S011/RF-19), ENTONCES EL SISTEMA la dará por errónea y dirá su número de línea del fichero y el motivo.
- RF-24: EL SISTEMA ofrecerá en el asistente la lista de filas erróneas con su línea y su motivo y permitirá guardarla o compartirla como un fichero de texto.

### Importar y deshacer (H3, H4)
- RF-25: CUANDO el usuario pulse "Importar", EL SISTEMA creará los puntos de las filas válidas en una sola transacción con durabilidad completa, en el orden del fichero (RNF-T6).
- RF-26: SI no se puede escribir en la base de datos, ENTONCES EL SISTEMA no creará ningún punto de la importación e indicará que no se ha importado.
- RF-27: CUANDO se cree un punto importado, EL SISTEMA guardará las coordenadas tal como venían, sin redondear, junto con las calculadas en la otra forma y la fecha y hora de la importación, sin jalón ni metadatos de calidad (como S011/RF-20).
- RF-28: CUANDO termine la importación, EL SISTEMA mostrará cuántos puntos ha importado, cuántos ha saltado por repetidos, cuántos ha renombrado y cuántos tenían error, con acceso al informe de errores.
- RF-29: CUANDO el usuario pulse "Deshacer importación" en el resultado y confirme, EL SISTEMA moverá a la papelera del proyecto todos los puntos de esa importación que sigan en él (S011/RF-22).
- RF-30: SI el usuario sale del asistente sin pulsar "Importar", ENTONCES EL SISTEMA no creará ningún punto.

### Los puntos importados (H5)
- RF-31: EL SISTEMA mostrará los puntos importados con el origen "Importado" en la lista de objetos y en la ficha, distinto de "Manual" y de "Medido".
- RF-32: EL SISTEMA escribirá "Imported" en la columna Origin del CSV de puntos (S012/RF-6).
- RF-33: EL SISTEMA tratará un punto importado como uno manual en todo lo demás: se edita con historial, se duplica, se borra a la papelera, se replantea (S018, S019), se oculta y sale en las exportaciones (S011, S012).
- RF-34: EL SISTEMA anotará en el log cada importación con su resultado, sin nombres ni coordenadas (S014/RF-5).

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T5: el fichero se elige con el selector de documentos y se valida por su contenido, no por su tipo MIME. No se guarda una copia del fichero en el almacenamiento del proyecto: los datos ya quedan en los puntos, y es una excepción que se justifica aquí.
  - RNF-T6: la importación y el deshacer se guardan en una sola transacción con durabilidad completa; al actualizar la app, los puntos y proyectos existentes se conservan (migración probada).
  - RNF-T7 y RNF-T8: textos en recursos; zonas táctiles de 56 dp; el estado de cada fila (válida, repetida, error) con icono y texto, nunca solo con color; TalkBack lee los recuentos.
  - RNF-T9: unidades lineales de la importación elegidas y mostradas como en el resto de la app; los números del fichero se leen con el signo decimal elegido, no con la configuración regional.
  - RNF-T13: leer, validar y volver a calcular la vista previa no bloquea el hilo principal más de 16 ms.
- Constitución nº 2: las coordenadas importadas en proyectadas dan las mismas geográficas que pyproj, y al revés, a menos de 1 mm, y la ida y vuelta difiere menos de 0,1 mm (como S011).
- Un fichero de 50.000 filas se lee y valida en menos de 3 s en el móvil de referencia, y cambiar una opción del asistente actualiza la vista previa en menos de 1 s.
- Importar 10.000 puntos válidos tarda menos de 5 s en el móvil de referencia.
- La lectura va por flujo: no se carga el fichero entero en memoria.

## Casos límite
| Caso | RF |
|---|---|
| Fichero vacío, binario o sin columnas | RF-2 |
| Fichero de más de 20 MB o 50.000 filas | RF-3 |
| UTF-8 con BOM, UTF-8 sin BOM y Windows-1252 con acentos | RF-4 |
| Separador coma, punto y coma, tabulador o espacios | RF-5 |
| Texto entre comillas con el separador dentro | RF-6 |
| Coma decimal con punto y coma de separador | RF-7 |
| Coma de separador y coma decimal | RF-8 |
| Fichero con y sin cabecera | RF-9, RF-10 |
| CSV exportado por TopoPhone, con todas las columnas | RF-10 |
| Asignación sin nombre, o con dos columnas de Este | RF-11 |
| Proyecto en geográficas | RF-12 (el SC del proyecto ya es geográfico) |
| Proyecto en ED50 | RF-13 |
| Fichero en pies con el proyecto en metros | RF-14 |
| Altura vacía, no numérica o 0 | RF-15, RF-16 |
| Nombre que ya existe o que se repite en el fichero | RF-17, RF-18, RF-19 |
| Nombre vacío o con espacios, o "001" | RF-20, RF-23 |
| Fila con menos columnas, con letras en una coordenada o fuera del huso | RF-23 |
| Miles con separador ("1.234,56") | RF-23 (no es un número) |
| Líneas vacías y última línea sin salto | RF-21 (no cuentan como filas) |
| Cambiar de opción con el asistente abierto | RF-22 |
| Fallo al escribir en la base de datos | RF-26 |
| Cierre de la app justo después de pulsar "Importar" | RNF-T6 |
| Salir del asistente sin importar | RF-30 |
| Deshacer cuando alguno de los puntos ya se ha borrado | RF-29 (solo los que siguen en el proyecto) |
| Importar dos veces el mismo fichero | RF-17 (los repetidos se saltan) |

## Fuera de alcance
- Importar un DXF → S040.
- Importar un Shapefile → S030.
- Importar un KML o KMZ → S031.
- Importar superficies o rasters → S042 y S045.
- Códigos y atributos de los puntos (las demás columnas se ignoran) → S023 y S024.
- Crear una línea o un polígono con los puntos importados → S025.
- Crear un proyecto nuevo desde el fichero → no previsto.
- Actualizar o sobrescribir puntos que ya existen → no previsto.
- Importar en el datum de las correcciones (coordenadas "globales") → no previsto.
- Latitud y longitud en grados, minutos y segundos o con letras N, S, E y O → no previsto.
- Hojas de cálculo (XLS y XLSX) → no previsto: se exportan antes a CSV.
- Detectar solo el sistema de coordenadas del fichero → no previsto.
- Guardar la asignación de columnas como plantilla → no previsto.
- Deshacer la importación después de salir de su pantalla de resultado → no previsto: se borran seleccionando los puntos (S011).
- Importar las fotos de los puntos → S017b y S031.

## Criterios de finalización
- Cada RF tiene un test automático en verde que lo cita, y `rf_report` no muestra RF huérfanos.
- Tests de la lectura frente a ficheros de ejemplo (`fixtures/formatos/csv/`): UTF-8 con y sin BOM, Windows-1252, los cuatro separadores, coma y punto decimal, comillas, con y sin cabecera, y el CSV que escribe S012.
- Test de las coordenadas importadas en proyectadas y geográficas frente a pyproj (≤ 1 mm; ida y vuelta ≤ 0,1 mm) en ETRS89, REGCAN95 y ED50.
- Test de migración de la base de datos con un proyecto de la versión anterior.
- Rendimiento: 50.000 filas leídas y validadas en menos de 3 s y 10.000 puntos importados en menos de 5 s.
- Demo en el emulador:
  - importar un PENZD con coma decimal y Windows-1252, con una fila errónea, un repetido y una altura vacía;
  - cambiar el separador y ver cómo se actualiza la vista previa;
  - ver el informe de errores y guardarlo;
  - ver los puntos con origen "Importado" en la lista, la ficha y el mapa;
  - replantear un punto importado y exportar el CSV de puntos;
  - deshacer la importación y recuperar los puntos de la papelera.
- Demo de campo: queda para el usuario (puerta G2b: puntos de un CSV replanteados y comprobados).

## Dudas abiertas
Resueltas con una suposición. Están marcadas para revisarlas; si alguna no te vale, se cambia la spec antes de implementarla.
- [SUPOSICIÓN: los límites de 20 MB y 50.000 filas (RF-3) salen de los 10.000 puntos de S011 con mucho margen; no hay un fichero real que los fije.]
- [SUPOSICIÓN: la latitud y la longitud de un fichero van siempre en grados decimales (RF-12); los grados, minutos y segundos se piden aparte.]
- [SUPOSICIÓN: la vista previa enseña 20 filas (RF-21).]
- [SUPOSICIÓN: el informe de errores se guarda como un fichero de texto con una línea por error, con el número de línea y el motivo (RF-24).]
- [SUPOSICIÓN: "Deshacer importación" solo existe en la pantalla del resultado (RF-29); luego se borran los puntos seleccionándolos.]
- [SUPOSICIÓN: la fecha y hora de un punto importado es la de la importación, como la de un punto manual (RF-27).]
- [SUPOSICIÓN: en un nombre repetido dentro del propio fichero, el primero entra y los siguientes son los repetidos (RF-17).]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`, fila S028: pasa a `spec`, y después a `tareas`, `en curso` e `implementada`.
- `docs/glosario.md`: se añaden "Punto importado" (`PointOrigin.IMPORTED`), "Importación" (`PointImport`), "Asignación (de columnas)" (`ColumnMapping`) y "Informe de errores (de la importación)" (`ImportReport`).
- S012/RF-6: la columna Origin escribe "Imported" para los puntos importados (RF-32).
