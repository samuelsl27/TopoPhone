# Tareas — Spec 023

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `core:model`: `CodeGeometry`, `AttributeType`, `CodeAttribute`, `SurveyCode`, `CodeLibrary`, `LibraryChoice`, `CodePalette` y los términos en `docs/glosario.md`. (RF-5, RF-8, RF-9)
      Hecho cuando: los tests de `core:model` en verde.
- [x] T2. `core:model`: `CodeKey`, `CodeValidator` (código y atributos) y `LibraryNames`. (RF-2, RF-6, RF-7, RF-9)
      Hecho cuando: `CodeKeyTest` y `CodeValidatorTest` en verde.
- [x] T3. `core:model`: `CodeSearch` y orden de la lista. (RF-11)
      Hecho cuando: `CodeSearchTest` en verde, con 2.000 códigos en menos de 1 s.
- [x] T4. `core:model`: `CodeMerge` (nuevos, duplicados del fichero y de la biblioteca; omitir y reemplazar). (RF-16)
      Hecho cuando: `CodeMergeTest` en verde.
- [x] T5. `core:formats`: `CodeLibraryCsvWriter` con el escape de opciones. (RF-17, RF-18)
      Hecho cuando: `CodeLibraryCsvWriterTest` en verde.
- [x] T6. `core:formats`: `CodeLibraryCsvReader` con `fixtures/codigos/` (Windows-1252, coma decimal, sin `Code`, filas inválidas). (RF-12, RF-13, RF-15)
      Hecho cuando: `CodeLibraryCsvReaderTest` en verde.
- [x] T7. `core:formats`: ida y vuelta y rendimiento de 2.000 códigos. (RF-18, RNF)
      Hecho cuando: `CodeLibraryRoundTripTest` en verde, con la lectura en menos de 3 s.
- [x] T8. `data`: Room v28 (cuatro tablas), `AutoMigration(27, 28)` y el esquema 28. (RNF-T6)
      Hecho cuando: `TopoPhoneDatabaseTest` 27 a 28 en verde.
- [x] T9. `data`: `CodeLibraryRepository`, bibliotecas (crear, renombrar, duplicar, borrar, por defecto de solo lectura) y `DefaultLibrarySource`. (RF-1 a RF-4)
      Hecho cuando: `CodeLibraryRepositoryTest` en verde.
- [x] T10. `data`: códigos y atributos (alta, edición, borrado, orden, rechazos). (RF-5 a RF-7, RF-9, RF-10)
      Hecho cuando: `CodeRepositoryTest` en verde.
- [ ] T11. `data`: importación atómica a biblioteca nueva o existente. (RF-14 a RF-16)
      Hecho cuando: `CodeImportRepositoryTest` en verde, con el caso del fallo a medias.
- [ ] T12. `data` y `core:model`: `ProjectDraft.libraryChoice`, vincular, cambiar y quitar en la transacción del proyecto, duplicar proyecto y `ProjectDefaults.codeLibraryId`. (RF-4, RF-19 a RF-21)
      Hecho cuando: `CodeLibraryLinkTest` en verde.
- [ ] T13. `data`: copias de seguridad y paquetes con las bibliotecas y la copia de cada proyecto. (RF-22)
      Hecho cuando: `CodeLibraryBackupTest` en verde.
- [ ] T14. `app`: `DefaultCodeLibraries` (es y en) y su enlace con Hilt. (RF-3)
      Hecho cuando: `DefaultCodeLibrariesTest` en verde (códigos válidos y sin duplicados).
- [ ] T15. `app`: lista de bibliotecas y entrada en Ajustes. (RF-1 a RF-4)
      Hecho cuando: `CodeLibraryListViewModelTest` y `CodeLibraryListScreenTest` en verde.
- [ ] T16. `app`: pantalla de una biblioteca con buscador y exportar CSV. (RF-11, RF-17)
      Hecho cuando: `CodeLibraryViewModelTest` en verde.
- [ ] T17. `app`: editor de código con paleta y atributos. (RF-5 a RF-10)
      Hecho cuando: `CodeEditorViewModelTest` y `CodeEditorScreenTest` en verde.
- [ ] T18. `app`: ViewModel del asistente de importación (selector, vista previa, informe, duplicados). (RF-12 a RF-16)
      Hecho cuando: `CodeImportViewModelTest` en verde.
- [ ] T19. `app`: pantalla del asistente de importación. (RF-12 a RF-16)
      Hecho cuando: `CodeImportScreenTest` en verde.
- [ ] T20. `app`: selector de biblioteca en el formulario de proyecto con confirmación y la biblioteca de los proyectos nuevos en Ajustes. (RF-19, RF-21)
      Hecho cuando: `ProjectFormViewModelTest` y `ProjectDefaultsScreenTest` en verde.
- [ ] T21. `app`: «Códigos» en el menú del proyecto, de solo consulta. (RF-20)
      Hecho cuando: `ProjectCodesViewModelTest` en verde.
- [ ] T22. `app`: pasada de accesibilidad y textos (56 dp, etiquetas, color con texto, inglés). (RNF-T7, RNF-T8)
      Hecho cuando: los tests de semántica de las pantallas de `ui/codes/` en verde.
- [ ] T23. Validación final: recorrer la spec RF por RF, ejecutar el informe de trazabilidad y hacer la demo del emulador. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report.py` no muestra RF huérfanos y la demo funciona.
