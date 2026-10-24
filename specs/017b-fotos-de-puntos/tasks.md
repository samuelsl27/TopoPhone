# Tareas — Spec 017b

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `tools/trazabilidad`: `rf_report` entiende las specs con letra (`017b`), que son distintas de las de su número. (RF de todas las specs con letra; S001/RF-26)
      Hecho cuando: los tests de `tools` en verde y `rf_report` lista S017b con sus RF pendientes.
- [ ] T2. `core:model`: `PhotoPolicy` (10 fotos, 2.048 px, tamaño reducido, cuántas caben), `PointPhoto`, `PhotoOrigin`, `PhotoNames` y `PhotoStats`. (RF-13, RF-14, RF-18)
      Hecho cuando: `PhotoPolicyTest` y `PhotoNamesTest` en verde, con la foto apaisada, la vertical, la menor que el máximo, la selección que no cabe y dos fotos en el mismo segundo.
- [ ] T3. `core:model`: `PhotoDestination` con las situaciones de A2. (RF-2, RF-5, RF-6, RF-7, RF-8)
      Hecho cuando: `PhotoDestinationTest` en verde, incluido el modo automático con otro punto en medida y el último punto que desaparece.
- [ ] T4. `core:model`: los sucesos de foto añadida y borrada en el historial y `PointSummary.photoCount`. (RF-23, RF-26)
      Hecho cuando: `PointHistoryTest` en verde con los sucesos entre las versiones del punto, ordenados del más reciente al más antiguo y sin marcar el punto como editado.
- [ ] T5. `data`: tablas `point_photos` y `photo_events`, sus DAO, `AutoMigration(9, 10)` con su esquema y `DatabaseSnapshot.TABLES` con ellas. (RF-20, RF-23, RF-28)
      Hecho cuando: `TopoPhoneDatabaseTest` 9 → 10 en verde (los puntos conservan sus datos y las tablas nuevas quedan vacías), `DatabaseSnapshotTest` copia las filas y las cascadas de las dos tablas funcionan.
- [ ] T6. `data`: `PhotoStore`: reducir y guardar la foto con sus metadatos en `photos/pending/`, con los fallos sin dejar nada. (RF-15, RF-18, RF-19, RF-20)
      Hecho cuando: `PhotoStoreTest` en verde, con gráficos `NATIVE` y JPEG generados en el test: 12 MP baja a 2.048 px, una menor se copia sin recomprimir, la fecha, la orientación y la ubicación se conservan, la foto original no cambia y un fichero ilegible o un fallo de escritura no dejan nada.
- [ ] T7. `data`: `PointRepository` con fotos: guardar el punto con sus fotos en espera en una transacción, `addPhotos`, `deletePhoto`, `photos(pointId)` y los sucesos. (RF-2, RF-12, RF-14, RF-20, RF-21, RF-22, RF-23)
      Hecho cuando: `PointRepositoryPhotosTest` en verde: un fallo a medias no deja ni filas ni ficheros, el límite de 10 se comprueba en la transacción, la misma foto añadida dos veces cuenta como dos, borrar deja su suceso y añadir no marca el punto como editado.
- [ ] T8. `data`: el barrido de ficheros sin fila, la papelera de puntos y de proyectos con sus fotos, duplicar sin fotos, `photoCount` en los resúmenes y `PhotoStats` del proyecto. (RF-24, RF-25, RF-26, RF-27, RF-34)
      Hecho cuando: `PhotoCleanupTest` en verde: borrar un punto de la papelera, vaciarla, y borrar un proyecto dejan sin ficheros las fotos; restaurar las devuelve; el barrido de arranque borra un fichero huérfano y lo vacío de `pending/`.
- [ ] T9. `core:formats`: `BackupArchive` con las entradas `photos/<id>.jpg`, sus sumas y `externalPhotos` en el manifiesto. (RF-28, RF-29, RF-30)
      Hecho cuando: `BackupArchiveTest` en verde: ida y vuelta con fotos, un nombre con `..` o absoluto, una suma cambiada, una foto de más y un manifiesto con `externalPhotos` o sin él; los ZIP de antes se siguen leyendo.
- [ ] T10. `data`: la copia completa con fotos, la restauración con el intercambio de directorios y vuelta atrás, y deshacer. (RF-28)
      Hecho cuando: `BackupRepositoryPhotosTest` en verde: restaurar devuelve fotos y filas, un fallo en el intercambio deja las fotos como estaban, deshacer las recupera y la copia del estado anterior lleva las fotos.
