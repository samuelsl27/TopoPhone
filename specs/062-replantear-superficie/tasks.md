# Tareas — Spec 062

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `core:model` y `core:survey`: `StakeSource.Surface`, `StakeoutNames.nextSurface` y `StakeoutDiff.surfaceRef`. (RF-16 a RF-18)
      Hecho cuando: `StakeoutNamesSurfaceTest` y `SurfaceRefTest` en verde (desmonte, terraplén, en cota, sin tolerancia horizontal).
- [x] T2. `data`: Room v42 (`points.stake_surface_only`), `AutoMigration(41, 42)`, el esquema 42 y el paso de `PointEntity` a `StakeSource.Surface` y al revés. (RF-17, RNF-T6)
      Hecho cuando: `Migration41To42Test` y el test de ida y vuelta de un punto de origen superficie (también en una copia) en verde.
- [x] T3. `core:formats`: `StakeoutReportCsv` con las filas de origen superficie y los dorados regenerados. (RF-21)
      Hecho cuando: `StakeoutReportCsvSurfaceTest` en verde y los demás dorados del informe no cambian.
- [x] T4. `app`: `StakeoutController` con `surfaceOnly`: el objetivo es la punta, sin zonas ni avisos horizontales, el nivel con el desplazamiento y fuera de la superficie. (RF-7 a RF-9, RF-13, RF-15)
      Hecho cuando: `SurfaceOnlyControllerTest` en verde y los tests de `StakeoutController` siguen en verde.
- [x] T5. `app`: `StakeoutViewModel.openSurface`: activar y mostrar la superficie, el objetivo, el `StakeSaveInfo`, y el cierre cuando la superficie deja de ser la activa. (RF-6, RF-22)
      Hecho cuando: `SurfaceStakeOpenTest` en verde.
- [ ] T6. `app`: el recolector con origen superficie: SUP1, SUP2…, el bloqueo «Fuera de la superficie», lo guardado con la cota en la posición medida y la confirmación. (RF-14, RF-16 a RF-19)
      Hecho cuando: `SurfaceStakeCollectorTest` en verde (≤ 1 mm frente al plano).
- [ ] T7. `app`: `SurfaceStakePlate` en la hoja de campo y las pastillas «Jalón», «Tolerancia V» y «Desplazamiento». (RF-7 a RF-11, RF-23)
      Hecho cuando: `SurfaceStakePlateTest` y `SurfaceStakePillsTest` en verde.
- [ ] T8. `app`: `ProjectViewDestination.stakeoutSurfaceId`, «Replantear» en la ficha de Superficies y en la tarjeta del toque, y el mapa que sigue al jalón a 1:500. (RF-1, RF-2, RF-12)
      Hecho cuando: `SurfaceStakeEntryTest` y `SurfaceStakeFollowTest` en verde.
- [ ] T9. `app`: «Replantear superficie» en «Herramientas» de Objetos: con la activa, sin activa (la ficha para elegir) y desactivada sin superficies. (RF-3 a RF-5)
      Hecho cuando: `SurfaceStakeToolsTest` en verde y el test de S059 de «Herramientas» actualizado.
- [ ] T10. `app`: la ficha del punto replanteado contra la superficie. (RF-20)
      Hecho cuando: `SurfaceStakeRefTextTest` en verde.
- [ ] T11. Demo con el receptor virtual sobre una superficie sintética y un ráster; «Cambios al implementar» en `plan.md` y S062 a `implementada` en la hoja de ruta. (Criterios de finalización)
      Hecho cuando: `./gradlew check` en verde, el informe de trazabilidad con los 23 RF de S062 cubiertos y la demo hecha.
