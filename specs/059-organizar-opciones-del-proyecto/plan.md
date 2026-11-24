# Plan técnico — Spec 059

> Lee antes: `docs/constitution.md`, `docs/rnf-transversales.md` (RNF-T7, RNF-T8, RNF-T13), `specs/059-organizar-opciones-del-proyecto/spec.md` y los planes de S011 (lista de Objetos), S006/S009 (datos del proyecto), S036 (capas), S042, S049 y S058.

Sin dependencias nuevas, ni Gradle ni Python. Es una spec **de presentación**: no cambia `core:*`, ni Room, ni la lógica de ninguna opción (RF-7). Los iconos nuevos son vectores propios en `res/drawable`. Alternativas descartadas en las decisiones D1, D3 y D5.

## Lo que hay hoy (lo que se sustituye)
- `ObjectListScreen` (`ui/points`) pone en `ObjectsMenu` (el «⋮») una lista plana de 21 `DropdownMenuItem`, cada uno con su lambda (`onExport`, `onImportDxf`, `onCogo`…). Esas lambdas ya están en `ObjectListActions` y se conservan.
- La lista es un solo `LazyColumn`: buscador, filtro de códigos, aviso del jalón, las líneas (con tres filas de texto) y después los puntos. La búsqueda y el filtro por código también filtran las líneas (S025/RF-21), y `ObjectListUiState.lines` ya viene filtrado.
- `ProjectInfoScreen` (`ui/projects`) enseña el proyecto (SC, unidades) y el recuento de puntos y fotos. Su `ProjectInfoViewModel` solo conoce `ProjectRepository`, `PointRepository` y `PhotoRepository`.
- La superficie, el eje y las capas del mapa se gestionan en hojas **dentro** de `ProjectViewScreen` (`surfacesOpen`, `alignmentsOpen`, `layersOpen`, guardadas con `rememberSaveable`); no tienen ruta propia. La localización (`SurveyPage.Localization`) y el base shift (`SurveyPage.BaseShift`) sí; el terreno está en el formulario del proyecto (`SurveyPage.Form`).

## Decisiones que pide esta spec (para tu aprobación)
| # | Decisión | Propuesta | Alternativa descartada |
|---|---|---|---|
| D1 | Una sola lista de opciones (RF-2 a RF-6, criterio de finalización) | `ProjectOptions` (`ui/options`): una lista declarativa de `ProjectOption(id, grupo, texto, descripción?, icono, verbo de TalkBack)` con los grupos `IMPORT`, `EXPORT`, `TOOLS` y `MORE`. La pantalla pinta los menús desde ella y los tests comprueban orden, límite de 8 y que no hay ids repetidos, así que añadir una opción sin tocar la spec rompe un test. | Escribir cada fila a mano como hoy: obliga a duplicar la lista en los tests. |
| D2 | Cómo se ve cada menú (RF-1, RF-9, RF-10) | `GroupBar` (tres botones de igual ancho, 56 dp, icono y texto, con texto en dos líneas con letra al 130 %) y un `DropdownMenu` anclado a cada botón. Cierra al pulsar fuera y con «Atrás» sin código propio. Con 8 filas de 56 dp caben en 640 dp; con la letra al 130 % el menú se desplaza (lo permite la spec). | `ModalBottomSheet` por grupo: tapa la pantalla para una elección de un toque y la spec descarta la hoja con todas las opciones. |
| D3 | Pestañas y búsqueda (RF-11, RF-14) | `TabRow` de Material 3 con «Puntos (N)» y «Líneas y polígonos (M)». La búsqueda y los filtros por código viven **solo** en «Puntos» (RF-14), así que **la pestaña de líneas deja de filtrarse por ellos** y muestra todas las líneas. Es un cambio de S025/RF-21 que la spec no cita en «Cambios en documentos anteriores»: se corrige allí al aprobar este plan. El ViewModel pasa a dar las líneas sin filtrar y su total (`lineTotal`). | Dejar la búsqueda sobre las pestañas y que filtre las dos: contradice RF-14. |
| D4 | La sección «Activo en este proyecto» (RF-18, RF-19) | `ActiveItems.of(...)` (función pura, `ui/projects`) recibe lo que hay y devuelve la lista ordenada de `ActiveItem(tipo, título, estado)`; `ProjectInfoViewModel` la alimenta con `LocalizationRepository.active`, `BaseShiftRepository.active`, `SurfaceRepository.activeSurfaceId` y `surfaces`, `AlignmentRepository.activeAlignmentId` y `summaries`, `ProjectLayersRepository.layers` y el factor de `Project.terrain`. Todo son flujos, por lo que RF-22 se cumple sin código extra. El RMS de la localización sale de su modelo; si no lo tiene, el estado es «Activa» (RF-19). | Consultar al abrir la pantalla: RF-22 exige actualizar sin reabrir. |
| D5 | A dónde lleva cada fila (RF-20) | Localización y base shift: sus pantallas de hoy. Terreno: el formulario del proyecto con un indicador de «mostrar terreno» que desplaza hasta esa sección al abrir. Superficie, eje y capas: la vista del proyecto con un parámetro nuevo `openPanel` (`SURFACES`, `ALIGNMENTS`, `LAYERS`) que abre la hoja una sola vez al llegar. Si un elemento no tuviera ruta, su fila solo informa (RF-20b). | Crear pantallas propias de superficie, eje y capas: añade pantallas que la spec no pide. |
| D6 | Iconos (RF-5) | Unos 20 vectores simples propios (importar, exportar, una forma por formato, herramientas). | Añadir `material-icons-extended`: dependencia nueva de unos MB para 20 formas. |
| D7 | Orden de trabajo y tamaño | Menús primero (T1–T6), pestañas (T7–T11), lo activo (T12–T16) y cierre (T17–T20). Son 20 tareas. | — |

