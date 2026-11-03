# Spec 023 — Bibliotecas de códigos

> Hito: H3 · Depende de: S006, S028, S013 · Estado: pendiente de aprobación (entrevistada 03/11/2026)

## Contexto y objetivo
Un levantamiento útil no es una nube de puntos con nombres: cada punto dice qué es (una arqueta, un bordillo, una farola) y, a veces, qué datos hay que anotar (diámetro, material, estado). Hoy un punto solo lleva su nombre y una descripción libre (S010/RF-4), y eso obliga a escribir lo mismo cientos de veces y a limpiarlo después en la oficina.

Esta spec trae las **bibliotecas de códigos**: listas de códigos de levantamiento que el topógrafo prepara en el móvil, o importa de un CSV, y vincula a un proyecto. Aquí solo se gestionan las bibliotecas; usarlas al medir es S024 y las líneas y polígonos son S025. Las bibliotecas son configuración y plantilla, no dato de campo (constitución nº 1): lo que se mide guardará el código por valor (S024), de modo que borrar o cambiar una biblioteca nunca altera un punto ya medido.

## Usuarios / actores
- Topógrafo que prepara su lista de códigos antes de salir al campo, o la trae de un CSV.
- Jefe de equipo que reparte la misma biblioteca a varios móviles (por CSV o con un paquete de proyecto, S013).

## Historias de usuario
- H1: Como topógrafo quiero crear, copiar y borrar bibliotecas de códigos y tener algunas ya hechas para empezar.
- H2: Como topógrafo quiero definir cada código con su descripción, geometría, color y atributos.
- H3: Como topógrafo quiero importar y exportar una biblioteca en CSV.
- H4: Como topógrafo quiero vincular una biblioteca a un proyecto y que el proyecto la lleve consigo.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Código**: un identificador corto (por ejemplo `ARQ`) con su descripción, geometría, color y atributos.
- **Geometría**: lo que representa el código: Punto, Línea o Polígono.
- **Atributo**: un dato que se anota en cada objeto con ese código. Tipos: Texto, Entero, Número y Lista (una opción de varias). Puede tener un valor por defecto.
- **Biblioteca**: un conjunto de códigos con nombre. Las **bibliotecas por defecto** vienen con la app y son de solo lectura.
- **Biblioteca vinculada**: la que un proyecto lleva consigo, como copia propia del proyecto (RF-20).

### Gestionar bibliotecas (H1)
- RF-1: CUANDO el usuario abra "Bibliotecas de códigos" en Ajustes, EL SISTEMA listará las bibliotecas con su nombre y su número de códigos, y marcará con un texto las que son por defecto.
- RF-2: EL SISTEMA permitirá crear, renombrar, duplicar y borrar bibliotecas, con confirmación al borrar, y exigirá un nombre no vacío y distinto del de las demás.
- RF-3: EL SISTEMA incluirá dos bibliotecas por defecto, «Topografía general» y «Urbanización», en español y con el contenido del anexo, y permitirá duplicarlas para editar la copia, pero no modificarlas ni borrarlas.
- RF-4: CUANDO el usuario borre una biblioteca, EL SISTEMA no modificará ningún proyecto ni ningún punto (RF-20).

### Definir códigos (H2)
- RF-5: CUANDO el usuario añada o edite un código de una biblioteca editable, EL SISTEMA le pedirá el código, la descripción (opcional), la geometría, el color y los atributos, y permitirá borrarlo con confirmación.
- RF-6: SI el código ya existe en la biblioteca, ENTONCES EL SISTEMA no lo guardará e indicará el código en conflicto; la comparación no distingue mayúsculas, acentos ni espacios en los extremos.
- RF-7: SI el código está vacío, tiene más de 32 caracteres o contiene un salto de línea, ENTONCES EL SISTEMA no lo guardará e indicará el motivo.
- RF-8: EL SISTEMA ofrecerá una paleta de colores para el código y, si no se elige ninguno, asignará uno por defecto según la geometría; el color se mostrará siempre junto al texto del código (RNF-T8).
- RF-9: EL SISTEMA permitirá hasta 100 atributos por código, con nombre distinto dentro del código, tipo y valor por defecto opcional válido para su tipo; un atributo de tipo Lista tendrá de 1 a 50 opciones distintas.
- RF-10: EL SISTEMA permitirá reordenar los atributos de un código, y borrar o cambiar un atributo no modificará ningún punto ya guardado.
- RF-11: CUANDO el usuario escriba en el buscador de una biblioteca, EL SISTEMA mostrará solo los códigos cuyo código o descripción contengan el texto, sin distinguir mayúsculas ni acentos.

