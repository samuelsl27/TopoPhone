# Plan técnico — Spec 020

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md` (orden de cálculo: bruto → punta del jalón → shift → SC → localización), `specs/020-base-shift/spec.md`, `specs/010-recolectar-puntos/` y `specs/011-objetos-y-puntos-manuales/` (`PointMeasurement`, `PointCorrections`, las coordenadas escritas), `specs/015-estado-receptor-detallado/` (`BaseTracker`, RF-17), `specs/018-replanteo-puntos/`, `specs/046-antena-y-jalon/`, `specs/047-tipos-de-solucion/`, `specs/008-coordenadas-espana/` (`ProjectPositionCalculator`, `Ecef`) y `specs/045-raster-mdt/` (el patrón de pantalla, Room y copia). Sin código.

Sin dependencias nuevas, ni Gradle ni Python:
- ECEF ya existe en `core:geodesy` (`Ecef`, `GeodeticPoint`, `LocalTangentPlane`); solo se le añaden la suma y la resta de vectores. Los valores esperados se calculan con `pyproj` (ya es dependencia de `tools`) en `tools/base_shift/`. Alternativa descartada: una librería de álgebra de vectores (tres sumas).

## Decisiones que pide esta spec (para tu aprobación)
| # | Decisión | Propuesta | Alternativa |
|---|---|---|---|
| D1 | Dónde se guardan los desplazamientos (RF-12, RF-8) | **Room**: tabla `base_shifts` (la lista del proyecto, con un `active`) y columnas nuevas en `points`. Entran solos en la copia, los paquetes y la papelera (RNF-T6). **Room v23** con `AutoMigration(22, 23)` (S017b pasa a la v24) | DataStore: no entra en las copias ni en los paquetes del proyecto |
| D2 | Qué coordenadas lleva el punto con desplazamiento (RF-8, RF-9) | Las coordenadas del punto (`ground_*`, `easting`…, y `measured_*`) son las **desplazadas**, que es lo que se ve, se replantea y se exporta. Se guardan aparte la **punta sin desplazar** (`unshifted_ground_*`), el vector (`shift_dx/dy/dz`) y el punto conocido (`shift_id`, `shift_name`). La antena (`antenna_*`) sigue bruta | Guardar las coordenadas brutas y desplazar al leer: cada lectura y cada exportación tendría que conocer el vector |
| D3 | Un solo punto de aplicación (RF-7) | El vector se pasa a `LiveTip.of`, `PointMeasurement` y `StakeoutContext` y se suma **a la punta ya calculada, antes de `ProjectPositionCalculator.fromGround`** (`BaseShiftApplier.apply(ground, vector)`: geográficas → ECEF → + Δ → geográficas). Los seis sitios que calculan hoy suelo y SC reciben el mismo parámetro | Aplicarlo después del SC (suma en E/N/h): no sería ECEF y no coincidiría con el orden de `arquitectura.md` |
| D4 | Medir el punto conocido (RF-2) | Pantalla propia `BaseShift*` con su propio `PointMeasurement` (duración de 1 s a 60 min y tipos admitidos de las preferencias del recolector, jalón activo de S046), **sin vector aplicado** aunque haya uno activo y sin guardar nada. Alternativa descartada: un «modo» dentro del recolector (el `CollectorViewModel` ya es muy grande y guardaría un punto) | Un modo del recolector |
| D5 | Cuándo se desactiva por cambio de datum (RF-16) | **Al guardar el proyecto** con otro datum de correcciones, en la misma transacción (`BaseShiftRepository.deactivateForDatumChange`). Cada desplazamiento guarda el datum con que se calculó, por si el cambio llega por una copia restaurada | Comprobarlo al leer |
| D6 | Columnas en las exportaciones (RF-13) | ΔX, ΔY y ΔZ al final de «Todas las columnas» del CSV y **también en el GeoPackage de S053**, que repite esas columnas (`GpkgPointAttributes`). Los CSV personalizados las ofrecen | Solo el CSV: el GeoPackage dejaría de seguir a «Todas las columnas» |
| D7 | Dónde está la entrada «Base shift» (RF-1) | En el mismo menú del proyecto que «Importar ráster» (`ObjectsMenu`); la lista de desplazamientos y sus acciones, dentro de la misma pantalla | En el menú del receptor |
| D8 | Punto conocido (RF-1) | Se elige un punto del proyecto o se escriben E, N y altura con el mismo componente de S011/RF-1; la altura es en el sistema de alturas del proyecto y puede quedar vacía (RF-6) | Solo puntos del proyecto |
| D9 | Desplazamiento horizontal (RF-6) | Se calcula en el plano local del punto medido: la diferencia en Este y Norte, con la componente Up a cero, y se vuelve a ECEF. El módulo de RF-5 es el de ese vector | Poner ΔZ a cero (no es horizontal) |
| D10 | Fixtures (criterios de finalización) | `tools/base_shift/` (puro Python con `pyproj`) con ETRS89, REGCAN95 y correcciones ITRF; el caso ITRF usa el 0,9 m de `arquitectura.md` | Sin caso ITRF |

## Estructura de módulos
- `core:geodesy`: `Ecef.plus/minus(Ecef)` y `Ecef.length` (RF-3; lo demás ya existe).
- `core:model`
  - `point/BaseShiftVector` (ΔX, ΔY, ΔZ en metros), `point/BaseShift` (id, nombre del punto conocido, coordenadas conocidas y su altura opcional, posición medida, vector, solo horizontal, datum de correcciones, base en el momento, `fromReplay`, `active`, fecha) y `BaseShiftLimits` (10 y 1.000 m) (RF-4 a RF-6, RF-12).
  - `Measurement` gana `shift: AppliedShift?` (vector, `shift_id`, nombre y punta sin desplazar) (RF-8, RF-13).
- `core:survey` (sin Android ni Hilt)
  - `BaseShiftCalculator`: del punto conocido (E, N, h del proyecto) a ECEF en el datum del SC (inversa de `ProjectPositionCalculator` ya usada por los puntos manuales), de la posición medida a ECEF, el vector, su equivalente ENU en el SC, su módulo, el caso sin altura (D9) y la clasificación `Ok / NeedsConfirmation / Rejected` (RF-3 a RF-6).
  - `BaseShiftApplier.apply(ground, vector)` (D3, RF-7).
  - `LiveTip.of`, `PointMeasurement` (`finish`, `use`) y `PointCorrections.withPoleHeight` reciben el vector; `MeasurementResult` devuelve la punta sin desplazar. `PointCorrections` usa **el vector del punto**, no el activo (RF-10).
- `core:formats`: `ExportColumn.SHIFT_DX/DY/DZ` al final de «Todas las columnas» y `GpkgPointAttributes` (D6, RF-13).
- `data`
  - Room v23: tabla `base_shifts`, columnas nuevas en `points`, `AutoMigration(22, 23)` (D1).
  - `BaseShiftRepository`: `create`, `activate` (desactiva la anterior en la misma transacción), `deactivate`, `delete`, `list`, `deactivateForDatumChange` (RF-12, RF-16).
  - `ActiveBaseShift`: el vector activo del proyecto como `StateFlow`, con la lectura instantánea que usa una medida al empezar (RF-9).
  - Guardado del punto con el vector (RF-8) y `DatabaseSnapshot`/`BackupRepository` con `base_shifts` (`Table("base_shifts", "WHERE project_id = ?")` y `copyBaseShifts` con ids nuevos remapeados desde `points.shift_id`).
- `app`
  - `ui/baseshift/`: `BaseShiftViewModel`, `BaseShiftScreen`, `BaseShiftRoute` (RF-1 a RF-6, RF-12); `SurveyPage.BaseShift(projectId)` y su entrada en `ObjectsMenu`.
  - Recolector (tres sitios: en vivo, medida final y automático), replanteo (`StakeoutController`, `LineStakeoutViewModel`, `AxisStakeoutViewModel`) y recálculo del jalón (`PointDetailsScreen`, `ObjectListScreen`): pasan el vector; el aviso «Base shift activo» con su botón de desactivar en el panel del recolector y en el replanteo (RF-7, RF-9, RF-11).
  - `PointDetailsScreen`: tarjeta «Base shift» (RF-13).
  - Avisos de cambio de base (observa `received.base.change?.count`, como `CollectorViewModel`) y de datum (RF-15, RF-16).
- `tools/base_shift`: `generar_base_shift.py` y `fixtures/base_shift/` con lo esperado calculado aparte (constitución nº 2).

## Modelo de datos
### Room v23
```
base_shifts   id TEXT PK, project_id TEXT FK(projects, CASCADE), created_at INTEGER, from_replay INTEGER,
              known_name TEXT, known_point_id TEXT NULL, known_easting REAL, known_northing REAL, known_height REAL NULL,
              measured_lat REAL, measured_lon REAL, measured_h REAL, epochs_used INTEGER,
              dx REAL, dy REAL, dz REAL, horizontal_only INTEGER, corrections_datum TEXT, active INTEGER,
              base_lat REAL NULL, base_lon REAL NULL, base_h REAL NULL