## Estructura
- `core:*` y `data` no cambian.
- `app/ui/options/` (nuevo): `ProjectOptions`, `GroupBar`, `GroupMenu`.
- `app/ui/points/`: `ObjectListScreen` se parte en la barra de grupos, las pestañas y sus dos contenidos (`ObjectPointsTab`, `ObjectLinesTab`); `ObjectsMenu` queda con las dos opciones del «⋮».
- `app/ui/projects/`: `ActiveItems`, `ActiveSection` y la ampliación de `ProjectInfoViewModel` y de la navegación (`openPanel`, terreno a la vista).
- Textos en `strings.xml` (es y en, RNF-T7).

## Piezas
### P1. `ProjectOptions`
Lista inmutable en el orden de RF-2 a RF-6. Cada opción lleva el verbo y el nombre para TalkBack («Importar DXF»). `GroupMenu` y los tests la recorren; el test de «ninguna opción se pierde» compara contra los ids de las 21 opciones de hoy.

### P2. `GroupBar` y `GroupMenu`
Tres botones `OutlinedButton` de 56 dp con icono y texto; cada uno abre su `DropdownMenu`. La fila lleva icono, nombre y, en «Herramientas», una descripción de una línea. Las filas de «Importar» y «Exportar» no la llevan (RF-5).

### P3. Pestañas de Objetos
`TabRow` con dos pestañas de 56 dp. La pestaña elegida se guarda con `rememberSaveable` (giro y proceso, RF-13) y vuelve a «Puntos» al abrir Objetos o cambiar de proyecto (RF-12). La barra de selección se queda en «Puntos» y las acciones por fila en «Líneas y polígonos» (RF-17).

### P4. Lo activo
`ActiveSection` pinta una `Card` con una fila de 56 dp por elemento (nombre y estado en texto, una línea con puntos suspensivos y el texto entero para TalkBack, RF-19b). No se pinta si la lista está vacía (RF-21).

## Riesgos
- Mover el menú rompe los tests de `ui/points` que lo abren por etiqueta: se migran en T6, no al final.
- Que `lines` deje de filtrarse cambia tests de S025/S027: se ajustan en T7 con el cambio explícito.
- Abrir la vista del proyecto con una hoja abierta desde «Datos del proyecto» (`openPanel`) puede reabrirla al girar: se consume una sola vez y se prueba (T15).
- El rendimiento al cambiar de pestaña con 50 000 puntos: cada pestaña es un `LazyColumn` y la otra no se compone (T11).

## Cambios al implementar
Cambios de la spec aprobados durante la implementación: ninguno nuevo; la corrección de S025/RF-21 (la búsqueda ya no filtra las líneas) estaba en D3 y queda escrita en «Cambios en documentos anteriores» de la spec.