### Importar y exportar CSV (H3)
- RF-12: CUANDO el usuario elija "Importar CSV" en una biblioteca, EL SISTEMA abrirá el fichero con el selector de documentos, lo reconocerá por su contenido (RNF-T5), ofrecerá la codificación, el separador y el signo decimal de S028/RF-4 a RF-9 y mostrará una vista previa antes de importar.
- RF-13: EL SISTEMA reconocerá las columnas por su cabecera: `Code`, `Description`, `Type` (Point, Line o Polygon), `Color` (#RRGGBB) y, por cada atributo N de 1 a 100, `Attribute_N_Name`, `Attribute_N_Type` (Text, Integer, Number o List), `Attribute_N_Default` y `Attribute_N_Options` (las opciones de una Lista separadas por `|`); SI falta `Code`, ENTONCES no importará nada e indicará el motivo.
- RF-14: CUANDO el usuario confirme la importación, EL SISTEMA la hará en una biblioteca nueva con el nombre del fichero o añadiéndola a una editable, y mostrará cuántos códigos entraron.
- RF-15: SI una fila es inválida (código vacío o demasiado largo, tipo o color desconocido, atributo mal definido), ENTONCES EL SISTEMA no la importará y la listará con su línea y su motivo, e importará las demás (como S028).
- RF-16: SI un código del fichero ya está en la biblioteca de destino o repetido en el propio fichero, ENTONCES EL SISTEMA los listará y permitirá elegir entre omitirlos y reemplazarlos, sin crear nunca dos códigos iguales (RF-6).
- RF-17: CUANDO el usuario elija "Exportar CSV", EL SISTEMA escribirá la biblioteca con las mismas columnas, en UTF-8 y con punto decimal (RNF-T9), y la guardará con el selector de ficheros o la compartirá con el FileProvider (RNF-T5), con el nombre de la biblioteca y la fecha como nombre propuesto.
- RF-18: EL SISTEMA dará, al exportar una biblioteca y volver a importarla, los mismos códigos, descripciones, geometrías, colores y atributos (con tipo, opciones y valor por defecto).

### Vincular a un proyecto (H4)
- RF-19: CUANDO el usuario cree o edite un proyecto (S006), EL SISTEMA le permitirá elegir una biblioteca vinculada o ninguna, y permitirá elegir en Ajustes la biblioteca que se propone en los proyectos nuevos.
- RF-20: CUANDO se vincule una biblioteca, EL SISTEMA guardará en el proyecto una copia de ella; los cambios posteriores de la biblioteca no cambiarán la copia, y el usuario podrá consultar los códigos del proyecto desde su menú.
- RF-21: CUANDO el usuario cambie o quite la biblioteca vinculada, EL SISTEMA pedirá confirmación y no modificará ningún punto.
- RF-22: EL SISTEMA incluirá las bibliotecas y la copia de cada proyecto en las copias de seguridad y los paquetes de proyecto (S013), y un proyecto restaurado en otro móvil conservará su copia aunque ese móvil no tenga la biblioteca original.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T5: ficheros con el selector de documentos, validados por contenido.
  - RNF-T6: cada alta, edición, borrado o importación se guarda en una sola transacción con durabilidad completa; al actualizar la app se conservan los datos (migración probada).
  - RNF-T7 y RNF-T8: textos en recursos; zonas táctiles de 56 dp; el color nunca es el único indicador.
  - RNF-T9: números de los ficheros con `Locale.ROOT`.
- Una biblioteca de 2.000 códigos se abre y se busca en menos de 1 s, y se importa de un CSV en menos de 3 s en el móvil de referencia.
- Una importación se hace entera o no se hace.

## Casos límite
| Caso | RF |
|---|---|
| Código repetido con otra mayúscula o acento | RF-6, RF-16 |
| Editar una biblioteca por defecto | RF-3 |
| CSV sin columna `Code`, con tipo o color desconocido | RF-13, RF-15 |
| Atributo de tipo Lista sin opciones o con un valor por defecto que no está en ellas | RF-9 |
| Borrar la biblioteca vinculada a un proyecto | RF-4, RF-20 |
| Proyecto sin biblioteca vinculada | RF-19 |
| Paquete restaurado en un móvil sin la biblioteca original | RF-22 |
| CSV en Windows-1252 o con coma decimal | RF-12 |

## Fuera de alcance
- Elegir un código al medir, crear códigos sobre la marcha, rellenar atributos y símbolos por código → S024.
- Líneas y polígonos con código → S025.
- Capas por código al exportar a DXF → S029.
- Subcódigos, jerarquías o atributos obligatorios o con rango → no previsto.
- Actualizar la copia del proyecto desde la biblioteca → no previsto.
- Compartir bibliotecas por la nube → no previsto (constitución nº 4).

## Criterios de finalización
- Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` (si se toca `tools/`) en verde, y test de migración de la base de datos.
- Demo en el emulador:
  - crear una biblioteca con un código de atributo Lista y otro de tipo Número, y duplicar una por defecto;
  - importar un CSV con un duplicado y filas inválidas, ver el informe, exportarlo y reimportarlo;
  - vincular la biblioteca a un proyecto, borrarla y comprobar que el proyecto conserva su copia.
- Demo de campo: queda para el usuario (importar la biblioteca que usa hoy con la app de referencia).

## Decisiones de la entrevista (03/11/2026)
- Un código es único en su biblioteca con una sola geometría: no puede haber el mismo código como Punto y como Línea (RF-6, RF-16).
- Hay dos bibliotecas por defecto: «Topografía general» y «Urbanización» (RF-3; contenido en el anexo, ajustable por el usuario al aprobar).
- La copia que lleva el proyecto es fija: actualizarla desde la biblioteca original queda fuera; para usar una versión nueva se cambia la biblioteca vinculada con confirmación (RF-20, RF-21).
- La copia del proyecto es solo de consulta; añadir códigos sobre la marcha es S024 (RF-20).
- Columnas de atributos del CSV: formato propio `Attribute_N_Name/Type/Default/Options` con las opciones separadas por `|`; se ajustará si el usuario aporta un CSV real de la app de referencia (RF-13, RF-18).
- Sin preguntar, por defecto razonable: 32 caracteres como máximo por código, 100 atributos por código y 50 opciones por lista (RF-7, RF-9); ninguna biblioteca se propone en los proyectos nuevos hasta que el usuario la elija en Ajustes (RF-19).

## Anexo · Contenido de las bibliotecas por defecto
| Biblioteca | Códigos (código · descripción · geometría) |
|---|---|
| Topografía general | VER vértice · Punto; BASE base de replanteo · Punto; EST estación · Punto; CTL punto de control · Punto; TER terreno · Punto; ARB árbol · Punto; POS poste · Punto; PIE pie de talud · Línea; CAB cabeza de talud · Línea; CAM camino · Línea; VAL valla · Línea; ARR arroyo · Línea; EDI edificio · Polígono; PAR parcela · Polígono; BAL balsa · Polígono |
| Urbanización | ARQ arqueta · Punto (atributos: Material lista, Diámetro Número); POZ pozo de registro · Punto; SUM sumidero · Punto; FAR farola · Punto; SEM semáforo · Punto; HID hidrante · Punto; VAD válvula · Punto; BOR bordillo · Línea; ACE acera · Línea; CAL calzada · Línea; EJE eje de calle · Línea; COL colector · Línea (atributos: Material lista, Diámetro Número); MUR muro · Línea; ZVE zona verde · Polígono; ESC escalera · Polígono |

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`, fila S023: dependencias «006, 028, 013» en lugar de «006».
- `docs/glosario.md`: se añaden «Geometría (del código)» (`CodeGeometry`: Punto, Línea, Polígono), «Biblioteca por defecto» (`DefaultLibrary`) y «Biblioteca vinculada» (`LinkedLibrary`); «Atributo» ya existe.
- S006: crear y editar un proyecto gana el campo «Biblioteca de códigos» (hoy «Biblioteca de códigos vinculada al proyecto → S023» en su fuera de alcance).
- S013: las copias y los paquetes incluyen las bibliotecas y la copia de cada proyecto (RF-22).
- La base de datos gana una versión (S017b se desplaza una más).
