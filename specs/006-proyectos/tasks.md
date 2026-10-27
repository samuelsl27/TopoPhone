# Tareas — Spec 006

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `core:model`: unidades lineales y angulares, datum de las correcciones, SC del proyecto y formato de longitudes por unidad. (RF-8, RF-9, RF-10)
      Hecho cuando: `ProjectUnitsTest` en verde (factores exactos, formato con configuración regional).
- [x] T2. `core:model`: reglas de nombres (comparar sin mayúsculas ni tildes, "(copia n)", "(restaurado n)") y aviso de datum. (RF-4, RF-7, RF-11, RF-16, RF-25)
      Hecho cuando: `ProjectNamesTest` en verde.
- [x] T3. `tools` + `core:survey`: vectores de pyproj de UTM con huso fijo y `ProjectPositionCalculator`. (RF-29, RF-30)
      Hecho cuando: `ProjectPositionCalculatorTest` en verde (≤ 1 mm) y el test del generador en `tools`.
- [x] T4. `data`: Room v1 (proyectos y carpetas) con WAL y `synchronous=FULL`, esquema exportado y banco de pruebas de migraciones. (RNF de la spec)
      Hecho cuando: el test de durabilidad y el de `MigrationTestHelper` en verde, y el esquema en `data/schemas/`.
- [x] T5. `data`: `ProjectRepository` crear, editar, abrir y listar, con sus errores y su orden. (RF-1, RF-2, RF-7, RF-12, RF-14, RF-15, RF-20)
      Hecho cuando: `ProjectRepositoryTest` en verde para esos casos.
- [x] T6. `data`: duplicar, búsqueda y carpetas (crear, renombrar, mover, borrar). (RF-4, RF-16 a RF-21)
      Hecho cuando: `ProjectRepositoryTest` en verde para esos casos.
- [x] T7. `data`: papelera (mover, restaurar, borrar definitivamente, purga a los 30 días con reloj inyectado). (RF-22 a RF-27)
      Hecho cuando: `ProjectRepositoryTest` en verde para la papelera.
- [x] T8. `data`: valores por defecto de los proyectos en DataStore. (RF-6, RF-13)
      Hecho cuando: `ProjectPreferencesTest` en verde.
- [x] T9. `app`: grafo de Levantamiento y lista de proyectos (carpetas, proyectos, vacío); adaptar los tests de S001 que daban Levantamiento por vacía. (RF-1, RF-2, RF-3)
      Hecho cuando: `ProjectListTest` en verde y `./gradlew check` sin tests de S001 rotos.
- [x] T10. `app`: búsqueda en la lista. (RF-4)
      Hecho cuando: `ProjectSearchTest` en verde.
- [x] T11. `app`: formulario de proyecto nuevo con sus valores por defecto, validaciones y aviso de datum; al guardar se abre. (RF-5 a RF-12)
      Hecho cuando: `ProjectFormTest` en verde para crear.
- [x] T12. `app`: editar un proyecto y cambiar su SC. (RF-14, RF-15)
      Hecho cuando: `ProjectFormTest` en verde para editar.
- [x] T13. `app`: menú de cada proyecto (duplicar, mover a carpeta) y menú "⋮" de la barra. (RF-16, RF-19)
      Hecho cuando: `ProjectMenuTest` en verde.
- [x] T14. `app`: carpetas (crear, renombrar, abrir, borrar). (RF-17, RF-18, RF-20, RF-21)
      Hecho cuando: `ProjectFolderTest` en verde.
- [x] T15. `app`: borrar un proyecto y papelera (restaurar, borrar definitivamente) y purga al arrancar. (RF-22 a RF-27)
      Hecho cuando: `TrashTest` en verde.
- [x] T16. `app`: pantalla "Proyectos nuevos" en Ajustes. (RF-13)
      Hecho cuando: `ProjectDefaultsTest` en verde.
- [x] T17. `app`: vista de proyecto con sus datos, el aviso de datum y "sin puntos". (RF-11, RF-28, RF-31)
      Hecho cuando: `ProjectViewTest` en verde para esos casos.
- [x] T18. `app`: posición actual en la vista de proyecto, en su SC y unidades, o no disponible con su motivo. (RF-29, RF-30)
      Hecho cuando: `ProjectViewTest` en verde para la posición.
- [x] T19. Rendimiento con 500 proyectos (lista < 1 s, búsqueda < 300 ms). (RNF de la spec)
      Hecho cuando: el test de rendimiento en verde.