Lo que se decidió o se encontró al construir y no estaba en el plan:
- **T5 y T6 juntas.** Quitar el menú plano rompía 19 ficheros de tests que lo abrían, así que se hicieron en un solo commit con el `check` en verde. Los tests pasan a `chooseOption(ProjectOption.X)`, que abre el menú de la opción y la elige.
- **T8 a T10 juntas.** Las dos pestañas y sus contenidos tocan la misma función de la pantalla.
- **Sin `lineTotal`.** Con las líneas sin filtrar, `lines.size` ya es el total (T7); no hizo falta el campo.
- **Filtros por código.** Eran un `FlowRow` que se partía en varias filas; pasan a una `Row` con desplazamiento lateral (RF-14).
- **Fila de una línea (RF-15).** El nombre y el código (punto de color y «Código: X») van en la primera fila, y el tipo, los vértices y el estado en la segunda.
- **Descripciones de «Herramientas» (RF-5).** Son una frase corta; en el menú anclado a la derecha del emulador (1080×2400) ocupan dos líneas de pantalla. Se tomó «de una línea» como «una sola frase».
- **«Atrás» y pulsar fuera (RF-9).** Robolectric no entrega esos eventos a la ventana emergente: el test comprueba que la barra cierra el menú al descartarlo y el cierre en sí es el estándar de Material (`onDismissRequest`).
- **Lo activo.** `ActiveSources` (un puerto con la implementación `RepositoryActiveSources`, `ProjectInfoModule` en Hilt) saca el estado de los repositorios de localización, base shift, superficies, ejes y capas; el terreno sale del propio proyecto. La función pura `ActiveItems.of` fija el orden y qué cuenta como activo (las capas solo si el proyecto añadió alguna).
- **Navegación (RF-20).** Terreno: `ProjectFormDestination(showTerrain)`, que lleva el formulario hasta la sección de terreno una sola vez (`BringIntoViewRequester`). Superficie, eje y capas: `ProjectViewDestination(openPanel)`, que abre la hoja una sola vez por entrada. No hizo falta ninguna pantalla nueva.
- **Cadenas.** Se quitaron `localization_menu_item` y `objects_lines_title`, que dejaron de usarse (lint de recursos sin usar); la descripción de TalkBack de cada fila de lo activo va en un recurso, porque `checkHardcodedUiText` prohíbe texto escrito en el código.
- **Tests de tamaño.** `ObjectListScreenTest`, `ImportedPointsTest` e `ImportedPointsTest` (en inglés) pasan a `h800dp`: la barra de grupos y las pestañas ocupan sitio en la pantalla pequeña de Robolectric y empujaban los puntos fuera de la vista.

## Validación
- `rf_report`: 22 RF, 22 con test, 0 pendientes. `./gradlew check`, `uv run --project tools pytest tools`, `forbidden_files` y `assembleDebug` en verde (24/11/2026). Con el emulador abierto fallan los tests de rendimiento y de ViewModel de otras specs; con el emulador cerrado pasan.
- Matriz de tamaño y accesibilidad (`OptionsAccessibilityMatrixTest`): la lista de Objetos, los menús de Importar y de Herramientas y la sección de lo activo, a 360 y 411 dp y con la letra al 100 % y al 130 %: controles de 56 dp, dentro del ancho, sin texto cortado y con nombre para TalkBack.
- Rendimiento (`ObjectTabsTest`): cambiar de pestaña con 50 000 puntos y 5 000 líneas, por debajo de 300 ms en Robolectric (un indicador, no los 16 ms de un móvil real).
- Demo en el emulador (24/11/2026, 1080×2400): la lista de Objetos con la barra de grupos y las pestañas «Puntos (14)» y «Líneas y polígonos (4)», los menús de Importar (8 filas, enteras) y de Herramientas (con descripciones), la pestaña de líneas con dos filas de texto por línea y «Datos del proyecto» de un proyecto sin nada activo (sin la sección). Las capturas están en el directorio temporal de la sesión (no se versionan).
- **No visto en el emulador:** la sección «Activo en este proyecto» con algo activo (el proyecto de la demo no tenía terreno, localización ni capas); la cubren `ActiveSectionTest`, `ProjectInfoActiveScreenTest` y `ActiveItemsNavigationTest`. Queda para que lo mires en un proyecto real.