points      + shift_id TEXT NULL, shift_name TEXT NULL, shift_dx REAL NULL, shift_dy REAL NULL, shift_dz REAL NULL,
              unshifted_ground_lat REAL NULL, unshifted_ground_lon REAL NULL, unshifted_ground_h REAL NULL
```
- `AutoMigration(22, 23)` sin especificación: una tabla nueva y columnas anulables; los puntos existentes quedan «Sin base shift» (RNF-T6). Esquema 23 en `data/schemas/`.
- Un índice por `project_id`. Un solo `active = 1` por proyecto lo garantiza la transacción de `activate`; el test lo comprueba.
- `shift_id` no es una clave foránea: borrar un desplazamiento no toca el punto, que conserva su vector, su nombre y su punta sin desplazar (RF-9, constitución nº 1).
- El vector y las coordenadas se guardan en `Double`, sin redondear.

## Algoritmos
### A1. Calcular el desplazamiento (RF-2 a RF-6; D4, D8, D9)
1. Medida: `PointMeasurement` con la duración elegida, los tipos admitidos (S047) y el jalón activo (S046), **sin vector**. El resultado es la punta (`ground`: φ, λ, h elipsoidal, en el datum de las correcciones). No se guarda nada.
2. Conocido: de E, N, h del proyecto a φ, λ, h con la inversa de `ProjectPositionCalculator` (ED50 y UTM inversos, geoide si la altura es ortométrica). Es ETRS89/REGCAN95/ED50 según el proyecto.
3. Ambos a ECEF con GRS80; **sin transformar entre datums** (RF-3).
4. Con altura: Δ = conocido − medido. Sin altura y con el «solo horizontal» aceptado: ΔENU = (Este y Norte del conocido menos los del medido, tomados en el plano local del medido; Up = 0) y se vuelve a ECEF con `LocalTangentPlane` (D9).
5. Equivalente en el SC: (E, N, h de la punta medida con Δ aplicado) menos (E, N, h de la punta medida sin Δ), ambas proyectadas sin transformar entre datums. Módulo: `‖Δ‖`.
6. Clasificación: `‖Δ‖ ≤ 10 m` → `Ok`; `10 < ‖Δ‖ ≤ 1.000 m` → `NeedsConfirmation` (segunda confirmación aparte); `> 1.000 m` → `Rejected` (RF-5).

### A2. Aplicar (RF-7, RF-9, RF-14; D3)
- `BaseShiftApplier.apply(ground, v)`: `Ecef.fromGeodetic(ground)` + `v` → `toGeodetic` (GRS80). Después, `fromGround` como siempre. Cuesta una conversión directa y una inversa por época (microsegundos; RNF 1 ms).
- La medida lee el vector activo **una vez al empezar** y lo conserva hasta el fin: desactivar a mitad no cambia esa medida (RF-9). Lo mismo para el promediado automático y el replanteo en vivo, que lo leen en cada época desde `ActiveBaseShift`.
- Un punto de reproducción lleva `from_replay` como siempre (S010/RF-18); el desplazamiento calculado en reproducción también lo lleva (RF-14).

### A3. Guardar (RF-8, RF-10, RF-13)
- El punto guarda las coordenadas desplazadas y, en la misma transacción, `unshifted_ground_*` y las cinco columnas de desplazamiento (D2). Sin desplazamiento activo, todas `NULL` y las columnas ΔX/ΔY/ΔZ del CSV salen vacías.
- `PointCorrections.withPoleHeight` recalcula la punta desde la antena y la inclinación guardadas, suma `shift_dx/dy/dz` **del punto** y proyecta (RF-10); `revertToMeasurement` vuelve a las `measured_*`, que son las desplazadas de cuando se midió.

### A4. Avisos (RF-11, RF-15, RF-16)
- RF-11: `ActiveBaseShift` alimenta el panel del recolector y el del replanteo con «Base shift activo» y un botón «Desactivar».
- RF-15: la primera vez que `received.base.change.count` sube con un desplazamiento activo, aviso con «Desactivar» y «Repetir» (abre la pantalla de A1). No se repite por el mismo cambio (como `BaseNotices`).
- RF-16: D5. El mismo mensaje se muestra la siguiente vez que se abre el recolector o el replanteo.

## Contratos
- `BaseShiftCalculator.compute(known, measured, projectCrs, heightSystem, horizontalOnly): BaseShiftResult` (vector, ENU, módulo, `Classification`).
- `BaseShiftApplier.apply(ground: GeodeticPosition, shift: BaseShiftVector?): GeodeticPosition`.
- `BaseShiftRepository` y `ActiveBaseShift.current(projectId): BaseShiftVector?`.
- Rechazos de la pantalla (`BaseShiftFailure`): `TOO_FAR` (> 1.000 m), `NO_FIX_ADMITTED`, `KNOWN_POINT_MISSING`, `MEASUREMENT_FAILED`.

### Textos nuevos (`strings.xml`, es y en)
Base shift, elegir el punto conocido, medir, los resultados (ΔX, ΔY, ΔZ, Este, Norte, altura, módulo), los dos avisos de módulo, «solo horizontal», «Base shift activo», «Desactivar», «Reactivar», «Borrar», «Sin base shift», la ficha, los avisos de base y de datum, y las frases de TalkBack. Sin apóstrofos sin escapar en inglés.

### Accesibilidad (RNF-T8)
Campos y botones de 56 dp; los avisos de módulo y el de «Base shift activo» en texto además del color; TalkBack lee el resultado al terminar de medir.

## Decisiones técnicas
| Decisión | Por qué | Alternativa descartada |
|---|---|---|
| Vector en ECEF, sumado a la punta antes del SC | Es el orden de `arquitectura.md` y absorbe el cambio de datum (RF-3) | Sumar en E/N/h |
| Coordenadas del punto desplazadas, brutas aparte | Todo lo que lee el punto ve lo que se replantea y exporta | Coordenadas brutas y desplazar al leer |
| Medida propia sin guardar | No contamina la lista de puntos (decisión de la entrevista) | Modo del recolector |
| `shift_id` sin clave foránea | Borrar el cálculo no modifica puntos (RF-9) | Clave foránea con `SET NULL` |

## Estrategia de tests
- **`tools`:** `generar_base_shift.py` y su test: para ETRS89, REGCAN95 y correcciones ITRF, un conocido y una medida, y el vector, su ENU y las coordenadas del SC de dos puntos nuevos tras aplicarlo, con `pyproj`; el caso sin altura; los módulos de 9,99, 10,01, 999,9 y 1.000,1 m.
- **JVM `core:geodesy` / `core:model`:** `EcefTest` ampliado, `BaseShiftTest` (valores, límites).
- **JVM `core:survey`:** `BaseShiftCalculatorTest` y `BaseShiftApplierTest` frente a `fixtures/base_shift/` a menos de 1 mm y la ida y vuelta a menos de 0,1 mm; `PointMeasurementShiftTest` (vector fijo durante la medida), `PointCorrectionsShiftTest` (RF-10), `LiveTipShiftTest`.
- **JVM `core:formats`:** `CsvExportTest` y `GpkgExportTest` ampliados (columnas, vacías sin desplazamiento).
- **`data`** (nombres de 60 caracteres o menos): `TopoPhoneDatabaseTest` 22 → 23 con un proyecto con puntos; `BaseShiftRepositoryTest` (uno activo, desactivar, reactivar, borrar sin tocar puntos, datum); `BaseShiftPointSaveTest`; `BaseShiftBackupTest`.
- **Robolectric (`app`):** `BaseShiftViewModelTest` y `BaseShiftScreenTest` (pasos, módulos, confirmaciones, sin altura, lista); `CollectorBaseShiftTest`, `StakeoutBaseShiftTest`, `PointDetailsShiftTest`; avisos de base y datum.
- **Rendimiento:** `BaseShiftPerformanceTest`: aplicar el vector no añade más de 1 ms por época (RNF).
- **Reproducción sintética:** `rs3-base-shift`: medir un conocido, activar, medir dos puntos, desactivar, con el resultado esperado calculado aparte.
- **Manual o de campo:** demo en el emulador (criterios de finalización); en campo, base en SINGLE y un vértice conocido, que hace el usuario.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 | A1, D7, D8 | `BaseShiftViewModelTest`, `BaseShiftScreenTest` |
| RF-2 | A1, D4 | `BaseShiftViewModelTest` |
| RF-3 | A1 | `BaseShiftCalculatorTest` |
| RF-4 | A1 | `BaseShiftCalculatorTest`, `BaseShiftScreenTest` |
| RF-5 | A1 | `BaseShiftCalculatorTest`, `BaseShiftViewModelTest` |
| RF-6 | A1, D9 | `BaseShiftCalculatorTest`, `BaseShiftViewModelTest` |
| RF-7 | A2, D3 | `BaseShiftApplierTest`, `LiveTipShiftTest`, `CollectorBaseShiftTest`, `StakeoutBaseShiftTest` |
| RF-8 | A3, D2 | `BaseShiftPointSaveTest` |
| RF-9 | A2, A3 | `PointMeasurementShiftTest`, `BaseShiftRepositoryTest` |
| RF-10 | A3 | `PointCorrectionsShiftTest` |
| RF-11 | A4 | `CollectorBaseShiftTest`, `StakeoutBaseShiftTest` |
| RF-12 | `BaseShiftRepository` | `BaseShiftRepositoryTest`, `BaseShiftScreenTest` |
| RF-13 | A3, D6 | `CsvExportTest`, `GpkgExportTest`, `PointDetailsShiftTest` |
| RF-14 | A2 | `CollectorBaseShiftTest` |
| RF-15 | A4 | `BaseShiftNoticeTest` |
| RF-16 | A4, D5 | `BaseShiftRepositoryTest`, `BaseShiftNoticeTest` |

## Pendiente para otras specs
- S017b (fotos) pasa a la versión 24 de la base de datos (`AutoMigration(23, 24)`); su `plan.md` solo se corrige con permiso para tocar `specs/`.
- S039 (localización) usa este orden de cálculo: el vector se aplica antes del SC y la localización después.

## Cambios en documentos anteriores
- `docs/hoja-de-ruta.md`: la fila S020 ya está en `spec`; pasa a «implementada» al final y S017b pasa a la v24.
- `docs/glosario.md`: «Punto conocido» y «Desplazamiento activo».
- S012 (`ExportColumn`), S053 (columnas del GeoPackage), S011 (recálculo del jalón con el vector del punto) y S010/S018 (aviso «Base shift activo») cambian en el código; **sus `spec.md` no se tocan sin que lo pidas**.

## Cambios al implementar (S020)
- `base_shifts` gana la columna `datum_notice` (aviso de que un cambio de datum lo desactivó y el usuario aún no lo ha visto): RF-16 pide «indicarlo» y hacía falta guardarlo. Reactivar un cálculo hecho con otro datum se rechaza (`ActivateResult.DATUM_MISMATCH`); no estaba en la spec.
- D5 se cumple en `RoomProjectRepository.update`, en la misma transacción que guarda el proyecto (`BaseShiftDao.deactivateOtherDatum`).
- `ActiveBaseShift` es el único punto de acceso del recolector, los replanteos, el análisis de eje y el mapa (`track`, `datumNotice`, `deactivate`, `NONE`); la lectura instantánea de RF-9 la hace `PointMeasurement` al construirse (recibe el vector), no `ActiveBaseShift.current`.
- «Base shift activo» (RF-11) y los avisos de RF-15 y RF-16 viven en `Messages` de `CollectorPanel`, que ya comparten el recolector y los paneles de replanteo de puntos, de CAD, de eje y el análisis de eje. El aviso de RF-15 aparece cuando cambia el número de cambios de base (`BaseState.change.count`) con un desplazamiento activo; un cambio anterior a abrir el recolector no cuenta. «Repetir» abre la pantalla de Base shift.
- RF-7 «las coordenadas, el mapa y el replanteo en vivo»: el mapa y las coordenadas del proyecto (`ProjectViewModel`) y los tres replanteos llevan el vector; la pantalla de Receptor no, porque no tiene proyecto y su desplazamiento es por proyecto.
- Sin SC UTM (geográficas) el equivalente en el SC de RF-4 se calcula en el plano local de la medida. Las coordenadas escritas del punto conocido se interpretan como latitud y longitud en grados decimales.
- ΔX, ΔY y ΔZ van también en el GeoPackage (D6); los dorados del CSV (`fixtures/formatos/csv/`) y del GeoPackage (`fixtures/formatos/gpkg-exportado/`) se regeneraron con `TOPOPHONE_REGENERAR_DORADOS=1`.
- Las pruebas de rendimiento (`BaseShiftPerformanceTest`) miden aplicar el vector: menos de 1 ms en el peor caso.
- Nombres de test de `CadBackupTest` y `GearBackupTest` acortados: con más de unos 100 caracteres fallaban casi siempre con `SQLITE_CANTOPEN` en Windows.
