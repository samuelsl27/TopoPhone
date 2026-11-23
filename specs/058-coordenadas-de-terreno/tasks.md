# Tareas — Spec 058

> Cada tarea dura 30 min como máximo, sigue el orden de dependencias y se implementa con los tests primero. Cada tarea cierra con `./gradlew check` en verde (se repite con `--no-parallel` si falla un test ajeno intermitente), la casilla marcada y un commit `S058 Tn: …` en `main`, con la línea Co-Authored-By.

## Vectores y núcleo (`tools/geodesia`, `core:model`, `core:survey`)
- [x] T1. `tools/geodesia/terreno_vectores.py`: la cuadrícula, el origen, el factor combinado de pyproj y las coordenadas de terreno de unos puntos en UTM 30N y en Lambert-93, en `fixtures/geodesia/terreno.csv`. (RF-3, RF-7)
      Hecho cuando: `test_terreno.py` comprueba las filas, que se regenera idéntico y que `pytest tools` pasa.
- [x] T2. `TerrainPlane` y `TerrainFrame` (paso cuadrícula ↔ terreno), con su texto estable. (RF-6, RF-7, RF-8)
      Hecho cuando: `TerrainFrameTest` comprueba que el origen no se mueve, `k = 1` es la identidad, las distancias × `k`, las áreas × `k²`, la ida y vuelta ≤ 0,1 mm y 10.000 puntos en menos de 100 ms.
- [x] T3. `TerrainVectorsTest`: el marco contra `terreno.csv`, con el origen dado por su posición del suelo. (RF-7, constitución nº 2)
      Hecho cuando: las coordenadas de terreno coinciden con pyproj a ≤ 1 mm y la ida y vuelta cierra a ≤ 0,1 mm.
- [x] T4. `TerrainProposal` y `TerrainPlaneValidator`. (RF-2, RF-3, RF-4, RF-5)
      Hecho cuando: `TerrainProposalTest` da el factor combinado de pyproj a 1e-9 con y sin altura, y `TerrainPlaneValidatorTest` cubre SC geográfico, factor fuera de 0,9–1,1, no finito, origen fuera del ámbito y plano válido.
- [x] T5. `TerrainView`: coordenadas, distancias, deltas y áreas en terreno, la inversa y la etiqueta; identidad en cuadrícula. (RF-9, RF-10, RF-11)
      Hecho cuando: `TerrainViewTest` comprueba el paso de coordenadas, distancias, deltas y áreas, que las alturas no cambian y que con cuadrícula todo es la identidad.

## Datos (`data`)
- [x] T6. Room v39: columnas del plano en `projects` y del plano anterior y nuevo en `project_crs_history`, `Project.terrain` y `ProjectDraft.terrain`, y su migración automática 38→39. (RF-1, RF-6, RF-18, RF-19)
      Hecho cuando: `Migration38To39Test` conserva proyectos, puntos e historial, y `ProjectTerrainRepositoryTest` guarda, lee y borra el plano sin redondear y lo conserva al duplicar y restaurar.
- [x] T7. El historial con plano: `ProjectCrsChange.fromTerrain/toTerrain`, `apply(…, terrain)` y `undo` del plano, sin tocar los puntos, y el origen que sobrevive a un cambio de SC. (RF-6, RF-19, RF-20)
      Hecho cuando: `TerrainHistoryTest` cambia de cuadrícula a terreno, cambia el factor, deshace, cambia el SC con el plano activo (el origen sigue en el mismo punto) y comprueba que ninguna columna de los puntos cambia.
- [x] T8. Copias y paquetes con plano de terreno. (RF-18)
      Hecho cuando: `TerrainBackupTest` hace copia y restauración, y paquete e importación, con el plano.

## Formulario (`app`)
- [x] T9. El formulario del proyecto: cuadrícula o terreno, el origen (el centro por defecto) y el factor con su propuesta, y los errores marcados; sin terreno con un SC geográfico. (RF-1, RF-2, RF-3, RF-4, RF-5)
      Hecho cuando: `ProjectTerrainFormTest` elige terreno, ve el factor propuesto y lo cambia, ve cada error y no encuentra la opción con un SC geográfico.
- [x] T10. Guardar el cambio de plano desde el formulario por el historial, y las cadenas de textos. (RF-19, RF-20)
      Hecho cuando: `ProjectTerrainFormTest` guarda el cambio, queda en el historial, los puntos no cambian y el historial de S035 lo muestra.

