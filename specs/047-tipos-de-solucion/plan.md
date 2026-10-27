# Plan técnico — Spec 047

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/047-tipos-de-solucion/spec.md`. Sin código.

## Estructura de módulos
- `core:model`
  - `SolutionType` (`FIX`, `FLOAT`, `SINGLE`, de mejor a peor) y `AdmittedSolutions`: el conjunto de tipos admitidos, nunca vacío (RF-2, RF-3).
  - `PrecisionControl.fixOnly: Boolean` pasa a `admitted: AdmittedSolutions` (por defecto solo FIX). `check` recibe el `SolutionType?` de la medida en lugar de `isFix` (RF-7). `PrecisionFailure.NOT_FIX` conserva su nombre (ahora significa «tipo de solución no admitido»), porque se guarda en `out_of_tolerance` y se escribe así en el CSV (S012).
  - `PointQuality` gana `admitted: AdmittedSolutions?` y `epochsDiscardedByType: Int?` (RF-15); `solution` se conserva tal como está.
- `core:survey`
  - `SolutionTypes.of(SolutionStatus): SolutionType?` (RF-18: `DGPS` → `SINGLE`; `ESTIMATED` y `NONE` → null, nunca se admiten). Vive aquí porque `core:model` no conoce `SolutionStatus` (de `core:gnss`).
  - `PointMeasurement` recibe `admitted` en lugar de `fixOnly` y lleva la regla del mejor tipo (RF-8 a RF-10, A1).
  - `MeasurementUpdate.SolutionLost` pasa a `SolutionDegraded`; `MeasurementResult` gana `admitted` y `epochsDiscardedByType`.
- `data`
  - `CollectorPreferences` guarda `collector_admitted_solutions` y lee la clave antigua `collector_fix_only` (RF-5, RF-17).
  - Room v17: dos columnas nuevas en `points` (RF-15).
- `app`
  - Diálogo del control de precisión con tres casillas y la confirmación de RF-4 (RF-1, RF-3, RF-4).
  - `CollectorViewModel`, `StakeoutViewModel`, `LineStakeoutViewModel` y `StakeoutController` usan `admitted` (RF-6, RF-7, RF-11 a RF-13).
  - Indicador «no solo FIX» en el recolector y el replanteo (RF-14) y los tipos en la ficha del punto (RF-16).
- `tools/nmea`: `generar_tipos_mezclados.py`, una grabación sintética con FIX, FLOAT y SINGLE alternados y el resultado esperado de cada combinación de tipos, calculado aparte.

## Modelo de datos
### Preferencias (RF-5, RF-17)
`collector_admitted_solutions` (cadena, p. ej. `FIX,FLOAT`) en el DataStore del recolector, para todos los proyectos y receptores. Lectura:
1. Si existe la clave nueva, se usa (los valores desconocidos se ignoran; si no queda ninguno, solo FIX).
2. Si no, y existe `collector_fix_only`: `true` → `{FIX}`, `false` → `{FIX, FLOAT, SINGLE}`.
3. Si no existe ninguna: `{FIX}`.
Al guardar se escribe la clave nueva y se borra la antigua, en una sola `edit` (atómica, RNF-T6). Leer sin reescribir mantiene la actualización sin efectos y sin confirmación (RF-17).

### Room v17
`AutoMigration(16, 17)`: dos columnas nulas en `points`.

| Columna | Tipo | Notas |
|---|---|---|
| `admitted_solutions` | TEXT, nula | Los tipos admitidos al medir, separados por comas (`FIX,FLOAT`) (RF-15) |
| `epochs_discarded_by_type` | INTEGER, nula | Las épocas descartadas por tipo (RF-9, RF-10, RF-15) |

Los puntos anteriores quedan con NULL y la ficha no muestra los tipos admitidos (RF-16). `epochs_discarded` se conserva y cuenta todas las descartadas, de las que `epochs_discarded_by_type` son las debidas al tipo. S017b pasa a v18 y S048 a v19 si cambia el esquema.

`out_of_tolerance` sigue guardando `NOT_FIX` en los puntos de antes y en los nuevos: no se reescribe ninguna fila (RNF-T6, constitución nº 1).

## Algoritmos
### A1. Medir con tipos admitidos (RF-8 a RF-10)
```
tipo(época) = SolutionTypes.of(gga.status)          // null: DGPS→SINGLE, ESTIMATED/NONE sin tipo
valida(época) = tipo != null && tipo in admitidos

