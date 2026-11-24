# Tareas — Spec 059

> Cada tarea dura 30 min como máximo, sigue el orden de dependencias y se implementa con los tests primero. Cada tarea cierra con `./gradlew check` en verde (con el emulador cerrado y, si falla un test ajeno intermitente, tras `./gradlew --stop`), la casilla marcada y un commit `S059 Tn: …`.

## Menús de grupo
- [x] T1. `ProjectOptions`: la lista declarativa de opciones por grupo, en el orden de RF-2 a RF-6, con su verbo para TalkBack. (RF-2, RF-3, RF-4, RF-6, RF-7, RF-8)
      Hecho cuando: `ProjectOptionsTest` comprueba el orden de cada grupo, que ningún menú pasa de 8 filas, que no hay ids repetidos y que están las 21 opciones de hoy, cada una en un solo grupo.
- [x] T2. Iconos y textos: los vectores propios y las cadenas en español e inglés (nombres de fila, descripciones de «Herramientas» y verbos de TalkBack). (RF-5, RF-8, RNF-T7)
      Hecho cuando: un test comprueba que cada opción de `ProjectOptions` tiene icono, texto y etiqueta en los dos idiomas, y que las filas de «Importar» y «Exportar» no llevan descripción.
- [x] T3. `GroupBar`: tres botones de igual ancho con icono y texto, 56 dp, y el texto en dos líneas con la letra al 130 %. (RF-1)
      Hecho cuando: `GroupBarTest` comprueba a 360 y 411 dp, con letra al 100 % y al 130 %, que los tres botones se ven completos, miden 56 dp y no cortan el texto.
- [ ] T4. `GroupMenu`: el menú de cada botón con icono, nombre, descripción solo en «Herramientas» y etiqueta de TalkBack; cierra al pulsar fuera y con «Atrás». (RF-5, RF-8, RF-9, RF-10)
      Hecho cuando: `GroupMenuTest` abre cada menú a 360×640 con letra al 100 % y comprueba que se ve entero sin desplazar, que cada fila lleva su etiqueta («Importar DXF») y que cerrar no llama a nada.
- [ ] T5. El «⋮» de Objetos con solo «Informe de replanteo» y «Papelera de puntos». (RF-6, RF-7, RF-10b)
      Hecho cuando: `ObjectsMenuTest` comprueba las dos filas, sus acciones y que una opción que hoy avisa al abrirse sigue visible y con el mismo comportamiento.
- [ ] T6. Integración en `ObjectListScreen`: la barra de grupos bajo la barra superior, los menús conectados a las acciones de siempre y migración de los tests que abrían el menú plano. (RF-1, RF-2, RF-3, RF-4, RF-7, RF-10b)
      Hecho cuando: `ObjectListMenusTest` pulsa cada una de las filas de los tres menús y comprueba que llega a la acción de su spec de origen; los tests antiguos del menú pasan migrados.

## Pestañas de Objetos
- [ ] T7. `ObjectListViewModel`: las líneas sin filtro de búsqueda ni de códigos y el total de líneas (`lineTotal`); se ajustan los tests de S025/S027 afectados. (RF-11, RF-14, RF-15)
      Hecho cuando: `ObjectListViewModelTabsTest` comprueba que buscar o filtrar por código no cambia las líneas, que `lineTotal` cuenta todas y que los puntos se filtran igual que antes.
- [ ] T8. `ObjectTabs`: «Puntos (N)» y «Líneas y polígonos (M)», 56 dp, que abre en «Puntos» aunque esté vacía, con la pestaña guardada y los menús cerrados al abrir o cambiar de proyecto. (RF-11, RF-12, RF-13)
      Hecho cuando: `ObjectTabsTest` comprueba los contadores, que abre en «Puntos» con un proyecto sin puntos pero con líneas, y que cambiar de proyecto vuelve a «Puntos».
- [ ] T9. La pestaña «Puntos»: buscador, filtros por código en una fila que se desplaza, aviso del jalón y barra de selección con todas sus acciones. (RF-14, RF-17)
      Hecho cuando: `ObjectPointsTabTest` comprueba que buscador y filtros solo están en «Puntos», que la selección ofrece ocultar, mostrar, borrar, exportar la selección, corregir el jalón y cambiar el código, y que los filtros no hacen crecer la pantalla.
