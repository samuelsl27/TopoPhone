# Tareas — Spec 008

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `tools`: rejillas SPED2ETV2 y EGM08-REDNAP Canarias en el registro con su SHA-256; vectores de pyproj para REGCAN95 / UTM 27–28, geoide de Canarias, ED50 / UTM 29–31 (dentro y fuera de la rejilla) y factores de la TM. (RF-1, RF-4, RF-7, RF-13, RF-14)
      Hecho cuando: `uv run --project tools pytest tools` en verde y los CSV nuevos en `fixtures/geodesia/`.
- [x] T2. `core:geodesy`: elipsoide internacional de 1924, UTM sobre cualquier elipsoide, factor de escala y convergencia, y radio medio de Gauss. (RF-13, RF-14, RF-15)
      Hecho cuando: `UtmFactorsTest` en verde contra los vectores (k ≤ 1·10⁻⁹, γ ≤ 0,01″) y los tests UTM de antes siguen en verde.
- [x] T3. `core:geodesy`: lector GeoTIFF con varias subrejillas, dos bandas en planos separados y metadatos; `HorizontalShiftGrid`. (RF-7)
      Hecho cuando: `HorizontalShiftGridTest` en verde con `es_ign_SPED2ETV2.tif` (subrejillas, nodos y signo de la longitud).
- [x] T4. `core:geodesy`: transformación ETRS89 → ED50 por la rejilla invertida, y fuera de la rejilla. (RF-7, RF-8)
      Hecho cuando: `Ed50Test` en verde contra los vectores ED50 / UTM (≤ 1 mm) y la ida y vuelta ≤ 0,1 mm.
- [x] T5. `core:geodesy`: geoide EGM08-REDNAP de Canarias incluido; tamaño de las rejillas. (RF-4)
      Hecho cuando: `GeoidCanariasTest` en verde (≤ 1 mm y fuera de cobertura) y la prueba de tamaño < 300 KB.
- [x] T6. `core:model`: catálogo `ProjectCrs` (10 SC con datum, huso, ámbito, EPSG y alturas), `HeightSystem`, datum de las correcciones por SC, `DatumCheck` general y huso propuesto. (RF-1 a RF-3, RF-9, RF-11, RF-17, RF-18)
      Hecho cuando: `ProjectCrsTest` y los casos nuevos de `ProjectNamesTest` en verde, y los de S006 adaptados.
- [ ] T7. `core:model`: formato de ángulos en gon y de latitud, longitud y convergencia en DD, DMS y gon con su resolución. (RF-6, RF-14)
      Hecho cuando: `AngleFormatTest` en verde.
- [ ] T8. `core:survey`: `ProjectPositionCalculator` para todos los SC y alturas, con ED50, ámbitos, factores, factor combinado y huso de la posición. (RF-4 a RF-9, RF-12 a RF-16)
      Hecho cuando: `ProjectPositionCalculatorTest` en verde contra los vectores (≤ 1 mm) en cada SC y caso no disponible.
- [ ] T9. `core:survey`: `GroundPointCalculator` con husos 27–31, REGCAN95 y el geoide de Canarias. (RF-20, RF-21)
      Hecho cuando: `GroundPointCalculatorTest` en verde con un punto en Canarias y los casos de S002 adaptados.
- [ ] T10. `data`: Room v3 con `height_system` (migración 2→3 probada y esquema exportado); repositorio y preferencias con la altura. (RF-2, RF-10; RNF)
      Hecho cuando: el test de migración (un proyecto de la v2 queda en ORTHOMETRIC), `ProjectRepositoryTest` y `ProjectPreferencesTest` en verde, y `data/schemas/…/3.json` en el repositorio.
- [ ] T11. `app`: formulario con grupos Datum, Proyección y Altura; ED50 solo con altitud; datum de las correcciones según el SC; aviso de datum general; al editar, solo el mismo datum. (RF-1 a RF-3, RF-17 a RF-19)
      Hecho cuando: `ProjectFormCrsTest` en verde y los tests de S006 del formulario adaptados.
- [ ] T12. `app`: huso propuesto por la posición al crear un proyecto. (RF-11)
      Hecho cuando: `ProjectFormCrsTest` en verde para Península, Canarias, ED50, geográficas y sin posición.
- [ ] T13. `app`: vista de proyecto con geográficas, altura elipsoidal, factores, aviso de otro huso y fuera de ED50; nombres de los SC en la lista. (RF-5, RF-6, RF-8, RF-12 a RF-16)
      Hecho cuando: `ProjectViewCrsTest` en verde y los tests de S006 de la vista y la lista adaptados.
- [ ] T14. `app`: "Proyectos nuevos" en Ajustes con los nuevos SC y la altura. (RF-10)
      Hecho cuando: `ProjectDefaultsTest` en verde con los casos nuevos.
- [ ] T15. `app`: pantalla Coordenadas del receptor en Canarias (REGCAN95, husos 27–28 y su geoide). (RF-20, RF-21)
      Hecho cuando: `CoordinatesCardTest` en verde con un punto en Canarias.
- [ ] T16. Validación: recorrer la spec RF por RF, `rf_report`, demo en el emulador con reproducciones sintéticas en Canarias y en la Península según los criterios de finalización. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona.