- [x] T20. Validación: recorrer la spec RF por RF, `rf_report`, demo en el emulador según los criterios de finalización. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona.

## Ampliación: punto central (RF-32 a RF-38)

- [x] T21. `core:model`: `CentralPoint`, `ProjectDraft.centralPoint` y `Project.centralPoint`, y `ProjectCenter.of` con la regla del centro del proyecto. (RF-32, RF-35, RF-37)
      Hecho cuando: `ProjectCenterTest` en verde (las cuatro ramas y el borrado) y `core:model` compila con los usos adaptados.
- [x] T22. `core:survey`: `CentralPointResolver` (coordenadas escritas, toque y posición del receptor, con sus motivos de rechazo). (RF-33, RF-34)
      Hecho cuando: `CentralPointResolverTest` en verde: UTM contra pyproj (≤ 1 mm), geográficas, ED50, sin posición, fuera del ámbito y fuera de la rejilla ED50.
- [x] T23. `data`: Room v18 con `central_latitude` y `central_longitude` y `AutoMigration(17, 18)`; nota en la cabecera de `TopoPhoneDatabase`. (RF-32, RNF-T6)
      Hecho cuando: `TopoPhoneDatabaseTest` 17 → 18 en verde con proyectos de antes que se leen sin punto central y el esquema 18 en `data/schemas/`.
- [x] T24. `data`: el repositorio guarda, edita, quita y duplica el punto central, sin redondear. (RF-32, RF-35, RF-38)
      Hecho cuando: `ProjectRepositoryCentralPointTest` en verde, incluida la copia con el mismo punto.
- [x] T25. `data`: el punto central en las copias de seguridad y en los paquetes `.topoproj`. (RF-38)
      Hecho cuando: `CentralPointBackupTest` en verde: copia y restauración, y exportar e importar un paquete conservan el punto; una copia de la v17 se restaura sin él.
- [x] T26. `app`: `ProjectCenterProvider` como único sitio que calcula el centro; los ViewModels de importación de S040 y S042 lo usan en lugar de su `centerOf`. (RF-37)
      Hecho cuando: `ProjectCenterProviderTest` en verde y los tests de S040 y S042 sin cambios de comportamiento, con un caso de aviso de distancia medido desde el punto central.
- [x] T27. `app`: sección «Punto central» del formulario (sin fijar, fijado, quitar) y escribir coordenadas, con sus textos en es y en. (RF-32, RF-33, RF-34, RF-35, RNF-T7, RNF-T8)
      Hecho cuando: `ProjectFormCentralPointTest` en verde para nuevo y edición, con rechazo y motivo, zonas de 56 dp y etiquetas de TalkBack.
- [x] T28. `app`: «Usar mi posición» con el receptor y con una reproducción, y el motivo sin posición. (RF-33, RF-34)
      Hecho cuando: `ProjectFormCentralPointTest` en verde para la posición, con fix, sin fix y fuera del ámbito.
- [x] T29. `app`: `MapController.showCentralPoint` y su símbolo propio en el mapa del proyecto, con icono y texto de accesibilidad. (RF-36, RNF-T8)
      Hecho cuando: `MapCentralPointTest` en verde: aparece con punto central, desaparece al quitarlo y no se confunde con la base ni con el rover.
- [x] T30. `app`: «Elegir en el mapa»: selector a pantalla completa con el doble de `MapController`. (RF-33, RF-34)
      Hecho cuando: `CentralPointPickerTest` en verde: un toque coloca el punto, «Aceptar» lo pasa al formulario, «Cancelar» no cambia nada y un toque fuera del ámbito indica el motivo.
- [x] T31. `app`: «Punto central» en los datos del proyecto, con sus coordenadas en el SC y las unidades del proyecto. (RF-36)
      Hecho cuando: `ProjectInfoCentralPointTest` en verde: fijado, sin fijar y con otro SC tras cambiarlo.
- [ ] T32. Validación de la ampliación: `rf_report` con S006 en 38 de 38, `check`, `pytest tools`, `forbidden_files`, demo en el emulador (tocando el mapa, escribiendo coordenadas y con una reproducción; símbolo en el mapa y coordenadas en los datos; borrarlo; duplicar el proyecto), S006 «implementada» en la hoja de ruta, nota en S040/RF-10 y memoria. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona.
