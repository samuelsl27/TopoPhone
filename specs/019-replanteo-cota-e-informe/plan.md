# Plan técnico — Spec 019

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/019-replanteo-cota-e-informe/spec.md` y `specs/018-replanteo-puntos/plan.md` (esta spec extiende el replanteo de S018). Sin código.

Sin dependencias nuevas (Gradle ni Python): todo sale de `core:*`, Room, DataStore, Compose y `pyproj`, que ya están.

## Estructura de módulos
- `core:model`:
  - `StakeoutSettings.toleranceVM`: tolerancia vertical, de 1 cm a 1 m, 3 cm por defecto, con su conversión a la unidad del proyecto (RF-10);
  - `StakeoutRef` gana `designElevationM` (la cota usada), `toleranceHM`, `toleranceVM` y `withinTolerance` (RF-13, RF-14, RF-26);
  - `StakeoutNames.number(name)`: el número de replanteo de `_stkd`, `_stkd2`… (RF-21);
  - `StakeoutReport`: las filas del informe, "último replanteo", "sin evaluar" y el resumen (RF-17, RF-18, RF-19, RF-21, RF-26).
- `core:survey`:
  - `StakeoutLevel`: estados del nivel (desmonte, terraplén, en cota) y avisos, como función pura con el margen de 5 mm (A1; RF-6, RF-8, RF-9);
  - `LevelScale` y `LevelView`: la escala 1-2-5 por potencias de diez y dónde cae la burbuja (A2; RF-7);
  - `StakeoutDiff.ref` recibe la cota usada y las tolerancias, y calcula la diferencia de cota contra la cota usada y si cumple (A3; RF-13, RF-14).
- `core:formats`:
  - `StakeoutReportCsv`: el CSV del informe (A5; RF-21, RF-22, RF-23);
  - `ExportColumn` con las columnas del replanteo (RF-25); `CsvExport` abre sus ayudas (`number`, `quoted`, `utc`) a todo el módulo.
- `core:geodesy`: sin cambios.
- `data`:
  - columnas nuevas en `points`, migración 10 → 11 y su lectura y escritura (A4; RF-13, RF-14, RF-26);
  - `StakeoutPreferences` con la tolerancia vertical (RF-10).
- `receiver` → `Alerts.stakeoutLevelArrived()` y `stakeoutLevelLeft()` (RF-9).
- `app`:
  - `StakeoutController`: la cota efectiva, el nivel en `GuidanceUi`, los avisos y el apagado junto a la guía (A1; RF-1, RF-5 a RF-9, RF-11, RF-12);
  - `StakeoutViewModel`: la cota escrita, "Usar la del punto", la tolerancia vertical y el bloqueo durante la medida (RF-2 a RF-4, RF-10, RF-16);
  - `CollectorViewModel`: guarda con la cota usada, las tolerancias y si cumple (A3; RF-13, RF-14);
  - `StakeoutPanel`: el nivel vertical, "Desmonte/Terraplén/En cota", el diálogo de la cota y la tolerancia vertical (RF-5 a RF-8, RF-10);
  - la confirmación del punto replanteado y su ficha (RF-15);
  - `StakeoutReportViewModel` y la pantalla del informe, con sus entradas en el menú del proyecto y de la lista de objetos (A5; RF-17 a RF-20, RF-24);
  - `ExportScreen` con las columnas nuevas (RF-25).
- `tools`:
  - `tools/replanteo/generar_informe.py`: puntos de diseño con y sin altura, con cota escrita, en la Península y Canarias, con la diferencia de cota, el desmonte o terraplén, "dentro" y el informe completo con su resumen, calculados con pyproj y Python aparte (constitución nº 2);
  - `tools/nmea/generar_cota_rs3.py`: la reproducción `rs3-cota` (la punta sube y baja alrededor de la cota de diseño, con el FIX perdido un rato) y lo esperado de cada época.

## Modelo de datos

### En el punto replanteado (Room v11)
```
points  +  stake_design_elevation REAL NULL   // la cota de diseño usada (la del punto o la escrita)
        +  stake_tol_h REAL NULL, stake_tol_v REAL NULL   // tolerancias vigentes al medir (m)
        +  stake_within INTEGER NULL          // 1 dentro de la tolerancia, 0 fuera