- [ ] T11. `data`: el paquete con las fotos de los puntos y de su papelera, y su importación con ids nuevos o sustituyendo un proyecto. (RF-29)
      Hecho cuando: los tests de paquete de `BackupRepositoryPhotosTest` en verde: importado en otro móvil, como nuevo, sustituyendo al del móvil y con ids de foto repetidos, y las fotos abren.
- [ ] T12. `data`: `BackupFolder` con la subcarpeta de fotos (escribir, listar, borrar y abrir) y `ExternalPhotos`. (RF-30, RF-31, RF-32)
      Hecho cuando: los tests de `BackupFolder` en verde con el proveedor falso y los fallos del proveedor tratados como "no se ha podido".
- [ ] T13. `app`: `AutoBackupScheduler` copia las fotos nuevas una sola vez y aplica la retención. (RF-30, RF-31)
      Hecho cuando: `AutoBackupPhotosTest` en verde: la segunda copia no repite fotos, la retención conserva las que nombra algún ZIP y borra las demás, un fallo al copiar una foto hace fallar la copia y no se toca ningún otro fichero de la carpeta.
- [ ] T14. `data` y `app`: restaurar una copia automática con las fotos de la subcarpeta y el aviso de las que faltan. (RF-32, RF-33)
      Hecho cuando: `AutoBackupPhotosTest` y `BackupsScreenTest` en verde: se recuperan las fotos de la carpeta, una foto que falta o con otra suma se cuenta y se dice cuántas, y sin carpeta todas cuentan como no recuperadas.
- [ ] T15. `app`: `CollectorViewModel` con las fotos: destino, fotos en espera, añadir de cámara y de galería, descartar al cancelar o fallar, guardar con el punto y cierre de la app con la cámara abierta. (RF-1 a RF-8, RF-13 a RF-16)
      Hecho cuando: `CollectorViewModelPhotosTest` en verde con la `ReceiverSession` real: foto durante la medida y al guardar, cancelar con confirmación, medida fallida y fuera de tolerancia, modo automático, sin punto, límite, y el `SavedStateHandle`.
- [ ] T16. `app`: el panel del recolector con "Foto", "Galería", el destino, el recuento y el aviso al cancelar, con textos en es y en. (RF-1, RF-3, RF-7, RF-8)
      Hecho cuando: `CollectorPanelPhotosTest` en verde, con zonas táctiles de 56 dp y las etiquetas de TalkBack.
- [ ] T17. `app`: carga de miniaturas con orientación y caché, y la sección "Fotos" de la ficha con la foto que no se puede abrir. (RF-9, RF-17)
      Hecho cuando: `PointPhotosScreenTest` en verde con las miniaturas en orden con su número, la de un fichero ilegible sustituida por su aviso y el resto intactas.
- [ ] T18. `app`: el visor a pantalla completa con zoom, paso entre fotos y fecha, origen y tamaño. (RF-10, RF-11)
      Hecho cuando: los tests del visor de `PointPhotosScreenTest` en verde, con la foto con otra orientación.
- [ ] T19. `app`: añadir fotos desde la ficha: cámara, galería con varias, el límite y los errores. (RF-12, RF-13, RF-14, RF-15, RF-16)
      Hecho cuando: los tests de añadir de `PointPhotosScreenTest` en verde: en un punto medido y en uno manual, con 9 fotos y 3 elegidas, con 10 fotos, con un fallo de espacio y cerrando el selector sin elegir.
- [ ] T20. `app`: borrar una foto con confirmación y el historial con los sucesos de foto. (RF-22, RF-23)
      Hecho cuando: los tests de borrar y de historial de `PointPhotosScreenTest` en verde, con el punto sin marcar como editado.
- [ ] T21. `app`: la lista de objetos con la marca de fotos y los datos del proyecto con el recuento y el espacio. (RF-26, RF-27)
      Hecho cuando: `ObjectListPhotosTest` y `ProjectDataPhotosTest` en verde, con el icono y el texto y en las unidades de RNF-T9.
- [ ] T22. Extremo a extremo y rendimiento: `PhotosEndToEndTest` y `PhotoPerformanceTest`. (RNF, RF-2, RF-5, RF-22, RF-24, RF-28)
      Hecho cuando: ambos en verde: una medida con foto, guardar, foto tras guardar, ficha, papelera, restaurar, copia completa, restaurar y deshacer con las fotos presentes; reducir 12 MP en menos de 2 s y la ficha con 10 fotos en menos de 1 s.
- [ ] T23. Validación: recorrer la spec RF por RF, `rf_report`, demo en el emulador según los criterios de finalización. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona. Las pruebas de campo quedan para el usuario.