- [ ] T10. La pestaña «Líneas y polígonos»: cada línea con dos filas de texto, el texto de que no hay ninguna y las acciones por fila. (RF-15, RF-16, RF-17)
      Hecho cuando: `ObjectLinesTabTest` comprueba las dos filas (nombre; tipo, vértices y código), el texto sin líneas, y mostrar u ocultar, abrir, replantear y borrar.
- [ ] T11. Pestaña elegida al girar y al restaurar el proceso, y rendimiento de cambiar de pestaña. (RF-13, RNF-T13)
      Hecho cuando: `ObjectTabsRestoreTest` recrea la actividad y el estado guardado y conserva la pestaña, y `ObjectTabsPerformanceTest` mide por debajo de 16 ms el cambio con 50 000 puntos y 5 000 líneas.

## Lo activo en «Datos del proyecto»
- [ ] T12. `ActiveItems` (puro): el orden terreno, localización, base shift, superficie, eje y capas, el texto de estado de cada uno, las capas solo si hay propias y la lista vacía. (RF-18, RF-19, RF-21)
      Hecho cuando: `ActiveItemsTest` cubre cada estado (factor, RMS o «Activa», nombre del punto conocido, nombres, «2 de 3»), el orden y que sin nada activo, o solo con el mapa base, la lista queda vacía.
- [ ] T13. `ProjectInfoViewModel` con los repositorios de localización, base shift, superficies, ejes y capas, y el factor del terreno. (RF-18, RF-19, RF-22)
      Hecho cuando: `ProjectInfoViewModelActiveTest` comprueba, con repositorios falsos, que la lista cambia al activar o desactivar cada elemento sin volver a abrir.
- [ ] T14. `ActiveSection`: una fila de 56 dp por elemento con su nombre y su estado en texto, puntos suspensivos con el texto entero para TalkBack y filas solo informativas si no tienen destino. (RF-19, RF-19b, RF-20b, RNF-T8)
      Hecho cuando: `ActiveSectionTest` comprueba nombres y estados, el texto largo cortado con su descripción entera, que una fila sin destino no es un botón y que la sección no se pinta vacía.
- [ ] T15. Navegación de las filas: localización y base shift a sus pantallas, el terreno al formulario con su sección a la vista y la superficie, el eje y las capas a la vista del proyecto con su hoja abierta una sola vez. (RF-20)
      Hecho cuando: `ActiveItemsNavigationTest` pulsa cada fila y comprueba el destino, y que la hoja abierta por `openPanel` no se reabre al girar ni al volver.
- [ ] T16. La sección en `ProjectInfoScreen` y su actualización con la pantalla abierta. (RF-18, RF-21, RF-22)
      Hecho cuando: `ProjectInfoActiveScreenTest` muestra la sección con un terreno y una localización activos, no la muestra sin nada activo y la actualiza al activar un elemento con la pantalla abierta.

## Cierre
- [ ] T17. Matriz de tamaño y accesibilidad de menús, pestañas y sección: 360 y 411 dp, letra al 100 % y al 130 %. (RNF-T8, RNF específicos)
      Hecho cuando: `OptionsAccessibilityMatrixTest` comprueba en las cuatro combinaciones zonas de 56 dp, ningún texto cortado ni fuera del ancho, nombre para TalkBack y que con la letra al 130 % los menús se pueden desplazar.
- [ ] T18. Documentos: RNF-T15 en `docs/rnf-transversales.md`, «Barra de grupos» y «Menú de grupo» en `docs/glosario.md`, la hoja de ruta con la spec implementada y la corrección de S025/RF-21 en «Cambios en documentos anteriores» de la spec. (RNF-T15)
      Hecho cuando: los tres documentos están actualizados y `uv run --project tools pytest tools` y `forbidden_files` pasan.
- [ ] T19. Demo en el emulador con capturas de antes y después: la lista de Objetos, cada menú de grupo y «Datos del proyecto» con terreno o localización activos y sin nada activo.
      Hecho cuando: están las capturas en el directorio temporal y lo visto cuadra con los RF, y se anota en `plan.md` lo que no.
- [ ] T20. Validación final: `rf_report` sin RF huérfanos, `./gradlew check`, `uv run --project tools pytest tools`, `forbidden_files` y `assembleDebug` en verde; `plan.md` con «Cambios al implementar» y «Validación», y la nota de memoria. (todos los RF)
      Hecho cuando: todo lo anterior pasa y la spec queda con el estado «implementada».
