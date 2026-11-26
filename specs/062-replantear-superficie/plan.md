# Plan técnico — Spec 062

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/062-replantear-superficie/spec.md`, `specs/042-superficies-landxml/plan.md` (A4 a A7: la superficie activa, la cota en la punta, el nivel y lo que se guarda), `specs/019-replanteo-cota-e-informe/` (el nivel vertical, la tolerancia vertical y el informe), `specs/060-recolectar-y-replantear-en-campo/` (la hoja de campo, la placa y las pastillas) y `specs/061-ver-superficies-en-el-mapa/plan.md` (la ficha, la tarjeta del toque y la visibilidad). Sin código.

Sin dependencias nuevas (Gradle ni Python).

## Estructura de módulos
- `core:model`:
  - `StakeSource.Surface(surfaceName)`: el origen de un punto replanteado contra la superficie, sin punto de diseño (RF-16, RF-17, RF-21);
  - `StakeoutNames.nextSurface(taken)`: `SUP` con el primer número libre del proyecto (RF-16).
- `core:survey`:
  - `StakeoutDiff.surfaceRef(...)`: el punto replanteado contra la superficie: diseño en la posición medida con la cota de diseño, diferencias en planta a cero, diferencia de cota medida menos diseño y el veredicto solo vertical (RF-17, RF-18).
- `core:formats`:
  - `StakeoutReportCsv`: las filas de origen superficie dejan vacías las columnas de planta (RF-21).
- `data`:
  - Room v42: `points.stake_surface_only INTEGER NULL` con `AutoMigration(41, 42)`; `PointEntity` ↔ `StakeSource.Surface` (RNF-T6).
- `app`:
  - `StakeoutController`: el modo «solo superficie» del `StakeContext` (A1; RF-7 a RF-9, RF-13);
  - `StakeoutViewModel.openSurface(projectId, surfaceId)`, las pastillas y el desplazamiento (A2; RF-6, RF-10, RF-11, RF-22, RF-23);
  - `SurfaceStakePlate`: la placa sin objetivo en planta (RF-7 a RF-9, RF-13);
  - el recolector: el nombre SUPn, el guardado y el bloqueo fuera de la superficie (A3; RF-14 a RF-19);
  - las entradas: la ficha de Superficies, la tarjeta del toque y «Herramientas» de Objetos (A4; RF-1 a RF-6);
  - la ficha del punto y el informe (RF-20, RF-21);
  - el mapa sigue al jalón con la escala de la malla (RF-12).

## Modelo de datos

### Room v42
```
points  + stake_surface_only INTEGER NULL   (1 en un punto replanteado contra la superficie, S062; NULL en los demás)
```
- `AutoMigration(41, 42)` sin especificación: una columna que admite NULL. Los puntos que ya hay no cambian (RNF-T6).
- Un punto de origen superficie usa las columnas que ya existen: `stake_design_id` (su propio id: cada medida es su propio «diseño»), `stake_design_name` (el nombre de la superficie), `stake_design_*` (la posición medida con la cota de diseño), `stake_d_east = stake_d_north = stake_d_horizontal = 0`, `stake_d_height`, `stake_design_elevation`, `stake_tol_v`, `stake_tol_h = NULL`, `stake_within`, y las de S042 (`stake_elev_source = SURFACE`, `stake_surface_name`, `stake_surface_imported_ms`, `stake_surface_elev`, `stake_vertical_offset`).
- Ejemplo (proyecto en metros, desplazamiento +0,10, tolerancia vertical 0,03):
```
name = "SUP3", stake_surface_only = 1, stake_design_name = "explanacion", stake_design_east = 440123.456, stake_design_north = 4474321.987,
stake_surface_elev = 612.340, stake_vertical_offset = 0.100, stake_design_elevation = 612.440, stake_d_height = -0.052 (terraplén),
stake_tol_v = 0.030, stake_tol_h = NULL, stake_within = 0
```

## Algoritmos

### A1. La guía solo en cota (RF-7 a RF-9, RF-13)
- `StakeContext` gana `surfaceOnly: Boolean`. Con él, en `StakeoutController.onEpoch`:
  1. La punta sale como siempre (`LiveTip`, inclinación, jalón, base shift, localización): los mismos bloqueos de solución e inclinación (RF-15).
  2. El objetivo es la propia punta: `design = tip` con la cota `superficie(punta) + desplazamiento` (la misma regla y el mismo `SurfaceLevelKey` que S042 A5, así que el nivel no se reinicia en cada época).
  3. No hay zonas ni avisos horizontales (no se llama a `StakeoutZones` y la diana no se dibuja); el nivel vertical, sus colores, sus avisos y sus sonidos son los de S019 (RF-9).
  4. Fuera de la superficie, `LevelUnavailable.OUTSIDE_SURFACE` como en S042 (RF-13).
- `GuidanceUi` gana `surfaceOnly`; con él la placa no muestra flecha, distancia ni desplazamientos (RF-7).

### A2. Abrir el replanteo (RF-1 a RF-6, RF-10 a RF-12, RF-22, RF-23)
- `StakeoutViewModel.openSurface(projectId, surfaceId)`: si la superficie no es la activa, la activa (`SurfaceRepository.activate`) y la hace visible (`setVisible`), y después crea un `StakeTarget(id = "surface:<id>", name = <nombre>, source = StakeSource.Surface(<nombre>))` con `surfaceOnly`. El `StakeSaveInfo` lleva el origen `SURFACE`, la superficie y la tolerancia vertical.
- `ProjectViewDestination` gana `stakeoutSurfaceId`, como `stakeoutLineId` (S027). «Replantear superficie» de «Herramientas» navega con la activa o, sin activa, con `openPanel = surfaces` (la ficha para elegir, RF-4); la fila está desactivada sin superficies, con el motivo (RF-5).
- La ficha de Superficies y la tarjeta del toque (S061) ganan «Replantear», que llama a `openSurface` en la vista del proyecto (RF-1, RF-2).
- Pastillas (RF-10): «Jalón», «Tolerancia V» y «Desplazamiento» (en las unidades del proyecto; teclado numérico, como las de S060). La de «Desplazamiento» escribe en `SurfaceRepository.setVerticalOffset` (RF-11) y, como la tolerancia, está desactivada con una medida en marcha (RF-23).
- El mapa (RF-12): `StakeoutFollow` (S060/RF-25) sin destino centra en el jalón con el norte arriba y la escala de 1:500, a la que se ve la malla de S061; un gesto lo deja libre (S060/RF-26).
- Cambios con el replanteo abierto (RF-22): el `ViewModel` observa la superficie activa; si deja de ser la del replanteo (desactivada, borrada u otra activa), cierra el replanteo y publica el motivo.

### A3. Medir y guardar (RF-14 a RF-19)
- El recolector recibe el `StakeTarget` de origen superficie y propone `StakeoutNames.nextSurface(taken)` (RF-16); tras guardar, propone el siguiente (RF-19).
- «Medir» se bloquea con «Fuera de la superficie» mientras la guía dice `OUTSIDE_SURFACE` (RF-14, S060/RF-16), además de los bloqueos de siempre (RF-15).
- Al terminar la medida: `SurfaceStake.of(info, medido)` da la cota de la superficie en la posición **medida** (no en la última época), y `StakeoutDiff.surfaceRef` construye el `StakeoutRef`: diseño = posición medida con la cota de diseño, diferencias en planta 0, `dHeight = medido − diseño`, `toleranceHM = null`, `withinTolerance = |dHeight| ≤ tolV` (RF-17, RF-18). Si en ese instante la punta medida cayera fuera (el bloqueo se adelantó una época), no se guarda y se avisa: nunca un SUP sin cota de la superficie.
- La confirmación da el nombre y «Desmonte», «Terraplén» o «En cota» (RF-19, S019/RF-15).

### A4. Ficha e informe (RF-20, RF-21)
- `StakeoutRefText` con origen superficie no muestra el punto de diseño ni las diferencias en planta; sí lo de S042/RF-30 (RF-20).
- `StakeoutReportCsv`: con origen superficie, `nombre del punto de diseño` = la superficie, `número de replanteo` vacío, y vacías las columnas de Este y Norte de diseño, diferencias de Norte y Este, distancia horizontal y tolerancia horizontal (RF-21, S019/RF-21).

## Contratos
- `StakeoutViewModel.openSurface(projectId: String, surfaceId: String)`.
- `StakeoutDiff.surfaceRef(pointId, surfaceName, measured, designElevationM, toleranceVM): StakeoutRef`.
- `StakeoutNames.nextSurface(taken: Set<String>): String`.
- Textos nuevos: «Replantear» (ficha y tarjeta), «Replantear superficie» y su descripción en «Herramientas», «Este proyecto no tiene superficies», «Desplazamiento» (pastilla), el aviso de RF-22 y el título de la placa (el nombre de la superficie).

## Decisiones técnicas
| Decisión | Por qué | Alternativa descartada |
|---|---|---|
| Reutilizar `StakeoutController` con `surfaceOnly` | La punta, los bloqueos, el nivel, los avisos y los sonidos son los de S018, S019 y S042 | Un controlador nuevo que repetiría todo eso |
| El objetivo es la punta | No hay planta: la distancia es 0 y la placa la oculta | Un objetivo fijo en el centro de la superficie |
| `stake_surface_only` en `points` | Un punto de origen superficie se distingue sin inventar un id de diseño | Codificarlo en `stake_design_id` (frágil) |
| Diseño = posición medida, planta a 0 | El informe y la ficha no tienen planta que mostrar y las columnas de S018 siguen siendo NOT NULL en el modelo | Hacer anulables `dEast`, `dNorth`, `dHorizontal` (toca todo S018 y S019) |
| Cota de la superficie en la posición medida | Es la del sitio donde se estaquilla, como S042/RF-28 | La de la última época de la guía |
| `ProjectViewDestination.stakeoutSurfaceId` | El mismo camino que el replanteo de una línea (S027) | Un `ViewModel` compartido entre pantallas |

## Estrategia de tests
- JVM (`core:model`, `core:survey`, `core:formats`): `nextSurface` (vacío, huecos, otros nombres); `surfaceRef` (desmonte, terraplén, en cota, sin tolerancia horizontal) frente a un cálculo aparte; el CSV con una fila de superficie (columnas de planta vacías) con los dorados regenerados.
- Robolectric (`data`): migración 41 → 42; un punto de origen superficie vuelve igual de la base y de una copia.
- Robolectric (`app`): `StakeoutController` con `surfaceOnly` (sin zonas ni avisos horizontales, nivel con el desplazamiento, fuera de la superficie); `StakeoutViewModel.openSurface` (activa y hace visible la superficie, pastillas, desplazamiento, cierre por RF-22); el recolector (SUP1, SUP2, bloqueo fuera, lo guardado frente a la cota del plano en la posición medida ≤ 1 mm); las entradas (ficha, tarjeta, «Herramientas» con y sin activa, desactivada sin superficies); la placa y la ficha del punto.
- Manual: demo con el receptor virtual sobre una superficie sintética y un ráster: desmonte, terraplén y «En cota» al mover la punta, salir de la superficie y ver el bloqueo, medir SUP1 y SUP2 y verlos en la ficha y en el CSV.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1, RF-2 | A2 | `SurfaceStakeEntryTest` |
| RF-3 a RF-5 | A2 | `SurfaceStakeToolsTest` |
| RF-6 | A2 | `SurfaceStakeOpenTest` |
| RF-7 a RF-9 | A1 | `SurfaceOnlyControllerTest`, `SurfaceStakePlateTest` |
| RF-10, RF-11 | A2 | `SurfaceStakePillsTest` |
| RF-12 | A2 | `SurfaceStakeFollowTest` |
| RF-13, RF-14 | A1, A3 | `SurfaceOnlyControllerTest`, `SurfaceStakeCollectorTest` |
| RF-15 | A1 | `SurfaceOnlyControllerTest` |
| RF-16 a RF-19 | A3 | `StakeoutNamesSurfaceTest`, `SurfaceRefTest`, `SurfaceStakeCollectorTest` |
| RF-20 | A4 | `SurfaceStakeRefTextTest` |
| RF-21 | A4 | `StakeoutReportCsvSurfaceTest` |
| RF-22 | A2 | `SurfaceStakeOpenTest` |
| RF-23 | A2 | `SurfaceStakePillsTest` |

## Cambios al implementar (S062)
- El mapa (RF-12) sigue al jalón con la escala por defecto del seguimiento de S060 (20 m de lado corto visible, en torno a 1:300) en lugar de 1:500: a esa escala la malla de S061 se ve, y no hace falta otra regla de cámara. `StakeoutMarkers` no da destino en el replanteo de la superficie, para que el mapa nunca vaya a las coordenadas de relleno del objetivo.
- Abrir el replanteo necesita dejar la superficie activa y visible: `SurfaceStakeStore` (`data`) lo hace con el repositorio, porque Hilt no puede dar un parámetro anulable al `StakeoutViewModel` (T5).
- El bloqueo de RF-14 es un `MeasureBlocker.OUTSIDE_SURFACE` sin acción (`ReasonAction.NONE`): solo se resuelve volviendo a la superficie. Si la medida termina fuera (el bloqueo va una época por detrás), no se guarda y sale `CollectorError.OUTSIDE_SURFACE` (T6).
- El resumen del informe no cuenta los SUP en las desviaciones horizontales (no tienen planta) y los ordena por su nombre (T3).
- La placa y las pastillas van en `SurfaceStakePanel`, que reutiliza la hoja de campo, el nivel y los diálogos numéricos de S060; el desplazamiento se escribe con `SurfacesViewModel.setOffset` de S042 (T7, T8).
- `GroupMenu` admite filas desactivadas con su motivo (RF-5), y «Herramientas» pasa a seis filas (T9).
- Demo (T11) en el emulador con el receptor virtual sobre una superficie sintética: «Replantear» desde la ficha, placa con desmonte y cotas, el mapa sobre la malla, «Medir SUP1» (bloqueado hasta elegir el modo de inclinación, como cualquier medida) y la ficha de SUP1 con la superficie, la cota, la diferencia y solo la tolerancia vertical.
