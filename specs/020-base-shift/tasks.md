# Tareas — Spec 020

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `tools/base_shift`: `generar_base_shift.py` y `fixtures/base_shift/` con el vector, su ENU y las coordenadas de dos puntos tras aplicarlo, para ETRS89, REGCAN95 y correcciones ITRF, el caso sin altura y los módulos límite, todo con `pyproj`. (Constitución nº 2, RF-3 a RF-7)
      Hecho cuando: el script genera los ficheros y su test de `tools` está en verde.
- [x] T2. `core:geodesy` y `core:model`: `Ecef.plus/minus/length`, `BaseShiftVector`, `BaseShift`, `BaseShiftLimits` y `Measurement.shift`. (RF-3, RF-5, RF-8, RF-12)
      Hecho cuando: los tests de `core:geodesy` y `core:model` en verde, con los valores por defecto (los puntos sin desplazamiento no cambian).
- [x] T3. `core:survey`: `BaseShiftCalculator` (conocido a ECEF, vector, ENU, módulo, caso sin altura y clasificación). (RF-3 a RF-6)
      Hecho cuando: `BaseShiftCalculatorTest` en verde contra `fixtures/base_shift/` a menos de 1 mm y la ida y vuelta a menos de 0,1 mm.
- [x] T4. `core:survey`: `BaseShiftApplier` y el vector en `LiveTip`, `PointMeasurement` (`finish` y `use`) y `PointCorrections`, con la punta sin desplazar en el resultado. (RF-7, RF-9, RF-10)
      Hecho cuando: `BaseShiftApplierTest`, `LiveTipShiftTest`, `PointMeasurementShiftTest` y `PointCorrectionsShiftTest` en verde; los de S010, S011, S016 y S019 siguen en verde.
- [x] T5. `data`: Room v23 (`base_shifts`, columnas de `points`), sus DAO, `AutoMigration(22, 23)` y el esquema 23 en `data/schemas/`. (RNF-T6, RF-8, RF-12)
      Hecho cuando: `TopoPhoneDatabaseTest` 22 → 23 en verde: proyectos, puntos y replanteos conservan sus datos.
- [x] T6. `data`: `BaseShiftRepository` y `ActiveBaseShift` (crear, activar, desactivar, reactivar, borrar, lista, desactivar por cambio de datum). (RF-9, RF-12, RF-16)
      Hecho cuando: `BaseShiftRepositoryTest` en verde: uno activo por proyecto, borrar sin tocar los puntos y el datum.
- [x] T7. `data`: el punto se guarda con el vector, la punta sin desplazar y el conocido; copia de seguridad, paquete y restauración con `base_shifts`. (RF-8, RNF-T6)
      Hecho cuando: `BaseShiftPointSaveTest` y `BaseShiftBackupTest` en verde, con ids nuevos remapeados en el paquete.
- [x] T8. `core:formats`: ΔX, ΔY y ΔZ en «Todas las columnas» del CSV y en el GeoPackage. (RF-13)
      Hecho cuando: `CsvExportTest` y `GpkgExportTest` ampliados en verde, con las columnas vacías sin desplazamiento.
- [x] T9. `app`: `BaseShiftViewModel`: punto conocido (del proyecto o escrito), medida sin guardar, resultado, confirmaciones, rechazo, sin altura, lista de desplazamientos. (RF-1 a RF-6, RF-12, RF-14)
      Hecho cuando: `BaseShiftViewModelTest` en verde.
- [x] T10. `app`: `BaseShiftScreen`, `BaseShiftRoute`, `SurveyPage.BaseShift` y la entrada en el menú, con sus textos en es y en. (RF-1, RF-4, RF-12, RNF-T7, RNF-T8)
      Hecho cuando: `BaseShiftScreenTest` en verde, con las zonas táctiles de 56 dp y el resumen.
- [x] T11. `app`: el recolector con el desplazamiento (en vivo, medida final y automático), el aviso «Base shift activo» y desactivar desde allí. (RF-7, RF-9, RF-11, RF-14)
      Hecho cuando: `CollectorBaseShiftTest` en verde y los tests del recolector siguen en verde.
- [x] T12. `app`: el replanteo (puntos, CAD y eje) con el desplazamiento y el mismo aviso. (RF-7, RF-11)
      Hecho cuando: `StakeoutBaseShiftTest` en verde y los de S018, S019, S041 y S054 siguen en verde.
- [x] T13. `app`: la tarjeta «Base shift» de la ficha del punto y el recálculo del jalón con el vector del punto desde la ficha y el lote. (RF-10, RF-13)
      Hecho cuando: `PointDetailsShiftTest` en verde.
- [x] T14. `app`: los avisos de cambio de base y de cambio de datum, con «Desactivar» y «Repetir». (RF-15, RF-16)
      Hecho cuando: `BaseShiftNoticeTest` en verde.
- [x] T15. Rendimiento y reproducción sintética `rs3-base-shift`: `BaseShiftPerformanceTest` y un recorrido que mide un conocido, activa, mide dos puntos y desactiva, con el resultado esperado calculado aparte. (RNF, criterios de finalización)
      Hecho cuando: aplicar el vector no añade más de 1 ms por época y el test de la reproducción en verde.
- [x] T16. Validación: cambios en documentos anteriores (hoja de ruta con S017b a la v24, glosario), recorrer la spec RF por RF, `rf_report`, `pytest tools`, `forbidden_files` y la demo en el emulador según los criterios de finalización. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona. La demo de campo queda para el usuario.
      Demo en el emulador (30/10/2026, APK debug, reproducción `rs3-base-shift` en bucle): proyecto «Demo shift» ETRS89 UTM 30 con altura elipsoidal; menú «⋮» de la lista de objetos > «Base shift»; punto conocido escrito (V-12); la medida cayó en la parada B (la reproducción en bucle no deja elegir parada), con ΔX 8,4541 · ΔY 5,3650 · ΔZ −10,5796 m y módulo 14,5665 m, así que salió el aviso de más de 10 m y «Activar» quedó desactivado hasta «Confirmo el desplazamiento»; activado, la lista lo marca «Activo» y «Calculado en una reproducción», el recolector enseña «Base shift activo · V-12» con «Desactivar», el punto medido cae en V-12 y su ficha lleva la tarjeta «Base shift» con V-12 y ΔX, ΔY, ΔZ; tras desactivar, el punto siguiente dice «Sin base shift» y coincide con la parada C sin desplazar; la lista conserva el cálculo con «Reactivar» y «Borrar». No se recorrió a mano el rechazo de más de 1.000 m ni la exportación del CSV (los cubren los tests). La demo de campo (base en SINGLE y un vértice conocido) queda para el usuario.
