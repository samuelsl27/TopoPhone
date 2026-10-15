# Tareas — Spec 006

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `core:model`: unidades lineales y angulares, datum de las correcciones, SC del proyecto y formato de longitudes por unidad. (RF-8, RF-9, RF-10)
      Hecho cuando: `ProjectUnitsTest` en verde (factores exactos, formato con configuración regional).
- [x] T2. `core:model`: reglas de nombres (comparar sin mayúsculas ni tildes, "(copia n)", "(restaurado n)") y aviso de datum. (RF-4, RF-7, RF-11, RF-16, RF-25)
      Hecho cuando: `ProjectNamesTest` en verde.
- [ ] T3. `tools` + `core:survey`: vectores de pyproj de UTM con huso fijo y `ProjectPositionCalculator`. (RF-29, RF-30)
      Hecho cuando: `ProjectPositionCalculatorTest` en verde (≤ 1 mm) y el test del generador en `tools`.
- [ ] T4. `data`: Room v1 (proyectos y carpetas) con WAL y `synchronous=FULL`, esquema exportado y banco de pruebas de migraciones. (RNF de la spec)
      Hecho cuando: el test de durabilidad y el de `MigrationTestHelper` en verde, y el esquema en `data/schemas/`.
- [ ] T5. `data`: `ProjectRepository` crear, editar, abrir y listar, con sus errores y su orden. (RF-1, RF-2, RF-7, RF-12, RF-14, RF-15, RF-20)
      Hecho cuando: `ProjectRepositoryTest` en verde para esos casos.
- [ ] T6. `data`: duplicar, búsqueda y carpetas (crear, renombrar, mover, borrar). (RF-4, RF-16 a RF-21)
      Hecho cuando: `ProjectRepositoryTest` en verde para esos casos.
- [ ] T7. `data`: papelera (mover, restaurar, borrar definitivamente, purga a los 30 días con reloj inyectado). (RF-22 a RF-27)
      Hecho cuando: `ProjectRepositoryTest` en verde para la papelera.
- [ ] T8. `data`: valores por defecto de los proyectos en DataStore. (RF-6, RF-13)
      Hecho cuando: `ProjectPreferencesTest` en verde.
- [ ] T9. `app`: grafo de Levantamiento y lista de proyectos (carpetas, proyectos, vacío); adaptar los tests de S001 que daban Levantamiento por vacía. (RF-1, RF-2, RF-3)
      Hecho cuando: `ProjectListTest` en verde y `./gradlew check` sin tests de S001 rotos.
- [ ] T10. `app`: búsqueda en la lista. (RF-4)
      Hecho cuando: `ProjectSearchTest` en verde.
- [ ] T11. `app`: formulario de proyecto nuevo con sus valores por defecto, validaciones y aviso de datum; al guardar se abre. (RF-5 a RF-12)
      Hecho cuando: `ProjectFormTest` en verde para crear.
- [ ] T12. `app`: editar un proyecto y cambiar su SC. (RF-14, RF-15)
      Hecho cuando: `ProjectFormTest` en verde para editar.
- [ ] T13. `app`: menú de cada proyecto (duplicar, mover a carpeta) y menú "⋮" de la barra. (RF-16, RF-19)
      Hecho cuando: `ProjectMenuTest` en verde.
- [ ] T14. `app`: carpetas (crear, renombrar, abrir, borrar). (RF-17, RF-18, RF-20, RF-21)
      Hecho cuando: `ProjectFolderTest` en verde.
- [ ] T15. `app`: borrar un proyecto y papelera (restaurar, borrar definitivamente) y purga al arrancar. (RF-22 a RF-27)
      Hecho cuando: `TrashTest` en verde.
- [ ] T16. `app`: pantalla "Proyectos nuevos" en Ajustes. (RF-13)
      Hecho cuando: `ProjectDefaultsTest` en verde.
- [ ] T17. `app`: vista de proyecto con sus datos, el aviso de datum y "sin puntos". (RF-11, RF-28, RF-31)
      Hecho cuando: `ProjectViewTest` en verde para esos casos.
- [ ] T18. `app`: posición actual en la vista de proyecto, en su SC y unidades, o no disponible con su motivo. (RF-29, RF-30)
      Hecho cuando: `ProjectViewTest` en verde para la posición.
- [ ] T19. Rendimiento con 500 proyectos (lista < 1 s, búsqueda < 300 ms). (RNF de la spec)
      Hecho cuando: el test de rendimiento en verde.
- [ ] T20. Validación: recorrer la spec RF por RF, `rf_report`, demo en el emulador según los criterios de finalización. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona.
