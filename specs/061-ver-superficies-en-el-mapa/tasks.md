# Tareas — Spec 061

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `tools/superficies`: casos de contorno (cuadrado de dos triángulos, malla con un hueco, dos islas, dos anillos que se tocan en un vértice) con el contorno esperado calculado aparte en Python puro, y los colores de referencia de la rampa de siete paradas. (Constitución nº 2, RF-6, RF-10)
      Hecho cuando: el generador escribe los casos en `fixtures/superficies/` y su test de `tools` está en verde.
- [ ] T2. `core:model`: `SurfaceNames.display` y `ElevationRamp`. (RF-1, RF-10)
      Hecho cuando: `SurfaceNamesTest` y `ElevationRampTest` en verde contra los valores de T1.
- [ ] T3. `core:model`: `SurfaceOutline` (anillos exteriores y huecos) y `SurfaceOutlineCodec`, partido en trozos de ≤ 256 kB. (RF-6)
      Hecho cuando: `SurfaceOutlineCodecTest` en verde: ida y vuelta sin pérdida y un contorno que ocupa varios trozos.
- [ ] T4. `core:survey`: `TinOutline.rings` (aristas de borde, encadenado, exterior y huecos, islas y vértices de dos anillos). (RF-6)
      Hecho cuando: `TinOutlineTest` en verde con los casos de T1 y `TinOutlinePerfTest` con 1.000.000 de triángulos en menos de 2 s en la JVM.
- [ ] T5. `core:survey`: `SurfaceViewSelector` para TIN (suma del índice, recuento en la vista, contorno o malla, triángulos sin repetir y color por cota media). (RF-6, RF-7, RF-10)
      Hecho cuando: `SurfaceViewSelectorTest` en verde a los dos lados de 20.000 triángulos y con la suma por encima de 60.000.
- [ ] T6. `core:survey`: `SurfaceViewSelector` para ráster (celdas de la vista por aritmética, recuadro densificado o la imagen de celdas con su color y transparente sin dato). (RF-8, RF-9, RF-10)
      Hecho cuando: `SurfaceViewSelectorRasterTest` en verde a los dos lados de 1.000.000 de celdas y con celdas sin dato.
- [ ] T7. `data`: Room v41 (`surfaces.visible`, `surface_outlines`), su DAO, `AutoMigration(40, 41)` con la `AutoMigrationSpec` y el esquema 41 en `data/schemas/`. (RNF-T6, RF-3, RF-5)
      Hecho cuando: `TopoPhoneDatabaseTest` 40 → 41 en verde: la superficie activa queda visible, las demás ocultas, y nada más cambia.
- [ ] T8. `data`: `SurfaceRepository` con el nombre por defecto al leer, `setVisible`, `outline` (calcula y guarda el que falta), `tilesIn` y `heightAt` de cualquier superficie. (RF-2, RF-3, RF-6, RF-16, RF-18)
      Hecho cuando: `SurfaceRepositoryNameTest`, `SurfaceOutlineRepositoryTest` y `SurfaceHeightAtTest` en verde (TIN y ráster, ≤ 1 mm frente a `ActiveSurface`).
- [ ] T9. `data`: `SurfaceImporter` guarda el nombre de RF-1, el contorno y `visible = 1`; la copia de seguridad conserva `visible` y no lleva `surface_outlines`. (RF-1, RF-4, RF-5)
      Hecho cuando: `SurfaceImporterNameTest` y `SurfaceBackupVisibleTest` en verde.
- [ ] T10. `app`: la pantalla de importación y el resumen con el nombre por defecto; `SurfacesSheet` con el interruptor «Ver en el mapa». (RF-1, RF-3)
      Hecho cuando: `SurfacesSheetVisibilityTest` y el test de la pantalla de importación con `name=""` en verde.
- [ ] T11. `app`: `SurfaceScene`, `MapController.showSurfaces` y su implementación en `MapLibreMapController` (malla, aristas, contorno con la activa más gruesa, imagen del ráster y orden de las capas). (RF-6 a RF-9, RF-12, RF-13)
      Hecho cuando: el test del controlador falso en verde y el mapa del emulador muestra las capas en su orden.
- [ ] T12. `app`: `SurfaceSceneBuilder` al pararse la cámara, con la rampa común de las visibles. (RF-6 a RF-10)
      Hecho cuando: `SurfaceSceneBuilderTest` en verde: contorno de lejos, malla de cerca, ráster y rampa común de dos superficies.
- [ ] T13. `app`: `SurfaceLegend` sobre el mapa. (RF-11)
      Hecho cuando: `SurfaceLegendTest` en verde con las cifras en metros y en pies y la descripción de TalkBack.
- [ ] T14. `app`: «Ir a la superficie» en la ficha. (RF-14, RF-15)
      Hecho cuando: `GoToSurfaceTest` en verde: se hace visible, se encuadra con margen y deja de seguir al jalón.
- [ ] T15. `app`: tocar una superficie: `SurfaceTapResolver` y la tarjeta, después de puntos, líneas y dibujos. (RF-16 a RF-18)
      Hecho cuando: `SurfaceTapTest` en verde: una superficie, dos solapadas, un hueco y un toque fuera de todas.
- [ ] T16. Rendimiento y demo: `SurfaceScenePerfTest` (escena de 20.000 triángulos en menos de 1 s) y demo en el emulador con un LandXML de cerca de 1.000.000 de triángulos sin nombre de superficie y un ráster; «Cambios al implementar» en `plan.md` y S061 a `implementada` en la hoja de ruta. (RNF, criterios de finalización)
      Hecho cuando: `./gradlew check` en verde, el informe de trazabilidad con los 18 RF de S061 cubiertos y la demo hecha.
