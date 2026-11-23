# Tareas — Spec 039

> Cada tarea dura 30 min como máximo, sigue el orden de dependencias y se implementa con los tests primero. Cada tarea cierra con `./gradlew check` en verde (se repite con `--no-parallel` si falla un test ajeno intermitente), la casilla marcada y un commit `S039 Tn: …` en `main`, con la línea Co-Authored-By.

## Vectores y núcleo (`tools/localizacion`, `core:model`, `core:survey`)
- [x] T1. `tools/localizacion/generar_localizacion.py`: pares sintéticos con una transformación conocida (con y sin ruido, con y sin escala fija, con plano vertical), ajuste por ecuaciones normales en `fractions` y posiciones con `pyproj` en UTM 30N y Lambert-93, en `fixtures/localizacion/`. (RF-7, RF-8, RF-9, constitución nº 2)
      Hecho cuando: `test_localizacion.py` comprueba las filas, que se regenera idéntico y que `pytest tools` pasa.
- [x] T2. `core:model`: `LocalizationParams`, `LocalizationPair`, `LocalizationStatus`, `LocalizationLimits` y `Point.localizationId`. (RF-4, RF-13, RF-19)
      Hecho cuando: `LocalizationParamsTest` comprueba los valores por defecto, los límites (50, 3 cm, 0,001) y el texto estable de los parámetros.
- [x] T3. `LocalizationSolver` horizontal: traslación, semejanza, rígido (escala fija) y los casos degenerados. (RF-7, RF-8, RF-12)
      Hecho cuando: `LocalizationSolverTest` cubre 1 par, 2 o más, escala fija exactamente 1, sin pares y puntos coincidentes.
- [x] T4. `LocalizationSolver` vertical y residuos: desplazamiento, plano, alineados, residuos, RMS, mayor residuo y avisos de umbral. (RF-9, RF-10, RF-13)
      Hecho cuando: `LocalizationSolverTest` cubre 1 y 2 pares, 3 o más, 3 alineados (aviso), la pendiente en ppm y los umbrales.
- [x] T5. `LocalizationApplier` (localizar y deslocalizar), `LocalizationVectorsTest` frente a `fixtures/localizacion/` y rendimiento. (RF-7, RF-9, RF-17, constitución nº 2, RNF-T13)
      Hecho cuando: parámetros y residuos coinciden a 0,1 mm y 1e-9, la ida y vuelta cierra a 0,1 mm, un caso sin ruido recupera los parámetros con residuos < 0,1 mm y 50 pares se resuelven en menos de 100 ms.
- [x] T6. `LocalizationDraftRules`: admisión de pares, límite de 50 y calidad del punto (FIX y precisión). (RF-3, RF-4, RF-6)
      Hecho cuando: `LocalizationDraftRulesTest` cubre punto sin medida, repetido, el par 51 y un punto en FLOAT o fuera de precisión que queda sin confirmar.

## Datos (`data`)
- [ ] T7. Room v40: tablas `localizations`, `localization_pairs` y `localized_crs`, columna `points.localization_id` y migración 39→40. (RF-13, RF-19, RF-26)
      Hecho cuando: `TopoPhoneDatabaseTest` 39 → 40 conserva proyectos y puntos y las entidades guardan y leen sin redondear.
- [ ] T8. `LocalizationRepository` del borrador: crear, añadir y quitar pares, componentes, escala fija y umbrales. (RF-2, RF-5, RF-10, RF-11, RF-13)
      Hecho cuando: `LocalizationRepositoryTest` comprueba un solo borrador por proyecto y que el recálculo sale de los pares guardados.
- [ ] T9. `apply`, `remove` y `reactivate` en una transacción: reescribe los puntos GNSS no editados, deja intactos los manuales, importados, calculados y editados, y mueve la activa al historial. (RF-14, RF-16, RF-17, RF-18, RF-20, RF-21, RNF-T6)
      Hecho cuando: `LocalizationRepositoryTest` comprueba el recuento, que `measured_*` no cambia, la ida y vuelta de quitar, que solo hay una activa y que un fallo a mitad no deja nada a medias.
- [ ] T10. `ActiveLocalization` (`StateFlow` y lectura instantánea) y el log de aplicar y quitar sin coordenadas. (RF-17, RF-26)
      Hecho cuando: un test comprueba que cambia al aplicar y quitar y que el log no lleva coordenadas.
- [ ] T11. Copia de seguridad y paquete con las localizaciones y `points.localization_id`. (RF-26)
      Hecho cuando: `LocalizationBackupTest` hace copia y restauración, y paquete e importación, con borrador, activa e historial; `BackupMigrationTest` abre las copias anteriores.

## Aplicar en vivo (`core:survey`, `app`)
- [ ] T12. `PointMeasurement`, `LiveTip` y `PointCorrections` reciben la localización: el punto se localiza tras el SC, se guarda `localization_id` y el jalón corregido usa la activa. (RF-17, RF-19)
      Hecho cuando: `PointMeasurementLocalizationTest` y `PointCorrectionsLocalizationTest` comprueban coordenadas localizadas, `measured_*` sin localizar, y que un punto editado no cambia.
