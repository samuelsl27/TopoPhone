# Spec 024 — Recolectar con códigos

> Hito: H3 · Depende de: S023, S010, S011 · Estado: pendiente de aprobación (entrevistada 04/11/2026)

## Contexto y objetivo
Con S023 el topógrafo ya tiene bibliotecas de códigos, pero al medir un punto solo puede escribir un nombre y una descripción (S010/RF-2, RF-4). Esta spec lleva los códigos al recolector: elegir el código del punto con unos toques, rellenar sus atributos, cambiarlos después si se equivocó y ver en el mapa cada punto con el color y el símbolo de su código.

El dato de campo es sagrado (constitución nº 1): el punto guarda su código, su color y los valores de sus atributos **por valor**, tal como estaban al medir. Cambiar o borrar después la biblioteca no toca ningún punto, y cambiar el código de un punto deja rastro en su historial (S011/RF-10). Aquí solo hay códigos de geometría Punto; los de línea y polígono llegan en S025.

## Usuarios / actores
- Topógrafo en campo, con una mano y a veces con guantes, que mide cientos de puntos con código.
- Técnico en oficina que recibe los puntos con su código y sus atributos.

## Historias de usuario
- H1: Como topógrafo quiero elegir el código al medir, con los recientes a mano y una búsqueda, sin perder tiempo.
- H2: Como topógrafo quiero crear un código nuevo en el campo, solo para este proyecto.
- H3: Como topógrafo quiero rellenar los atributos del código al medir y corregirlos después.
- H4: Como topógrafo quiero cambiar el código de uno o varios puntos ya medidos.
- H5: Como topógrafo quiero reconocer cada punto en el mapa por el color y el símbolo de su código.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec (los de S023 más):
- **Códigos del proyecto**: los de la biblioteca vinculada (su copia, S023/RF-20) más los creados en el proyecto (RF-5).
- **Código del punto**: el código con el que se guardó un punto, o ninguno ("Sin código").
- **Recientes**: los últimos 10 códigos distintos usados en el proyecto, el más reciente primero.
- **Símbolo**: la forma con que el mapa dibuja los puntos de un código: círculo, cuadrado, triángulo, rombo, cruz o estrella.

### Elegir el código al medir (H1)
- RF-1: MIENTRAS el recolector esté abierto, EL SISTEMA mostrará el código elegido junto al nombre y la descripción, y un selector de códigos con los recientes arriba, el resto de los códigos de geometría Punto del proyecto por orden alfabético y la opción "Sin código".
- RF-2: CUANDO el usuario escriba en el buscador del selector, EL SISTEMA mostrará solo los códigos cuyo código o descripción contengan el texto, sin distinguir mayúsculas ni acentos.
- RF-3: EL SISTEMA conservará el código elegido para el siguiente punto, como la descripción en S010/RF-4, y permitirá quitarlo con "Sin código".
- RF-4: DONDE el código tenga descripción y la descripción del punto esté vacía, EL SISTEMA propondrá la del código como descripción del punto, que el usuario podrá cambiar.

### Crear códigos sobre la marcha (H2)
- RF-5: CUANDO el usuario pulse "Nuevo código" en el selector, EL SISTEMA le pedirá código, descripción, color, símbolo y atributos con las reglas de S023/RF-5 a RF-9, con geometría Punto, y lo guardará solo en los códigos del proyecto, sin modificar ninguna biblioteca.
- RF-6: SI el código nuevo ya existe entre los del proyecto, ENTONCES EL SISTEMA no lo guardará, indicará el conflicto (S023/RF-6) y ofrecerá usar el existente.
- RF-7: CUANDO el usuario edite o borre un código creado en el proyecto, EL SISTEMA lo hará con confirmación y no modificará ningún punto ya guardado; los códigos que vienen de la biblioteca vinculada no se editan aquí.

### Atributos (H3)
- RF-8: DONDE el código elegido tenga atributos, EL SISTEMA mostrará en el recolector un formulario con un campo por atributo, precargado con su valor por defecto: texto libre, entero, número (con el signo decimal de la interfaz) o una lista de opciones.
- RF-9: SI el valor de un atributo no es válido para su tipo, ENTONCES EL SISTEMA lo marcará con su motivo y no permitirá medir hasta corregirlo; un atributo vacío es válido.
- RF-10: CUANDO termine una medida, EL SISTEMA guardará con el punto, en la misma transacción (RNF-T6), el código, su descripción, su color, su símbolo y los valores de los atributos con su nombre y su tipo, sin redondear los números.
- RF-11: MIENTRAS haya una medida en marcha, EL SISTEMA no permitirá cambiar de código ni de valores (como S018/RF-30).
- RF-12: EL SISTEMA mostrará en la ficha del punto (S011) el código y los valores de sus atributos, y permitirá corregirlos con rastro en el historial (S011/RF-10), validando como en RF-9.

### Cambiar el código (H4)
- RF-13: CUANDO el usuario elija "Cambiar código" en la ficha de un punto o con puntos seleccionados en la lista de objetos, EL SISTEMA le permitirá elegir otro código del proyecto, o ninguno, y lo aplicará a todos los puntos seleccionados o a ninguno.
- RF-14: CUANDO cambie el código de un punto, EL SISTEMA conservará los valores de los atributos con el mismo nombre y tipo que tenga el nuevo código, pondrá el valor por defecto en los demás, y guardará en su historial el código y los valores anteriores.
- RF-15: SI el cambio descarta algún valor distinto del vacío, ENTONCES EL SISTEMA lo avisará con los atributos afectados antes de aplicarlo.
- RF-16: EL SISTEMA no modificará la observación bruta ni las coordenadas de un punto al cambiar su código (RNF-T6).