## Pantallas (`app`)
- [x] T11. Panel de captura y posición del receptor en terreno, con «Terreno» junto a las coordenadas. (RF-9)
      Hecho cuando: `TerrainScreensTest` comprueba que `CoordinatesCard` muestra el Este y el Norte de terreno y su etiqueta, y los de cuadrícula sin plano.
- [x] T12. Mapa (coordenadas del cursor y de la pulsación larga) y distancias del mapa. (RF-9)
      Hecho cuando: `TerrainScreensTest` comprueba las coordenadas y las distancias del mapa en terreno.
- [x] T13. Lista y ficha de los puntos, vista del proyecto y base del punto. (RF-9, RF-11)
      Hecho cuando: `TerrainScreensTest` comprueba la ficha de un punto con Este y Norte de terreno, sin cambiar la altura.
- [x] T14. Punto manual y edición de un punto: lo que se escribe es terreno. (RF-10)
      Hecho cuando: `TerrainInputsTest` escribe unas coordenadas de terreno y el punto queda con las de cuadrícula que le corresponden y la ida y vuelta cierra.
- [x] T15. Punto central del proyecto: entrada y visualización en terreno. (RF-9, RF-10)
      Hecho cuando: `TerrainInputsTest` fija el punto central escribiendo terreno y lo ve en terreno.
- [x] T16. Replanteo de puntos y de líneas: deltas y distancias en terreno. (RF-9)
      Hecho cuando: `TerrainScreensTest` comprueba que la distancia y los deltas del replanteo de un punto están × `k` y el acimut no cambia.
- [x] T17. Replanteo de ejes, dibujos, superficies, pendientes, secciones y tomas lineales, y desplazamiento de base. (RF-9)
      Hecho cuando: `TerrainScreensTest` comprueba las distancias y las coordenadas de cada uno en terreno.
- [x] T18. COGO: entradas y resultados en terreno, con el modo de distancias en cuadrícula. (RF-9, RF-10)
      Hecho cuando: `TerrainInputsTest` calcula una inversa y una intersección en terreno y los puntos guardados quedan en cuadrícula.
- [x] T19. Información geométrica: distancias, longitudes y áreas en terreno. (RF-9)
      Hecho cuando: `TerrainScreensTest` comprueba una distancia × `k`, un área × `k²` y un ángulo sin cambio.

## Ficheros (`core:formats`, `data`, `app`)
- [x] T20. CSV: exportar en terreno con su primera línea de comentario. (RF-13)
      Hecho cuando: `TerrainCsvTest` exporta con y sin plano, la cabecera lleva el origen y el factor y los nombres de las columnas no cambian.
- [x] T21. CSV y TXT: importar en terreno. (RF-12)
      Hecho cuando: `TerrainCsvTest` importa un CSV de terreno y los puntos quedan en cuadrícula, y el CSV exportado se vuelve a importar en el mismo proyecto con los mismos puntos.
- [x] T22. DXF: exportar en terreno con su comentario de cabecera. (RF-14)
      Hecho cuando: `TerrainDxfTest` exporta un dibujo con puntos y líneas en terreno y el comentario lleva el origen y el factor.
- [ ] T23. Importar DXF, superficies, ejes, rásteres y las demás capas: «las coordenadas del proyecto» son de terreno y el SC del fichero se respeta. (RF-17)
      Hecho cuando: `TerrainImportTest` importa un DXF «del proyecto» en terreno y queda en cuadrícula, e importa uno con SC declarado sin cambio.
- [ ] T24. Shapefile y GeoPackage siempre en cuadrícula con su aviso en pantalla, y KML sin cambios. (RF-15, RF-16)
      Hecho cuando: `TerrainExportNoticeTest` exporta con el proyecto en terreno, los ficheros llevan la cuadrícula y las pantallas dicen que va en cuadrícula, y KML sale igual.

## Historial y cierre
- [ ] T25. El historial del proyecto muestra los cambios de plano. (RF-19)
      Hecho cuando: `TerrainHistoryScreenTest` muestra un cambio a terreno y uno de factor, y «Volver al SC anterior» lo deshace.
- [ ] T26. Validación final: `rf_report` con los RF cubiertos, `check`, `pytest tools`, `forbidden_files` y `assembleDebug` en verde; `plan.md` con «Cambios al implementar» y «Validación»; hoja de ruta con S058 implementada (Room v39); demo del emulador (elegir terreno, ver, exportar CSV y DXF, deshacer).
      Hecho cuando: el informe de trazabilidad no tiene RF huérfanos y la demo está hecha.