- [ ] T13. El recolector, `AutoCollector` y el panel de posición localizan lo que enseñan y miden, con el indicador «Localizado». (RF-14, RF-17)
      Hecho cuando: `CollectorLocalizationTest` comprueba la posición en vivo, el punto guardado y el indicador.
- [ ] T14. Replanteo de puntos, de líneas, por eje y de taludes, análisis de ejes y mapa localizan la punta. (RF-17)
      Hecho cuando: `StakeoutLocalizationTest` comprueba que la guía usa la punta localizada contra el punto de diseño.
- [ ] T15. La ficha del punto muestra las coordenadas sin localizar y las localizadas, y dice que un punto editado no se transforma. (RF-18, RF-20)
      Hecho cuando: `PointDetailsLocalizationTest` cubre un punto localizado, uno sin localización y uno editado.

## Pantalla de localización (`app`)
- [ ] T16. `LocalizationRoute`, `LocalizationViewModel` y la entrada «Localización» del menú del proyecto, con el estado y la lista de pares. (RF-1)
      Hecho cuando: `LocalizationScreenTest` muestra «Sin localización», una activa con su RMS y la lista de pares.
- [ ] T17. Añadir un par: elegir el punto GNSS y escribir las coordenadas conocidas o tomarlas de un punto manual o importado, con el rechazo de cada motivo y la confirmación de un punto de baja calidad. (RF-2, RF-3, RF-4, RF-6)
      Hecho cuando: `LocalizationViewModelTest` y `LocalizationScreenTest` cubren el par válido, los tres rechazos y la confirmación de calidad.
- [ ] T18. Activar o excluir cada componente, quitar un par, «Fijar escala = 1» y recálculo instantáneo. (RF-5, RF-8, RF-11)
      Hecho cuando: `LocalizationViewModelTest` comprueba que cada cambio recalcula el borrador y que con la escala fija sale exactamente 1.
- [ ] T19. Los resultados: traslación, giro en el formato del proyecto, escala y ppm, pendiente, residuos por par, RMS, mayor residuo, umbrales ajustables y avisos con texto. (RF-9, RF-10, RF-12, RF-13)
      Hecho cuando: `LocalizationScreenTest` comprueba cada valor, el destacado con texto de un residuo y de una escala fuera de umbral y el motivo cuando no se puede calcular.
- [ ] T20. «Aplicar al proyecto» con confirmación y recuento, la segunda confirmación por umbrales, editar sin tocar la activa, «Quitar», historial y «Reactivar». (RF-14, RF-15, RF-16, RF-21)
      Hecho cuando: `LocalizationScreenTest` y `LocalizationViewModelTest` cubren aplicar, aplicar con umbrales superados, editar con una activa, quitar y reactivar.

## Exclusiones con otras funciones (`app`)
- [ ] T21. Terreno y cambio de SC: no aplicar con terreno, no pasar a terreno ni cambiar el SC o las alturas con una localización activa. (RF-22, RF-27)
      Hecho cuando: `LocalizationTerrainTest` y `LocalizationCrsLockTest` comprueban los cuatro bloqueos y sus textos.

## Exportar y guardar (`core:formats`, `app`)
- [ ] T22. CSV con la columna `Localization id`, coordenadas localizadas y «Exportar sin localizar»; el GeoPackage de S053 repite la columna. (RF-23)
      Hecho cuando: `CsvExportTest` y la prueba del GeoPackage comprueban la columna, las coordenadas localizadas y las sin localizar de los puntos GNSS no editados.
- [ ] T23. Shapefile y GeoPackage no ofrecen «sistema del proyecto» con una localización activa: solo geográficas, con el aviso. (RF-23)
      Hecho cuando: las pruebas de las pantallas de Shapefile y GeoPackage comprueban la opción oculta y el aviso.
- [ ] T24. «Informe»: CSV con los parámetros, los pares y los residuos, para guardar o compartir. (RF-25)
      Hecho cuando: `LocalizationReportCsvTest` comprueba el contenido con `Locale.ROOT` y el botón guarda y comparte.
- [ ] T25. «Guardar como SC» en «Mis SC» (`localized_crs`) y elegirlo en otro proyecto, que queda con el SC base y la localización activa sin pares. (RF-24, RF-28)
      Hecho cuando: `LocalizedCrsRepositoryTest` y `LocalizedCrsFlowTest` guardan, listan con la marca «Localizado», eligen en otro proyecto y respetan RF-22 y RF-27.

## Validación
- [ ] T26. Reproducción `rs3-localizacion`, demo en el emulador y validación final: `rf_report` con 28 RF, `check`, `pytest` de `tools`, `forbidden_files` y `assembleDebug`; `plan.md` con «Cambios al implementar» y «Validación»; hoja de ruta (S039 implementada, Room v40) y glosario. (todos los RF)
      Hecho cuando: `rf_report.py` da 28 RF con test y 0 pendientes, todo en verde, y la demo cubre medir, formar 4 pares, residuos, aplicar, ver «Localizado», quitar y exportar.