### En el mapa y en la lista (H5)
- RF-17: EL SISTEMA dibujará en el mapa cada punto con el color y el símbolo de su código y, sin código, con los de S010/RF-20; los puntos fuera de tolerancia y los replanteados conservarán su marca propia (S010/RF-20, S018/RF-34).
- RF-18: EL SISTEMA mostrará el código en la lista de objetos y permitirá filtrarla por código y buscar por él, y distinguirá cada código con su color y con su texto, nunca solo con el color (RNF-T8).
- RF-19: CUANDO el usuario cambie el color o el símbolo de un código del proyecto, EL SISTEMA redibujará en el mapa los puntos de ese código con lo nuevo, sin modificar ningún punto guardado.

### Herencia y exportación (H1)
- RF-20: CUANDO se guarde un punto replanteado (S018/RF-27), EL SISTEMA le pondrá el código y los atributos del punto de diseño, con la posibilidad de cambiarlos como en RF-13.
- RF-21: EL SISTEMA añadirá a la exportación «Todas las columnas» de S012 la columna del código y una columna por cada atributo de los puntos exportados, y los escribirá como atributos tipados en la exportación a GeoPackage (S053/RF-4).

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T2: pantalla encendida con el recolector abierto.
  - RNF-T6: el punto con su código y sus atributos se guarda en una sola transacción con durabilidad completa; cambiar el código de una selección se aplica entero o no se aplica; al actualizar se conservan los puntos existentes (migración probada).
  - RNF-T7 y RNF-T8: textos en recursos; zonas táctiles de 56 dp; TalkBack lee el código con su descripción.
  - RNF-T9: números de los atributos con el signo decimal de la interfaz en pantalla y `Locale.ROOT` en los ficheros.
  - RNF-T13: el selector se abre y filtra 2.000 códigos sin bloquear el hilo principal más de 16 ms.
- Elegir un código reciente y medir requiere como máximo 2 toques más que medir sin código.
- Con 10.000 puntos con código, el mapa se dibuja en menos de 2 s (como S010).

## Casos límite
| Caso | RF |
|---|---|
| Proyecto sin biblioteca vinculada | RF-1, RF-5 |
| Atributo vacío (no hay obligatorios) | RF-9 |
| Código borrado de la biblioteca después de medir | RF-10, RF-19 |
| Cambiar a un código con atributos distintos | RF-14, RF-15 |
| Cambiar el código de una selección con puntos medidos y manuales | RF-13 |
| Código creado en el proyecto con el mismo nombre que uno de la biblioteca | RF-6 |
| Reproducción en lugar de receptor | RF-10 |
| Número con coma decimal en la interfaz | RF-8, RNF-T9 |

## Fuera de alcance
- Códigos de línea y polígono, empezar y continuar líneas → S025.
- Capas por código al exportar a DXF y atributos en Shapefile y KML → S029, S030, S031.
- Subir un código creado en el proyecto a una biblioteca → no previsto.
- Asignar código a los puntos de una importación CSV → no previsto.
- Atributos obligatorios, con rango o con fórmula → no previsto.
- Foto automática por código → no previsto.

## Criterios de finalización
- Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos, `./gradlew check` en verde y test de migración de la base de datos.
- Demo en el emulador con una reproducción:
  - medir con un código reciente y con uno de la búsqueda, con atributos de los cuatro tipos y un valor inválido;
  - crear un código sobre la marcha y borrar la biblioteca después;
  - cambiar el código de un punto y de una selección y ver el historial;
  - ver los símbolos en el mapa y el CSV con el código y los atributos.
- Demo de campo: queda para el usuario (un levantamiento real con su biblioteca).

## Decisiones de la entrevista (04/11/2026)
- Los atributos se rellenan antes de medir, con los valores por defecto del código y sin arrastrar los del punto anterior; no hay atributos obligatorios y uno vacío se guarda vacío (RF-8, RF-9).
- Un punto replanteado hereda siempre por valor el código, el color y los atributos de su punto de diseño, aunque el código no sea de geometría Punto o no exista entre los del proyecto; después se puede cambiar como en RF-13. En una línea de un dibujo CAD no hay código que heredar (RF-20).
- Al cambiar el código, los valores no vacíos que se pierden se avisan y se aplican solo tras confirmar; los anteriores quedan en el historial (RF-14, RF-15).
- El símbolo se elige solo en los códigos creados en el proyecto; los de biblioteca se dibujan con círculo porque el CSV de S023 no lleva símbolo y S023 no cambia (RF-5, RF-17).
- El mapa pinta con el color y el símbolo vigentes del código del proyecto; si el código ya no existe, con los que el punto guardó al medir, que son también los que usan las exportaciones (RF-10, RF-19).
- Un código creado en el proyecto no puede repetir uno de la biblioteca vinculada (sin distinguir mayúsculas ni acentos): se rechaza, se indica el conflicto y se ofrece usar el existente (RF-6).
- Sin preguntar, por defecto razonable: el recolector solo ofrece códigos de geometría Punto hasta S025 (RF-1).

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`, fila S024: dependencias «023, 010, 011».
- `docs/glosario.md`: se añaden «Código del proyecto» (`ProjectCode`), «Códigos recientes» (`RecentCodes`), «Símbolo (del código)» (`CodeSymbol`); «Código de levantamiento» (`SurveyCode`) y «Atributo» ya existen.
- S011: el punto gana código y atributos en la ficha, la lista y el historial (S011 «Códigos y atributos → S023 y S024»).
- S012: «Todas las columnas» (RF-6) gana el código y los atributos (RF-21).
- S018: el punto replanteado hereda el código (RF-20).
- S053/RF-4: los atributos de los códigos como columnas tipadas.
- La base de datos gana una versión (S017b se desplaza una más).