instantánea:
  la primera época con posición cuyo tipo es valida          (RF-8)
promediada:
  mejor = null
  por cada época con posición y valida:
    si mejor == null o tipo mejor que mejor:
        si mejor != null: descartadasPorTipo += usadas; descartar lo acumulado y las estadísticas (RF-9)
        mejor = tipo
    si tipo == mejor: usar la época
    si no (tipo peor que mejor): descartadasPorTipo++; avisar "Solución degradada" una vez (RF-10)
  época con posición no valida: descartadasPorTipo++; si había mejor != null, avisar una vez
```
- El aviso se repite solo cuando el tipo ha vuelto a `mejor` y se pierde otra vez, como el `SolutionLost` actual.
- Reiniciar lo acumulado exige poder vaciar las sumas y `AverageStats`: se agrupan en un acumulador interno que se recrea (sin guardar épocas, S017/RF-12).
- La solución guardada del punto es siempre la de `mejor` (todas las épocas usadas son de ese tipo): desaparece la «peor de las usadas».
- Con los tres tipos admitidos, una medida promediada ya no mezcla FIX con FLOAT: es el cambio buscado (H3). Con la antigua opción apagada (RF-17) cambia el comportamiento del promediado; la spec lo pide.

### A2. Control de precisión (RF-6, RF-7)
`PrecisionControl.check(solution: SolutionType?, ...)` añade `NOT_FIX` si `solution == null` o no está en `admitted`; el resto de condiciones no cambia. Con tipos admitidos, `PointMeasurement` solo produce `Done` con tipos admitidos salvo en la medida confirmada de D1; el flujo «fuera de tolerancia → confirmar → guardar marcado» de S010/RF-14, RF-15 se conserva tal cual.

### A3. Replanteo (RF-12, RF-13)
`StakeContext.fixOnly` pasa a `admitted`. En `StakeoutController.onEpoch`, si el tipo de la época no está en `admitted` (incluido sin tipo), la guía se apaga con `GuidanceOff.SOLUTION_NOT_ADMITTED` (antes `NO_FIX`) y con el mismo aviso de S018/RF-22. Si está, sigue con cualquiera de ellos, y `GuidanceUi.solution` ya lleva el tipo a la interfaz (RF-13).

### A4. Elegir los tipos (RF-1, RF-3, RF-4)
`AdmittedSolutions.toggle(type)` devuelve `Toggle.Changed(nuevo)`, `Toggle.NeedsConfirmation(type)` (al marcar FLOAT o SINGLE) o `Toggle.LastOne` (al desmarcar el último). La confirmación no se guarda: cada vez que se marca FLOAT o SINGLE se pide, y hasta aceptarla el conjunto no cambia. El diálogo trabaja sobre una copia y guarda con «Guardar», como el resto del control de precisión.

## Contratos
- `AdmittedSolutions`: `types: Set<SolutionType>`, `contains`, `best`, `onlyFix`, `toggle`, `encode()/decode(String?)`, `ALL`, `FIX_ONLY`.
- `CollectorPreferences.settings` y `save` no cambian de forma; cambia `PrecisionControl`.
- `MeasurementResult.epochsDiscardedByType` y `admitted` pasan a `PointQuality` al guardar.

### Textos nuevos (`strings.xml`, es y en)
Tipos de solución admitidos (título y tres casillas con «Fijo (FIX)», «Flotante (FLOAT)» y «Sencilla (SINGLE)» según `docs/glosario.md`), confirmación de FLOAT («submétrica») y SINGLE («de varios metros»), «Debe haber al menos un tipo», condición incumplida «Tipo de solución», aviso «Solución degradada», indicador «No solo FIX», y «Tipos admitidos: …» de la ficha. Sustituyen a `precision_fix_only`, `collector_solution_lost` y `out_of_tolerance_not_fix`.

### Accesibilidad (RNF-T8)
Casillas de 56 dp con etiqueta de TalkBack; el indicador lleva icono y texto; el tipo de solución sigue con color y texto.

## Decisiones técnicas
| Decisión | Motivo | Alternativa descartada |
|---|---|---|
| Conjunto de tipos admitidos, no un nivel mínimo | La spec los marca uno a uno (RF-1) y permite, por ejemplo, FLOAT sin FIX (RF-6) | Un único «tipo mínimo» (no permite el caso «solo FLOAT») |
| `SolutionType` en `core:model` y el mapeo en `core:survey` | `core:model` no conoce `SolutionStatus`; `core:survey` conoce los dos | Mover `SolutionStatus` a `core:model` (arrastra `core:gnss`) |
| Leer la clave antigua sin migrar | RF-17 sin efectos visibles y sin escribir al actualizar | Migración que reescribe el DataStore al arrancar |
| `NOT_FIX` conserva su nombre | Se guarda y se exporta así (CSV de S012, ficheros de oro): renombrarlo cambiaría un formato | Renombrar a `SOLUTION_TYPE` con lectura del valor antiguo |
| Dos columnas nuevas, no una cadena JSON | Consultables y exportables más adelante | Un campo único con todo |
| Sin dependencias nuevas | `AGENTS.md` | — |

## Decisión pendiente del usuario (D1)
RF-6 y RF-8 se pueden leer de dos maneras y cambian el comportamiento de «Medir» en modo instantáneo con la solución en un tipo no admitido:
- **A (propuesta).** Se conserva S010/RF-14: «Medir» instantáneo usa la primera época con posición, sea del tipo que sea; si el tipo no es admitido, la medida termina y se pide confirmar antes de guardar, marcada fuera de tolerancia (RF-6, RF-7). Solo cuando la época inicial es admitida es «la primera época completa de un tipo admitido». El promediado, en cambio, nunca recoge épocas no admitidas.
- **B.** «Medir» instantáneo espera a una época admitida y no mide con otra; entonces RF-7 solo se alcanzaría en el promediado terminado a mano con «Terminar» sin épocas, que no existe. No deja una vía para medir con un tipo no admitido «con confirmación».
Se parte de A. Con B, T4 cambia y RF-6 pasa a ser un aviso previo al pulsar.

## Estrategia de tests
- Unitarios JVM: `AdmittedSolutionsTest` (toggle, último, confirmación, codificación, `ALL`); `PrecisionControlTest` ampliado (cada tipo, null); `SolutionTypesTest` (DGPS → SINGLE, ESTIMATED y NONE sin tipo); `PointMeasurementAdmittedTest` (instantánea, RF-9 con FLOAT y luego FIX, RF-10, contadores, solo FLOAT, épocas sin tipo) y la reproducción con las épocas alternadas contra el CSV esperado de `tools/nmea` (≤ 0,1 mm).
- `data`: `TopoPhoneDatabaseTest` 16 → 17 con puntos antiguos; `CollectorPreferencesTest` (clave nueva, antigua `true`/`false`, ninguna, valores desconocidos); `PointRepositoryAdmittedTest` (se guarda y se lee, punto de antes sin tipos, `NOT_FIX` antiguo).
- `app`: `StakeoutControllerAdmittedTest` (apaga/mantiene según el tipo, DGPS); `CollectorViewModelAdmittedTest` (confirmación previa, modo automático que omite, aviso de degradada); `PrecisionDialogTest` (casillas, último tipo, confirmación, 56 dp); `AdmittedBadgeTest`; ficha del punto.
- Los tests de S010, S017, S018, S019 y S041 que usaban `fixOnly` se adaptan a `admitted` sin cambiar lo que comprueban.
- Campo: demo de campo con FLOAT admitido bajo arbolado (queda para el usuario).

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 a RF-3 | `AdmittedSolutions`, A4, diálogo | `AdmittedSolutionsTest`, `PrecisionDialogTest` |
| RF-4 | A4, diálogo | `PrecisionDialogTest` |
| RF-5, RF-17 | Preferencias | `CollectorPreferencesTest` |
| RF-6, RF-7 | A2, `CollectorViewModel` | `PrecisionControlTest`, `CollectorViewModelAdmittedTest` |
| RF-8 | A1 | `PointMeasurementAdmittedTest` |
| RF-9, RF-10 | A1 | `PointMeasurementAdmittedTest`, reproducción |
| RF-11 | A2 | `CollectorViewModelAdmittedTest` |
| RF-12, RF-13 | A3 | `StakeoutControllerAdmittedTest` |
| RF-14 | indicador | `AdmittedBadgeTest` |
| RF-15 | Room v17 | `TopoPhoneDatabaseTest`, `PointRepositoryAdmittedTest` |
| RF-16 | ficha | `PointDetailsAdmittedTest` |
| RF-18 | `SolutionTypes` | `SolutionTypesTest` |

## Pendiente para otras specs
- Exportar los tipos admitidos y las épocas descartadas por tipo en el CSV no está en la spec; si se quiere, primero un RF en S012.
- Los avisos de calidad de un punto en el mapa según su tipo: no previsto.
