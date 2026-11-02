# Tareas — Spec 052

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `tools/ejes`: `generar_toma_lineal.py` con `fixtures/ejes/toma/`: eje tomado con y sin radios (contra `pi-vertices.csv`), longitudinal con desplazamientos y alturas dados, transversal con puntos dentro y fuera de sección; todo en aritmética racional. (RF-5 a RF-7, RF-11, RF-12, RF-17, RF-18)
      Hecho cuando: el script genera los ficheros y su test de `tools` está en verde.
- [x] T2. `core:model`: `LinearSurveyKind`, `LinearSurvey`, `SurveyPoint`, `SurveyFlag`, `AlignmentSource.FIELD` y los términos en `docs/glosario.md`. (RF-8)
      Hecho cuando: los tests de `core:model` en verde.
- [ ] T3. `core:survey`: `FieldAxisBuilder` (rectas, radios, rechazos, rasante opcional) y `PolylineLength`. (RF-3, RF-5 a RF-7)
      Hecho cuando: `FieldAxisBuilderTest` y `PolylineLengthTest` en verde a 1 mm.
- [ ] T4. `core:survey`: `ProfileSampler` (PK, desplazamiento, diferencia de cota y marcas) y `ProfileSeries`. (RF-10 a RF-12, RF-17, RF-18)
      Hecho cuando: `ProfileSamplerTest` y `ProfileSeriesTest` en verde a 1 mm.
- [ ] T5. `core:survey`: `CrossProfileBuilder` (a perfil de terreno de S050). (RF-17, RF-19, RF-20)
      Hecho cuando: `CrossProfileBuilderTest` en verde.
- [ ] T6. `core:formats`: `ProfileCsv` con dorado. (RF-15, RF-22)
      Hecho cuando: `ProfileCsvTest` en verde.
- [ ] T7. `data`: Room v27 (`linear_surveys`, `linear_survey_points`), `AutoMigration(26, 27)` y el esquema 27. (RF-8, RF-9, RNF-T6)
      Hecho cuando: `TopoPhoneDatabaseTest` 26 a 27 en verde.
- [ ] T8. `data`: `LinearSurveyRepository` (alta atómica de puntos, excluir, terminar eje, terminar transversal con historial, borrar, reanudar, log). (RF-2, RF-4, RF-8, RF-9, RF-20, RF-22, RF-23)
      Hecho cuando: `LinearSurveyRepositoryTest` en verde.
- [ ] T9. `data`: copias de seguridad y paquetes con las tomas. (RF-23)
      Hecho cuando: `LinearSurveyBackupTest` en verde.
- [ ] T10. `app`: `CollectorViewModel.setPointSavedListener`. (RF-2)
      Hecho cuando: `CollectorSavedListenerTest` en verde.
- [ ] T11. `app`: `AxisSurveyViewModel` (abrir, añadir, quitar último, terminar, rechazos, reanudar). (RF-1 a RF-9)
      Hecho cuando: `AxisSurveyViewModelTest` en verde.
- [ ] T12. `app`: panel de «Tomar eje», la línea de la toma en el mapa y «Tomar eje» en `ProjectView`. (RF-1, RF-3, RNF-T7, RNF-T8)
      Hecho cuando: `AxisSurveyPanelTest` en verde.
- [ ] T13. `app`: `ProfileView` (alzado, zoom y desplazamiento, rasante, diferencia, sección de diseño). (RF-13, RF-19, RNF-T13)
      Hecho cuando: `ProfileViewTest` en verde.
- [ ] T14. `app`: `LongitudinalViewModel` (guiado, libre y desde selección, marcas, quitar punto). (RF-10 a RF-12, RF-14, RF-21)
      Hecho cuando: `LongitudinalViewModelTest` en verde.
- [ ] T15. `app`: `ProfilePanel` del longitudinal, exportar CSV y «Perfil longitudinal» en la hoja y la ficha del eje. (RF-10, RF-13, RF-15)
      Hecho cuando: `ProfilePanelTest`, `AlignmentsSheetTest` y `AlignmentTouchTest` ampliados en verde.
- [ ] T16. `app`: `CrossViewModel` (guía al PK, tolerancia de sección, marcas, guardar perfil con historial, quitar punto). (RF-16 a RF-18, RF-20, RF-21)
      Hecho cuando: `CrossViewModelTest` en verde.
- [ ] T17. `app`: panel del transversal y «Perfil transversal» en la hoja y la ficha del eje. (RF-16, RF-19)
      Hecho cuando: los tests del panel, de la hoja y de la ficha en verde.
- [ ] T18. `app`: `LinearSurveysScreen` (lista de perfiles tomados, ver, exportar, borrar con confirmación) y la navegación. (RF-22)
      Hecho cuando: `LinearSurveysScreenTest` en verde.
- [ ] T19. Reproducción longitudinal y transversal, época a época contra el cálculo aparte. (RF-11, RF-12, RF-17, RF-18)
      Hecho cuando: `LongitudinalReplayTest` y `CrossReplayTest` en verde a 1 mm.
- [ ] T20. Rendimiento: 2.000 puntos dibujados en menos de 100 ms y la guía en menos de 16 ms. (RNF-T13)
      Hecho cuando: `ProfilePerformanceTest` en verde.
- [ ] T21. Pantalla encendida y accesibilidad de las tres tomas (frases de TalkBack, 56 dp). (RNF-T2, RNF-T8)
      Hecho cuando: los tests de las pantallas ampliados en verde.
- [ ] T22. Validación: cambios en documentos anteriores (hoja de ruta, Room v27), recorrer la spec RF por RF, `rf_report`, `pytest tools`, `forbidden_files` y la demo en el emulador. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona. La demo de campo queda para el usuario.
