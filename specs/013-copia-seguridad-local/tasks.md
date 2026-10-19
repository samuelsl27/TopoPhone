# Tareas — Spec 013

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `core:formats`: `BackupManifest` y `BackupArchive` (escribir y leer el ZIP, sumas SHA-256, `NotTopoPhone`, `WrongKind`, `Damaged`, `Newer`), con `kotlinx-serialization-json`. (RF-12, RF-13)
      Hecho cuando: `BackupArchiveTest` en verde con ida y vuelta, entradas que faltan, sumas cambiadas y versiones más nuevas.
- [x] T2. `core:formats`: `BackupFileNames` (con las reglas de `ExportFileName` compartidas en el módulo) y `AutoBackupPolicy` (cuándo toca y qué copias sobran). (RF-4, RF-7, RF-18, RF-19)
      Hecho cuando: `BackupFileNamesTest` y `AutoBackupPolicyTest` en verde.
- [x] T3. `data`: `DatabaseSnapshot` (conexión de solo lectura, copia por tablas entera o de un proyecto, marca de contraseña). (RF-2, RF-3, RF-24, RNF)
      Hecho cuando: `DatabaseSnapshotTest` en verde con los `double` iguales bit a bit y un punto guardado mientras la instantánea está abierta.
- [x] T4. `data`: `SettingsArchive` y `AppLanguageStore` (claves que viajan, claves del móvil intactas). (RF-2, RF-3, RF-8)
      Hecho cuando: `SettingsArchiveTest` en verde.
- [x] T5. `data`: `BackupRepository.writeBackup` y `read` (A2, A3: ZIP completo, validación y migración de `data.db`). (RF-1, RF-5, RF-6, RF-12, RF-13, RF-14)
      Hecho cuando: los tests de escribir y leer en verde, y `BackupMigrationTest` restaura copias de las versiones 1 a 5 del esquema y rechaza la 7.
- [x] T6. `data`: `restore` (copia previa privada, sustitución en una transacción, contraseñas conservadas, ajustes, vuelta atrás si falla). (RF-7, RF-8, RF-9, RF-10, RF-15)
      Hecho cuando: `BackupRestoreTest` en verde con los fallos en los pasos 5 y 6 y la contraseña pedida con `Undecryptable`.
- [x] T7. `data`: `undoRestore` y `undoAvailable`. (RF-11)
      Hecho cuando: los tests de deshacer en verde (datos y contraseñas como antes; la opción desaparece).
- [x] T8. `data`: `writePackage` e `importPackage` con sus ids y como nuevo ("(importado)", ids nuevos, id en la papelera). (RF-24, RF-25, RF-28)
      Hecho cuando: los tests de paquete (exportar, importar, como nuevo, nombre ocupado) en verde.
- [x] T9. `data`: sustituir al importar un paquete (el antiguo a la papelera con ids nuevos, el nuevo en su carpeta) y `NeedsChoice`. (RF-26, RF-27)
      Hecho cuando: los tests de sustituir en verde, y el mismo paquete importado otra vez se reconoce.
- [x] T10. `data`: `AutoBackupPreferences` (DataStore "backup") y `BackupChanges` (cambios de Room y de los DataStores, sin `map_viewports`). (RF-18, RF-20, RF-21, RF-23)
      Hecho cuando: `AutoBackupPreferencesTest` y `BackupChangesTest` en verde.
- [x] T11. `data`: `BackupFolder` con `DocumentsContract` (crear y escribir, listar, borrar, permiso persistido) y borrado del fichero a medias. (RF-5, RF-17, RF-19)
      Hecho cuando: los tests con un proveedor de documentos de prueba en verde.
- [x] T12. `app`: `AutoBackupScheduler` y el ámbito de la app (cada 5 min, al pasar a segundo plano, primera copia al elegir la carpeta, retención, fallo y reintento, un cerrojo con la restauración). (RF-18, RF-19, RF-21)
      Hecho cuando: `AutoBackupSchedulerTest` en verde con tiempo virtual.
- [x] T13. `app`: `MeasurementInProgress` actualizado por `CollectorViewModel` (medida y punto pendiente de confirmar). (RF-16)
      Hecho cuando: los tests del recolector y del singleton en verde.
- [x] T14. `app`: `BackupsViewModel` (crear, leer, confirmar, restaurar, deshacer, errores, medida en curso, carpeta). (RF-1, RF-5, RF-6, RF-11, RF-12, RF-13, RF-16, RF-17, RF-20, RF-21)
      Hecho cuando: `BackupsViewModelTest` en verde.
- [x] T15. `app`: pantalla "Copias de seguridad" y su acceso desde Ajustes, con el selector de ficheros y el de carpetas. (RF-1, RF-6, RF-11, RF-17, RF-20, RF-21)
      Hecho cuando: `BackupsScreenTest` en verde.
- [x] T16. `app`: tras restaurar o deshacer, volver a la lista de proyectos y aplicar el idioma; comprobar que la conexión NTRIP se cierra o se reconecta. (RF-8, S007/RF-8, RF-9)
      Hecho cuando: los tests de navegación y del controlador NTRIP tras restaurar en verde.
- [x] T17. `app`: aviso sin carpeta y alerta de fallo en la lista de proyectos. (RF-21, RF-22, RF-23)
      Hecho cuando: `ProjectListBackupNoticeTest` en verde.
- [x] T18. `app`: "Exportar paquete" en el menú del proyecto, con guardar o compartir. (RF-4, RF-5, RF-24)
      Hecho cuando: los tests del menú y del diálogo en verde.
- [ ] T19. `app`: "Importar paquete" en el menú de la lista, con los errores y la pregunta de sustituir o importar como nuevo. (RF-12, RF-13, RF-16, RF-25, RF-26, RF-27, RF-28)
      Hecho cuando: `PackageScreenTest` en verde.
- [ ] T20. Rendimiento: 10 000 puntos se copian en menos de 5 s y se restauran en menos de 10 s. (RNF)
      Hecho cuando: el test de rendimiento en verde.
- [ ] T21. Validación: recorrer la spec RF por RF, `rf_report`, demo en el emulador según los criterios de finalización. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona.
