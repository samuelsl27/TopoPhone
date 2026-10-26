# Tareas — Spec 046

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [ ] T1. `tools/antena`: `generar_vectores.py` con la punta del jalón para cada perfil del catálogo, el RS3 con 1,800 m (igual que los fixtures de antes), un perfil propio y jalones inclinados con radio, calculados con pyproj y Python; y su test. (RF-17, RF-20, RF-21)
      Hecho cuando: el script genera `fixtures/antena/` y su test de `tools` está en verde.
- [ ] T2. `core:survey`: `AntennaProfile` y `AntennaCatalog` con los modelos de RF-1 (`ReceiverModel` se conserva hasta T6); los términos nuevos en `docs/glosario.md`. (RF-1, RF-2)
      Hecho cuando: `AntennaCatalogTest` en verde con los desplazamientos de RF-1, el RS3 en 0,134 m y que el catálogo no trae radios.
- [ ] T3. `core:survey`: `PoleGeometry` (altura vertical desde la inclinada y altura de antena). (RF-16 a RF-19, RF-20)
      Hecho cuando: `PoleGeometryTest` en verde: vertical, inclinada con radio (≤ 0,1 mm contra √(i² − r²)), sin radio, menor y igual que el radio.
- [ ] T4. `core:survey`: `GearValidation` con nombres únicos sin distinguir mayúsculas ni tildes y los rangos de perfiles y jalones. (RF-3, RF-4, RF-9, RF-10)
      Hecho cuando: `GearValidationTest` en verde: nombres repetidos, vacíos y largos, rangos, punto y coma decimal, 0 y 10,001 m.
- [ ] T5. `core:survey`: `SavedPole`, `ActiveGear` y `resolve` (A3) con su estado. (RF-7, RF-13, RF-28)
      Hecho cuando: `ActiveGearTest` en verde: sin fila, perfil borrado, sin jalón, jalón inclinado sin radio y menor que el radio.
- [ ] T6. `core:survey`: `LiveTip`, `GroundPoint` y `ProjectPosition` reciben un `AntennaProfile` en lugar de `ReceiverModel`; el modo compensado no resta nada. (RF-20, RF-21, RF-22)
      Hecho cuando: los tests de punta del jalón en verde contra `fixtures/antena/` (≤ 1 mm) y los de antes sin cambios (RS3: ≤ 0,1 mm).
- [ ] T7. `core:survey`: `PointMeasurement` y `PointCorrections` con el perfil y el jalón; las correcciones usan el equipo guardado en el punto. (RF-20, RF-24, RF-25)
      Hecho cuando: `PointMeasurementGearTest` y `PointCorrectionsGearTest` en verde, y `ReceiverModel` borrado con todo en verde.
- [ ] T8. `core:model`: `GearUsed` en `Measurement` (nombre y altura del jalón, tipo, altura introducida, perfil, desplazamiento y altura de antena). (RF-24)
      Hecho cuando: los tests de `core:model` en verde y `Measurement` sin cambios para quien no pasa `GearUsed`.
- [ ] T9. `data`: tablas `antenna_profiles`, `saved_poles` y `receiver_gear` y las columnas nuevas de `points`, con `AutoMigration(15, 16)`. (RF-24, RF-29, RNF-T6)
      Hecho cuando: `TopoPhoneDatabaseTest` 15 → 16 en verde con puntos antiguos que se leen con el equipo vacío.
- [ ] T10. `data`: `GearRepository` con las operaciones y los resultados de A4. (RF-5, RF-6, RF-9 a RF-13)
      Hecho cuando: `GearRepositoryTest` en verde: altas, ediciones, nombre repetido, `InUse`, `LastPole`, `ActiveElsewhere` y una transacción por operación.
- [ ] T11. `data`: `GearLegacyImport` (A6). (RF-29)
      Hecho cuando: `GearLegacyImportTest` en verde: 1,8 m, otra altura, sin valor, que no se repite y que los puntos no cambian.
- [ ] T12. `data`: leer y escribir `GearUsed` en los puntos y en la consulta de la ficha. (RF-24)
      Hecho cuando: `PointRepositoryGearTest` en verde: se guarda y se lee sin redondear, y un punto de antes se lee sin equipo.
- [ ] T13. `data`: la copia completa con las tablas nuevas y el paquete de un proyecto sin ellas. (RF-30)
      Hecho cuando: `BackupGearTest` en verde: copia y restauración con perfiles, jalones y activos, y un paquete que no los lleva.
- [ ] T14. `app`: el `ActiveGear` de cada receptor con la clave de `CurrentTilt`, y `ReceiverViewModel`, `TiltViewModel` y `TiltStatusCard` leen de él en lugar de `poleHeightM`. (RF-8, RF-15, RF-20)
      Hecho cuando: los tests de `ReceiverViewModel` y `TiltViewModel` en verde con el jalón y la altura de antena nuevos.
- [ ] T15. `app`: `CollectorViewModel` con el equipo activo: medir y guardar, el bloqueo sin equipo, el bloqueo durante un promediado o un modo automático y el aviso del cambio a mitad de sesión. (RF-15, RF-24 a RF-28)
      Hecho cuando: `CollectorViewModelGearTest` en verde.
- [ ] T16. `app`: `StakeoutViewModel` y `LineStakeoutViewModel` con el mismo equipo activo. (RF-23)
      Hecho cuando: `StakeoutViewModelGearTest` y `LineStakeoutViewModelGearTest` en verde, y los tests de S018, S019 y S041 sin cambios.
- [ ] T17. `app`: `GearViewModel` con la lista de perfiles y jalones, las altas, ediciones y borrados, el cambio de perfil y de jalón activo, y los avisos. (RF-3 a RF-13, RF-16 a RF-19)
      Hecho cuando: `GearViewModelTest` en verde.
- [ ] T18. `app`: `GearScreen` y «Antena y jalón» en el menú del receptor, con sus textos en es y en. (RF-1, RF-3 a RF-12, RF-16 a RF-19, RNF-T7, RNF-T8)
      Hecho cuando: `GearScreenTest` en verde, con las zonas táctiles de 56 dp y las etiquetas de TalkBack.
- [ ] T19. `app`: el selector rápido del jalón activo en el recolector, el replanteo y las coordenadas, con el nombre, la altura y la altura de antena. (RF-14, RF-15, RF-22, RF-23)
      Hecho cuando: `GearSelectorTest` en verde: dos toques, el bloqueo durante una medida y el rótulo del modo compensado.
- [ ] T20. `app`: el jalón y la antena usados en la ficha del punto. (RF-24)
      Hecho cuando: `PointDetailsGearTest` en verde: punto con equipo, punto de antes y punto manual.
- [ ] T21. Reproducción de una medida con el RS3 y el jalón de 1,800 m y con un perfil propio, con el resultado esperado calculado aparte. (RF-20, RF-21)
      Hecho cuando: el test en verde: el mismo punto que antes con el RS3 (≤ 0,1 mm) y el distinto con el otro perfil.
- [ ] T22. Validación: revisión de los cambios en documentos anteriores (hoja de ruta, glosario, S002, S010), recorrer la spec RF por RF, `rf_report`, `pytest tools`, `forbidden_files` y la demo en el emulador según los criterios de finalización. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona. La demo de campo queda para el usuario.