```
- `AutoMigration(10, 11)` sin especificación: solo columnas opcionales y sin valor por defecto (como 9 → 10).
- `stake_d_height` (S018) pasa a ser medido menos la cota usada; hasta ahora era medido menos la altura del punto, que es lo mismo sin cota escrita.
- Los replanteos de antes quedan con las columnas nuevas a NULL. Al leerlos: `designElevationM = stake_design_elevation ?: stake_design_height` si `stake_d_height` no es NULL (en S018 la cota usada era la del punto). Sin tolerancias, `withinTolerance` es null: "sin evaluar" (RF-26).
- Se guarda `stake_within` y no se recalcula al informar: las tolerancias de entonces mandan y cambiarlas después no cambia el informe (RF-13, RF-14).
- Hay que añadir las columnas a la consulta de `forExport` y a las demás `SELECT` con la lista explícita de columnas de `Points.kt`.

### Ajustes del replanteo (DataStore `"stakeout"`)
```
StakeoutSettings( ..., toleranceVM: Double (0,01..1,0), 0,03 por defecto )
```
Clave `stakeout_tolerance_v_m`. Se escribe en la unidad del proyecto (RNF-T9); fuera de rango se corrige. Va a la copia de ajustes con el resto del almacén `"stakeout"`.

### La cota escrita
Solo vive en `StakeoutViewModel` (en memoria, en metros del sistema de alturas del proyecto): no se guarda en el punto de diseño (RF-2) y se descarta al cambiar de punto o cerrar (RF-3). Al medir se guarda, por valor, en el punto replanteado.

## Algoritmos

### A1. El nivel vertical (RF-1, RF-5 a RF-9, RF-11, RF-12)
- **Cota efectiva**: la escrita, si hay; si no, `target.coordinates.height`. El `StakeoutController` usa `design.copy(height = efectiva)` en `StakeoutGeometry.guidance`, que ya da `dHeightM` = punta menos diseño (RF-6). Sin cota efectiva o sin altura de la punta, `dHeightM` es null: no hay nivel y se dice cuál falta (RF-5).
- **Estado** (`StakeoutLevel.next(current, dz, tolerance)`), función pura como `StakeoutZones`:
  - `ON_GRADE` si `|dz| ≤ t`; `CUT` (desmonte) si `dz > t`; `FILL` (terraplén) si `dz < −t`;
  - entrar en `ON_GRADE` da `LEVEL_ARRIVED`; salir solo cuando `|dz| > t + 0,005` y da `LEVEL_LEFT` (margen de 5 mm de RF-9, el mismo criterio que S018/A3); entre `t` y `t + 0,005` no cambia;
  - el estado inicial es "fuera": si la primera época ya está en cota, avisa de la entrada, como la zona de S018.
- **Un corte, sin FIX con "Solo FIX", sin estado 30 o sin posición** apagan la guía y con ella el nivel: el estado vuelve a "fuera" y no avisa hasta que se cumpla otra vez (RF-11); el motivo es el de la guía (`GuidanceOff`).
- **Independiente de la llegada horizontal**: `StakeoutZones` no se toca y no recibe `dz` (RF-12).
- **Avisos**: `Alerts.stakeoutLevelArrived()` y `stakeoutLevelLeft()`, con sonidos y patrones distintos entre sí y de los tres de S018 (RF-9). Se anotan en el log como `Replanteo de X: en cota` y `fuera de cota`, sin coordenadas (S014/RF-5, RF-7).
- Cambiar la cota escrita o volver a la del punto reinicia el estado del nivel a "fuera".

### A2. La escala del nivel y la burbuja (RF-7)
- `LevelScale.radius(previous, dzAbs, toleranceV)`: el radio visible, en pasos de 1, 2 y 5 por cada potencia de diez (5 cm, 10, 20, 50 cm, 1 m, 2, 5, 10 m…) sin límite superior, para que quepan la burbuja con un 25 % de margen y la banda de la tolerancia con un 20 %. El mínimo es 5 cm. Baja de paso solo cuando la burbuja con su margen cabe en el 80 % del paso menor (sin saltos de ida y vuelta), como `DianaScale`.
- `LevelView.bubble(dz, radius)`: posición en `[-1, 1]` (arriba, desmonte), recortada al borde si se sale; `LevelView.bandFraction(toleranceV, radius)`.
- El valor numérico ("Desmonte 3,250 m") sale de `dz` sin recortar: la escala solo afecta al dibujo, nunca a la cifra (RF-6).

### A3. Medir, guardar y "dentro de la tolerancia" (RF-13, RF-14, RF-16)
- El panel da al `CollectorViewModel` un `StakeSaveInfo(elevationM, toleranceHM, toleranceVM)` junto al `StakeTarget` (`setStakeTarget(target, info)`).
- `StakeoutDiff.ref(...)` calcula las diferencias como en S018 y la diferencia de cota contra `info.elevationM` (medido menos cota), sin redondear; con el diseño por valor (RF-28 de S018) y la cota usada aparte.
- `dentro = distanciaH ≤ toleranceH && (dz == null || |dz| ≤ toleranceV)` con los valores ya guardados: una función pura (`StakeoutDiff.within`) para que el informe y la ficha coincidan (RF-14).
- Durante la medida el `ViewModel` rechaza cambiar la cota y la tolerancia vertical (RF-16), con la misma señal `inProgress.active` de S018/RF-30.

### A4. El informe (RF-17 a RF-21, RF-26)
- `StakeoutReport.of(points)` filtra los puntos activos con `staked != null`, ordena por nombre natural del diseño y por número de replanteo, y marca el último de cada diseño.
- Número de replanteo: el de `StakeoutNames.number(nombre)`; si el nombre no sigue el patrón (editado), el orden por fecha de creación entre los del mismo diseño.
- "Último": el de mayor fecha de creación entre los incluidos en el informe (con selección, entre los seleccionados).
- Resumen sobre los últimos: puntos de diseño replanteados; dentro, fuera y sin evaluar (RF-26); desviación horizontal máxima y media; desviación vertical `|dz|` máxima y media, solo de los que tienen `dHeightM`. Los sin evaluar entran en las desviaciones y no en dentro ni fuera.
- La selección llega por `ExportSelection` (S012/RF-2); un id que no es un punto replanteado se ignora (RF-18). La papelera ya no sale en `pointsForExport` (RF-19).
- Sin filas: el resumen lo dice y no se crea el fichero (RF-20).

### A5. El CSV del informe (RF-21 a RF-24)
- `StakeoutReportCsv.write(rows, project, separator, decimal, out)`: primera fila con los nombres de las columnas (en inglés, como S012), CRLF, RFC 4180, UTF-8 sin BOM (RF-23).
- Columnas por este orden: `Design name`, `Stakeout number`, `Staked name`, `Description`, `Design easting`, `Design northing`, `Design elevation`, `Measured easting`, `Measured northing`, `Measured elevation`, `Delta northing`, `Delta easting`, `Horizontal distance`, `Delta elevation`, `Horizontal tolerance`, `Vertical tolerance`, `Within tolerance`, `Last`, `Solution`, `Measured from` (UTC ISO 8601 como S012; RF-21).
- Proyecto geográfico: `Design latitude`/`Design longitude` y `Measured latitude`/`Measured longitude` en lugar de Este y Norte (RF-22); las diferencias son las del plano local de S018.
- Longitudes en la unidad del proyecto con 4 decimales, grados con 9 y la celda vacía cuando falta el valor (cota de un replanteo sin cota, tolerancias de un replanteo anterior). `Within tolerance` vacío si es "sin evaluar".
- Nombre propuesto: `ExportFileName.of(proyecto, "replanteo", ahora, zona)` → `<proyecto>_replanteo_<AAAAMMDD-HHMM>.csv` (RF-24).
- Guardar y compartir con `ExportFiles` y `writeToUri` (RF-24). El separador y el signo decimal son los de `ExportPreferences` (S012/RF-13, RF-21).

## Contratos

### Estado del replanteo (`StakeoutUiState`)
Se amplía con `designElevationM: Double?` (la cota efectiva), `elevationOverridden: Boolean` y `saveInfo: StakeSaveInfo?`. `GuidanceUi` gana `level: LevelUi?` = `dzM`, `state` (`CUT`/`FILL`/`ON_GRADE`), `radiusM` y `toleranceVM`; null si falta la cota o la altura de la punta, con el motivo en `UiState` (`NO_DESIGN_ELEVATION` o `TIP_WITHOUT_HEIGHT`).

### Pantallas
- **Panel de replanteo**: el nivel vertical va al final de la fila de la flecha y la diana, a todo el alto de la diana; con la guía apagada desaparece junto a ella. Debajo de las cifras, "Desmonte 3,250 m" o "Terraplén 0,040 m" o "En cota", con el color y el texto (RNF-T8). Reemplaza al renglón `stakeout_dz` de S018/RF-12.
- **Cota de diseño**: botón en el panel que abre un diálogo con el campo (unidad del proyecto), "Usar la del punto" y el texto de error si no es un número (RF-2 a RF-4). La tolerancia vertical va en el diálogo de ajustes, junto a la horizontal (RF-10). Ambos bloqueados mientras se mide (RF-16).
- **Confirmación y ficha** (RF-15): "Punto %s guardado: ΔN … ΔE … · Desmonte 0,012 m · dentro de la tolerancia", y en la ficha el bloque del replanteo con la cota de diseño usada, desmonte/terraplén/en cota y "Dentro de la tolerancia" o "Fuera de la tolerancia" o "Sin evaluar".
- **Informe de replanteo**: pantalla nueva del proyecto (`SurveyPage.StakeoutReport`) con el resumen y "Guardar"/"Compartir", a la manera de `ExportScreen`. Entradas en el menú del proyecto (junto a "Exportar CSV"), en el de la lista de objetos y en la acción de selección.
- **Exportar CSV de puntos**: las siete columnas nuevas en "Todas las columnas" y en la lista de "Personalizado".

### Textos nuevos (en `strings.xml`, es y en)
Desmonte, Terraplén, En cota, Cota de diseño, "Usar la del punto", "Sin cota de diseño: el punto no tiene altura", "Punta sin altura: no hay cota", "Cota no válida", Tolerancia vertical, Informe de replanteo, resumen ("%d puntos replanteados", "%d dentro de la tolerancia", "%d fuera", "%d sin evaluar", "Desviación horizontal: máxima %s, media %s", "Desviación vertical: máxima %s, media %s"), "No hay puntos replanteados", "Fuera de la tolerancia", "Sin evaluar", "Dentro de la tolerancia" (ya existe), "Punto %s guardado: ΔN %s · ΔE %s · %s" y la descripción del nivel para TalkBack ("Punta a 4 cm por encima de la cota, desmonte").

### Accesibilidad (RNF-T8)
- El nivel lleva una descripción de texto con el desmonte o terraplén y su valor; se anuncia al cambiar de estado (desmonte ↔ en cota ↔ terraplén) y a petición, no en cada refresco; "En cota" es una región viva.
- El campo de la cota y los botones llevan 56 dp y su etiqueta.

## Decisiones técnicas
| Decisión | Por qué | Alternativa descartada |
|---|---|---|
| El nivel y su escala son lógica pura en `core:survey` | Constitución nº 6 y mismo patrón que `StakeoutZones` y `DianaScale`: se prueban sin Android | Calcular el estado dentro del controlador |
| `StakeoutZones` no recibe la cota | RF-12: la llegada horizontal no cambia; así S018 no se puede romper | Una zona 3D que mezcla H y V |
| La cota usada se guarda aparte de la altura del diseño | El diseño se guarda por valor (S018) y la cota escrita no toca el punto (RF-2); la ficha enseña las dos | Sobrescribir `stake_design_height` con la cota escrita |
| Se guarda `stake_within` y las dos tolerancias | RF-13: el informe no debe cambiar si el usuario cambia después las tolerancias | Recalcular al informar con las tolerancias de ahora |
| Los replanteos de antes quedan "sin evaluar" | RF-26: no se inventa un veredicto con tolerancias que no se guardaron | Evaluarlos con las tolerancias actuales o con 2,5 cm |
| Margen de 5 mm también en el nivel | Mismo ruido del GNSS que en S018/A3: sin margen, el aviso se repetiría en el borde | Comparar `dz` con la tolerancia en cada época |
| La etiqueta "En cota" sigue el estado con histéresis | Igual que "Dentro de la tolerancia" en S018: no parpadea entre `t` y `t + 5 mm` | Etiquetar con el umbral exacto en cada época |
| Escala del nivel sin límite superior | RF-6 y RF-7: se debe poder indicar metros a excavar o rellenar | Recortar la escala a 2 m como la diana |
| `StakeoutReport` en `core:model` y el CSV en `core:formats` | `core:formats` solo depende de `core:model`; la lógica del informe no necesita geodesia | Poner el informe en `core:survey` y hacer depender de él a `core:formats` |
| `StakeoutReportViewModel` aparte del de exportación | `ExportViewModel` está atado a formatos, columnas y `canExport` de S012; mezclar los dos complicaría ambos | Un "modo informe" dentro de `ExportViewModel` |
| Se reutilizan `ExportSelection`, `ExportFiles`, `ExportPreferences` y `ExportFileName` | RF-23 y RF-24 piden el mismo comportamiento que S012 | Copiar el guardado y el compartir |
| `CsvExport.number`, `quoted` y `utc` pasan a `internal` | Un solo formato de números y de comillas para los dos CSV | Duplicarlos en `StakeoutReportCsv` |
| La hora del informe en UTC, como S012 | Decidido con el usuario; sin ambigüedad de zona y de horario de verano | Hora local con desfase del móvil al exportar |
| La cota escrita se descarta al cambiar de punto | RF-3 y suposición de la spec | Recordarla por punto de diseño (fuera de alcance) |
| `AutoMigration(10, 11)` sin especificación | Solo columnas nuevas opcionales | `RoomDatabase.Migration` a mano |
| `Alerts` con dos métodos nuevos con valor por defecto | Los fakes de tests no cambian (como en S017 y S018) | Un método con un parámetro |
| La reproducción `rs3-cota` en un proyecto de alturas elipsoidales | Lo esperado se calcula aparte sin depender del geoide; el geoide ya se prueba en S002 y S008 | Alturas ortométricas: el esperado necesitaría la rejilla en el script |

## Estrategia de tests
- **Unitarios JVM**:
  - `StakeoutLevelTest`: los tres estados, el margen de 5 mm, el aviso una vez, el borde oscilando y el reinicio (RF-6, RF-8, RF-9).
  - `LevelScaleTest` y `LevelViewTest`: los pasos de 1-2-5 hasta decenas de metros, sin saltos de ida y vuelta, la burbuja recortada y la banda (RF-7).
  - `StakeoutDiffTest` ampliado: la cota usada distinta de la del diseño, `within` con y sin cota, `fixtures/replanteo/` frente a pyproj (≤ 1 mm) (RF-13, RF-14).
  - `StakeoutSettingsTest` con la tolerancia vertical y sus unidades; `StakeoutNamesTest` con `number` (RF-10, RF-21).
  - `StakeoutReportTest`: ordenación, "último", varios replanteos, con selección, sin evaluar, resumen frente a `fixtures/replanteo/` (≤ 0,1 mm) (RF-17, RF-18, RF-26).
  - `StakeoutReportCsvTest` y golden: columnas, geográficas, celdas vacías, formato y comillas (RF-21 a RF-23); `CsvExportTest`/`CsvExportGoldenTest` con las siete columnas nuevas (RF-25).
- **`data`**:
  - `TopoPhoneDatabaseTest` 10 → 11: los puntos y replanteos conservan sus datos y las columnas nuevas quedan a NULL; un replanteo de antes se lee con su cota y sin evaluar (RF-26, RNF-T6).
  - `PointRepositoryStakeoutTest` ampliado: se guarda y se lee la cota usada, las tolerancias y `within` sin redondear (RF-13, RF-14).
  - `StakeoutPreferencesTest` y `SettingsArchiveTest` con `toleranceVM` (RF-10).
- **`receiver`**: `VibrationAlertsTest` con los dos patrones nuevos, distintos entre sí y de los demás (RF-9).
- **Robolectric (`app`)**:
  - `StakeoutControllerTest` ampliado, con la `ReceiverSession` real y las épocas de una en una: nivel con cota del punto y escrita, sin cota, punta sin altura, avisos, apagado con la guía, la llegada horizontal que no cambia (RF-1, RF-5 a RF-9, RF-11, RF-12).
  - `StakeoutViewModelTest`: cota escrita, no numérica, "Usar la del punto", cambio de punto, tolerancia vertical y el bloqueo durante la medida (RF-2 a RF-4, RF-10, RF-16).
  - `CollectorViewModelStakeoutTest` ampliado: guardar con la cota usada, las tolerancias y `within`, dentro en H y fuera en V y al revés, sin cota (RF-13, RF-14).
  - `StakeoutPanelTest` ampliado y `StakeoutLevelTest` de interfaz: el nivel a cualquier distancia y con varios metros, textos, verde con "En cota", ajustes y accesibilidad (RF-5 a RF-8, RF-10, RF-15).
  - `PointDetailsStakeoutTest` ampliado con el bloque de la cota (RF-15).
  - `StakeoutReportViewModelTest` y `StakeoutReportScreenTest`: resumen, selección, papelera, vacío, guardar y compartir, error de escritura y cancelar el selector (RF-17 a RF-20, RF-24).
  - `ExportViewModelTest` y `ExportScreenTest` con las columnas nuevas y una selección guardada de antes (RF-25).
- **`tools`**: tests de `generar_informe.py` y de `generar_cota_rs3.py` (que lo guardado es lo que generan los scripts).
- **Replay NMEA** (`fixtures/nmea/sintetico/`): `rs3-cota` y `rs3-cota.nivel.csv` con el estado esperado de cada época; `StakeoutLevelReplayTest` lo recorre con el `StakeoutController` y el `CollectorViewModel`.
- **Rendimiento**: `StakeoutLevelPerformanceTest` (el nivel a 10 Hz, menos de 1 ms por época; de una época a la pantalla, menos de 300 ms) y `StakeoutReportPerformanceTest` (resumen y CSV de 1.000 puntos replanteados en menos de 2 s).
- **Manual o de campo**: demo en el emulador (criterios de finalización); en campo, los ensayos d, e con una cota de diseño y g de la puerta G2.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 | A1 | `StakeoutControllerTest` |
| RF-2 | A1, `StakeoutViewModel` | `StakeoutViewModelTest`, `StakeoutPanelTest` |
| RF-3 | `StakeoutViewModel` | `StakeoutViewModelTest` |
| RF-4 | `StakeoutViewModel` | `StakeoutViewModelTest`, `StakeoutPanelTest` |
| RF-5 | A1 | `StakeoutControllerTest`, `StakeoutPanelTest` |
| RF-6 | A1, A2 | `StakeoutLevelTest`, `StakeoutControllerTest`, `StakeoutPanelTest` |
| RF-7 | A2 | `LevelScaleTest`, `LevelViewTest`, `StakeoutPanelTest` |
| RF-8 | A1 | `StakeoutLevelTest`, `StakeoutPanelTest` |
| RF-9 | A1 | `StakeoutLevelTest`, `StakeoutControllerTest`, `VibrationAlertsTest` |
| RF-10 | `StakeoutSettings` | `StakeoutSettingsTest`, `StakeoutPreferencesTest`, `StakeoutViewModelTest`, `StakeoutPanelTest` |
| RF-11 | A1 | `StakeoutControllerTest` |
| RF-12 | A1 | `StakeoutControllerTest` |
| RF-13 | A3, datos | `StakeoutDiffTest`, `PointRepositoryStakeoutTest`, `CollectorViewModelStakeoutTest` |
| RF-14 | A3 | `StakeoutDiffTest`, `CollectorViewModelStakeoutTest`, `PointRepositoryStakeoutTest` |
| RF-15 | Confirmación y ficha | `StakeoutPanelTest`, `PointDetailsStakeoutTest` |
| RF-16 | A3 | `StakeoutViewModelTest`, `StakeoutPanelTest` |
| RF-17 | A4 | `StakeoutReportTest`, `StakeoutReportViewModelTest`, `StakeoutReportScreenTest` |
| RF-18 | A4 | `StakeoutReportTest`, `StakeoutReportViewModelTest` |
| RF-19 | A4 | `StakeoutReportViewModelTest`, `PointRepositoryStakeoutTest` |
| RF-20 | A4 | `StakeoutReportViewModelTest`, `StakeoutReportScreenTest` |
| RF-21 | A4, A5 | `StakeoutReportCsvTest`, `StakeoutNamesTest` |
| RF-22 | A5 | `StakeoutReportCsvTest` |
| RF-23 | A5 | `StakeoutReportCsvTest` |
| RF-24 | A5 | `StakeoutReportViewModelTest`, `StakeoutReportScreenTest` |
| RF-25 | `ExportColumn` | `CsvExportTest`, `CsvExportGoldenTest`, `ExportViewModelTest` |
| RF-26 | Datos, A4 | `TopoPhoneDatabaseTest`, `StakeoutReportTest`, `StakeoutReportCsvTest` |

## Pendiente para otras specs
- S017b (fotos) tenía reservada la versión 11 de la base de datos. S019 la ocupa: cuando se retome, sus tablas pasan a la versión 12 (`AutoMigration(11, 12)` y `TopoPhoneDatabaseTest` 11 → 12). Hay que actualizar el aviso de su `plan.md`, lo que requiere permiso para tocar `specs/`.
