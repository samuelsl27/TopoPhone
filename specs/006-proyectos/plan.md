# Plan técnico — Spec 006

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/006-proyectos/spec.md`. Sin código.

## Estructura de módulos
| Módulo | Qué añade | RF |
|---|---|---|
| `core:model` | `LinearUnit` (m, ft, usft), `AngularUnit` (DD, DMS, gon), `CorrectionsDatum`, `ProjectCrs` (ETRS89 / UTM 29, 30, 31 + EGM08-REDNAP); formato de longitudes en cada unidad; reglas de nombres (comparar sin mayúsculas ni tildes, "(copia n)", "(restaurado)") y aviso de datum | RF-4, RF-7, RF-8 a RF-11, RF-16, RF-25 |
| `core:survey` | `ProjectPositionCalculator`: del fix GGA y la altura del jalón al Este, Norte y altitud en el SC del proyecto (huso fijo), con los motivos de "no disponible" | RF-29, RF-30 |
| `data` | Base de datos Room v1 (proyectos y carpetas), `ProjectRepository` (crear, editar, duplicar, carpetas, papelera, purga), valores por defecto de los proyectos en DataStore, banco de pruebas de migraciones | RF-1 a RF-27; RNF de la spec |
| `app` | Grafo de Levantamiento: lista (carpetas, proyectos, búsqueda), carpeta, formulario, papelera y vista de proyecto; pantalla "Proyectos nuevos" en Ajustes; purga de la papelera al arrancar | RF-1 a RF-31 |
| `tools` | Vectores de referencia de pyproj para UTM con huso fijo y EGM08-REDNAP | RF-29 (≤ 1 mm) |

Dependencias nuevas (ADR-8 ya eligió Room, la alternativa descartada era SQLDelight; todas con licencia Apache-2.0):
- `androidx.room:room-runtime` y `room-ktx`: almacén de proyectos;
- `androidx.room:room-compiler` (KSP, que `data` ya usa para Hilt);
- `androidx.room:room-testing`: `MigrationTestHelper` para el banco de pruebas de migraciones;
- plugin `androidx.room` de Gradle: carpeta de esquemas exportados (`data/schemas/`), que entra en el repositorio.

## Modelo de datos
```kotlin
enum class LinearUnit(val metres: Double) { METRE(1.0), INTERNATIONAL_FOOT(0.3048), US_SURVEY_FOOT(1200.0 / 3937.0) }
enum class AngularUnit { DECIMAL_DEGREES, DMS, GON }
enum class CorrectionsDatum { ETRS89, REGCAN95, ITRF_WGS84 }
enum class ProjectCrs(val utmZone: Int) { ETRS89_UTM29(29), ETRS89_UTM30(30), ETRS89_UTM31(31) }  // datum ETRS89, altitudes EGM08-REDNAP

data class ProjectDefaults(author, crs = ETRS89_UTM30, linear = METRE, angular = DMS, datum = ETRS89)  // DataStore "project_defaults"
```
Room, base de datos `topophone.db`, versión 1 (WAL, `PRAGMA synchronous=FULL`):

| Tabla | Columnas |
|---|---|
| `projects` | `id` (UUID, texto), `name`, `author`, `description`, `crs`, `linear_unit`, `angular_unit`, `corrections_datum`, `folder_id` (nulable), `created_at`, `modified_at`, `opened_at` (nulable), `deleted_at` (nulable: en la papelera) |
| `project_folders` | `id` (UUID), `name` |

- Fechas en ms UTC; enums guardados por nombre (`"ETRS89_UTM30"`), nunca por ordinal.
- Índice sobre `deleted_at` y `folder_id`.
- La unicidad de nombres (sin mayúsculas, solo entre los proyectos fuera de la papelera) la comprueba el repositorio dentro de una transacción. Un índice único no sirve porque los de la papelera conservan su nombre (RF-25).

Ejemplo de proyecto:

| id | name | crs | linear_unit | corrections_datum | folder_id | deleted_at |
|---|---|---|---|---|---|---|
| `6f1c…` | Calle Mayor 12 | ETRS89_UTM30 | METRE | ETRS89 | null | null |

## Algoritmos
### Posición en el SC del proyecto (RF-29, RF-30)
```
1. Punto del suelo como en S002 (jalón vertical + 0,134 m del RS3): φ, λ, h.
2. Si λ ∉ [−10°, +5°] o φ ∉ [35°, 44,5°] → OUTSIDE_CRS_AREA (ámbito de ETRS89/UTM 29–31 en la Península y Baleares).
3. E, N = Utm.forward(φ, λ, zona del proyecto)   // huso fijo, aunque λ caiga en otro huso (huso forzado)
4. N_geoide = EGM08-REDNAP(φ, λ); si no hay → OUTSIDE_GEOID; H = h − N_geoide.
5. Mostrar E, N y H en la unidad lineal del proyecto: valor_m / LinearUnit.metres, con 3 decimales.
```
La referencia son vectores generados con pyproj (EPSG:25829, 25830, 25831 forzados), incluidos puntos de un huso calculados en el vecino.

### Nombres (RF-4, RF-7, RF-16, RF-25)
- Comparar = minúsculas con `Locale.ROOT` y sin marcas diacríticas (NFD sin la categoría Mn).
- Copia: el primero libre de "N (copia)", "N (copia 2)", "N (copia 3)"…
- Restaurar: si el nombre está ocupado, "N (restaurado)", y si también lo está, "N (restaurado 2)"…

### Papelera (RF-22 a RF-27)
```
borrar:    deleted_at = ahora
restaurar: deleted_at = null; si folder_id ya no existe → null; si el nombre está ocupado → nombre de RF-25
purgar al arrancar la app (una vez por proceso): borrar las filas con deleted_at ≤ ahora − 30 días
días que quedan = 30 − ⌊(ahora − deleted_at) / 1 día⌋
```
El reloj se inyecta (`Clock`) para probar los 30 días.

### Lista (RF-1, RF-20)
- Carpetas por nombre (sin mayúsculas ni tildes).
- Proyectos por `opened_at` descendente; los nunca abiertos, por `created_at`.
- Abrir un proyecto actualiza `opened_at`; editarlo, duplicarlo o moverlo actualiza `modified_at`.

## Contratos
- `ProjectRepository` (`data`), todo con `Flow`/`suspend`:
  - `projects(folderId?)`, `search(text)`, `folders()`, `trash()`, `project(id)`;
  - `create(draft): Result<ProjectId, ProjectError>` y `update(id, draft)`, con `ProjectError` = `EMPTY_NAME`, `DUPLICATE_NAME`;
  - `duplicate(id)`, `markOpened(id)`, `moveToFolder(id, folderId?)`;
  - `createFolder(name)`, `renameFolder(id, name)`, `deleteFolder(id)`;
  - `moveToTrash(id)`, `restore(id)`, `deleteForever(id)`, `purgeTrash()`.
- `ProjectPreferences` (`data`): `defaults: Flow<ProjectDefaults>` y `saveDefaults()`.
- `ProjectPositionCalculator.compute(fix, poleHeightM, crs): ProjectPosition` (`core:survey`), con `ProjectPosition(easting, northing, orthometricHeight, unavailable: Set<OUTSIDE_CRS_AREA | OUTSIDE_GEOID | NO_HEIGHT>)`.
- Navegación de Levantamiento (rutas tipadas, ADR-19):
  - `ProjectsRoute` (raíz);
  - `ProjectFolderRoute(folderId)`;
  - `ProjectFormRoute(projectId?)`: nuevo o editar;
  - `ProjectRoute(projectId)`: vista de proyecto;
  - `TrashRoute`.
- Ajustes: `ProjectDefaultsRoute`, en el grupo "Proyectos".
- `SectionScaffold` y `TopoPhoneTopBar` admiten acciones propias (el menú "⋮"), que se pintan entre el indicador de estado y el modo sol.

Organización de la pantalla, como la app de referencia y con diseño propio:
- **Lista:** campo de búsqueda; filas de carpeta y de proyecto (56 dp como mínimo, RNF-T8); botón flotante "Nuevo proyecto"; menú "⋮" en la barra con "Nueva carpeta" y "Papelera".
- **Cada proyecto:** "⋮" con "Editar", "Duplicar", "Mover a carpeta" y "Borrar".
- **Cada carpeta:** "⋮" con "Renombrar" y "Borrar".
- **Vista de proyecto:** título con el nombre, flecha para volver y "Editar" en el menú; tarjetas "Proyecto" (SC, unidades, datum y aviso), "Posición actual" y "Puntos".

Textos nuevos que ve el usuario:

| Caso | Texto |
|---|---|
| Lista vacía | "Todavía no hay proyectos." + "Nuevo proyecto" |
| Nombre vacío | "Escribe un nombre para el proyecto." |
| Nombre repetido | "Ya hay un proyecto con ese nombre." |
| Carpeta repetida o vacía | "Ya hay una carpeta con ese nombre." / "Escribe un nombre para la carpeta." |
| Aviso de datum | "Las correcciones están en ITRF/WGS84 y el proyecto en ETRS89: las coordenadas pueden diferir unos 0,9 m." |
| Borrar proyecto | "¿Mover «%s» a la papelera? Podrás recuperarlo durante 30 días." |
| Borrar carpeta | "¿Borrar la carpeta «%s»? Sus proyectos quedarán fuera de las carpetas." |
| Papelera | "Quedan %d días" + "Restaurar" + "Borrar definitivamente" |
| Borrar definitivamente | "¿Borrar «%s» para siempre? No se podrá recuperar." |
| Fuera del ámbito / del geoide | "Fuera del ámbito del sistema de coordenadas del proyecto." / "Fuera del modelo de geoide EGM08-REDNAP." |
| Sin puntos | "Todavía no hay puntos en este proyecto." |
| Sin posición | "Sin posición del receptor." |

Unidades en pantalla: "m", "ft" y "ft EE. UU.".

## Decisiones técnicas
| Decisión | Motivo | Alternativa descartada |
|---|---|---|
| Room con WAL y `synchronous=FULL` | ADR-8 y RNF-T6: lo confirmado sobrevive a un cierre inmediato | DataStore o ficheros JSON por proyecto (sin transacciones ni consultas) |
| Papelera como columna `deleted_at`, no como tabla aparte | Restaurar es poner un nulo; el contenido de specs futuras no se mueve | Copiar el proyecto a otra tabla |
| Unicidad de nombres en el repositorio | Los de la papelera conservan el suyo (RF-25) | Índice único con `COLLATE NOCASE` (no ignora tildes ni la papelera) |
| Purga al arrancar la app, una vez por proceso | Sin permisos ni WorkManager; los 30 días no necesitan precisión de minutos | WorkManager periódico (dependencia y permisos para nada) |
| SC del proyecto como enum de huso fijo | En S006 solo hay tres SC; S008 lo amplía a un catálogo | Catálogo genérico ya (deuda sin uso) |
| Esquemas exportados en el repositorio y test de migración desde la v1 | RNF-T6: cada versión nueva se prueba contra la anterior | Migraciones destructivas |
| Botón flotante "Nuevo proyecto" | Visible y grande para los guantes (RNF-T8); la barra superior ya lleva el indicador | "+" pequeño en la barra superior |

## Estrategia de tests
- **JVM `core:model`:** factores de las unidades (exactos), formato de longitudes por unidad y configuración regional, comparación de nombres, "(copia n)", "(restaurado n)", aviso de datum.
- **JVM `core:survey`:** `ProjectPositionCalculator` contra vectores de pyproj (≤ 1 mm), con huso forzado, fuera del ámbito y fuera del geoide.
- **`tools`:** generador de vectores de UTM con huso fijo, con su test (`uv run --project tools pytest tools`).
- **Robolectric `data`** (Room en memoria y en fichero, reloj inyectado):
  - crear y editar con sus errores;
  - duplicar;
  - carpetas;
  - papelera y purga a los 30 días;
  - restaurar con nombre ocupado o sin carpeta;
  - durabilidad (cerrar y reabrir el fichero, `PRAGMA synchronous` = 2);
  - `MigrationTestHelper` abriendo la v1 exportada;
  - valores por defecto en DataStore.
- **Robolectric `app` (Compose):** lista, búsqueda, formulario y validaciones, valores por defecto, menú de proyecto y carpeta, papelera, vista de proyecto (datos, aviso, posición, no disponible, sin puntos), pantalla de Ajustes.
- **Rendimiento:** 500 proyectos en la base de datos, lista < 1 s y búsqueda < 300 ms (Robolectric como cota; el móvil de referencia en la demo).
- **Tests de S001 que daban por vacía Levantamiento:** se adaptan a la lista de proyectos.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 a RF-3, RF-20 | Lista y carpeta | `ProjectListTest`, `ProjectRepositoryTest` |
| RF-4 | Búsqueda | `ProjectNamesTest`, `ProjectSearchTest` |
| RF-5 a RF-12 | Formulario | `ProjectFormTest`, `ProjectUnitsTest`, `ProjectRepositoryTest` |
| RF-13 | Ajustes "Proyectos nuevos" | `ProjectDefaultsTest` |
| RF-14, RF-15 | Editar | `ProjectFormTest`, `ProjectRepositoryTest` |
| RF-16 | Duplicar | `ProjectNamesTest`, `ProjectRepositoryTest`, `ProjectMenuTest` |
| RF-17 a RF-21 | Carpetas | `ProjectFolderTest` |
| RF-22 a RF-27 | Papelera | `TrashTest`, `ProjectRepositoryTest` |
| RF-28 a RF-31 | Vista de proyecto | `ProjectViewTest`, `ProjectPositionCalculatorTest` |
| RF-32, RF-35 | Punto central en el formulario, borrarlo | `ProjectCenterTest`, `ProjectFormCentralPointTest` |
| RF-33, RF-34 | Fijarlo por coordenadas, posición o mapa; motivos | `CentralPointResolverTest`, `ProjectFormCentralPointTest`, `CentralPointPickerTest` |
| RF-36 | Datos del proyecto y símbolo en el mapa | `ProjectInfoCentralPointTest`, `MapCentralPointTest` |
| RF-37 | Un único cálculo del centro | `ProjectCenterTest`, `ProjectCenterProviderTest` |
| RF-38 | Duplicar, copias de seguridad y paquetes | `ProjectRepositoryCentralPointTest`, `CentralPointBackupTest` |

---

## Ampliación: punto central del proyecto (RF-32 a RF-38)

> Solo añade a lo anterior. Room pasa a la **v18** (S017b pasará a la v19).

### Estructura de módulos
| Módulo | Qué añade | RF |
|---|---|---|
| `core:model` | `CentralPoint(latitudeDeg, longitudeDeg)`; `ProjectDraft.centralPoint` y `Project.centralPoint` (opcionales, `null` por defecto); `ProjectCenter.of(central, points, viewport)`: la regla de «centro del proyecto» | RF-32, RF-35, RF-37 |
| `core:survey` | `CentralPointResolver`: de unas coordenadas escritas, un toque del mapa o una posición del receptor al `CentralPoint`, o el motivo de rechazo | RF-33, RF-34 |
| `data` | Room v18 (`central_latitude`, `central_longitude`), `AutoMigration(17, 18)`, el repositorio lo guarda, lo conserva al duplicar y viaja en copias y paquetes | RF-32, RF-38; RNF-T6 |
| `app` | `ProjectCenterProvider` (el único sitio que calcula el centro, con los puntos y el encuadre del proyecto); sección «Punto central» del formulario; selector en el mapa; `MapController.showCentralPoint`; fila en los datos del proyecto | RF-32 a RF-37 |

Sin dependencias nuevas.

### Modelo de datos
```kotlin
data class CentralPoint(val latitudeDeg: Double, val longitudeDeg: Double)   // geográficas ETRS89/REGCAN95 del punto del suelo
ProjectDraft(..., val centralPoint: CentralPoint? = null)
Project(..., val centralPoint: CentralPoint? = null)
```
Room v18, tabla `projects`: `central_latitude REAL NULL` y `central_longitude REAL NULL` (ambas nulas o ambas con valor). `AutoMigration(17, 18)`: añadir columnas nulables no necesita especificación; los proyectos de antes quedan sin punto central.

**Decisión D1 (pendiente de tu confirmación): se guarda en geográficas, no en Este/Norte.** El punto se guarda como latitud y longitud del suelo sin redondear (como las coordenadas de los puntos de S010), y se muestra en el SC del proyecto con `ProjectPositionCalculator.fromGround`. Motivo: los SC de S008 incluyen ED50 y geográficas, y el usuario puede cambiar el SC del proyecto (RF-15); un Este/Norte guardado exigiría guardar también el SC y reconvertir. Lo escrito en el SC del proyecto se convierte una vez con `ManualCoordinates` (la misma ruta que los puntos manuales de S011). Alternativa descartada: guardar Este/Norte y el SC en que se fijó.

### Algoritmos
**Centro del proyecto (RF-37)**, `ProjectCenter.of`:
```
1. Si el proyecto tiene punto central → ese.
2. Si no, y tiene puntos → la media de latitudes y de longitudes de sus puntos (como hoy).
3. Si no, y tiene encuadre (S009) → su centro.
4. Si no → null (sin centro).
```
`ProjectCenterProvider` (`app`) reúne el proyecto, sus puntos y su encuadre y llama a `ProjectCenter.of`. `CadImportViewModel` y `SurfaceImportViewModel` dejan de tener cada una su `centerOf` y usan el proveedor; S048 lo reutilizará.

**Fijar el punto (RF-33, RF-34)**, `CentralPointResolver`:
```
fromTyped(first, second, crs)  → ManualCoordinates.compute sin altura (EntryForm.PROJECT_CRS o geográficas, según el SC)
fromTap(lat, lon, crs)         → solo el control de ámbito
fromFix(gga, crs)              → NO_POSITION si !fix.hasPosition; si no, el suelo (jalón vertical, como RF-29) y el control de ámbito
ámbito: crs.area.contains(lat, lon) (el mismo criterio que RF-30) → si no, OUTSIDE_CRS_AREA
        ED50 sin rejilla → OUTSIDE_ED50_GRID
Resultado: CentralPoint o CentralPointRejected(NO_POSITION | OUTSIDE_CRS_AREA | OUTSIDE_ED50_GRID | INVALID_NUMBER)
```
El punto central es solo horizontal: no lleva altura, así que el geoide no interviene.

### Contratos
- `ProjectCenter.of(central: CentralPoint?, points: List<CentralPoint>, viewport: MapViewport?): CentralPoint?`
- `CentralPointResolver.fromTyped / fromTap / fromFix(...) : CentralPointResult` (`Fixed(point)` o `Rejected(reason)`).
- `ProjectCenterProvider.center(projectId): CentralPoint?` (`suspend`).
- `MapController.showCentralPoint(position: GeoPoint?)`, como `showBase`.
- `ProjectFormUiState.centralPointError: CentralPointRejected?` para el motivo bajo el campo.

### Interfaz
- **Formulario (nuevo y edición):** sección «Punto central (opcional)». Sin fijar: «Sin fijar» y tres botones de 56 dp: «Escribir coordenadas», «Usar mi posición» y «Elegir en el mapa». Fijado: sus coordenadas en el SC y unidades del proyecto y los botones «Cambiar» y «Quitar».
- **Escribir coordenadas:** diálogo con Este/Norte (o latitud/longitud si el SC es geográfico), con el formato de coordenadas de S011.
- **Elegir en el mapa:** pantalla completa con el mapa del proyecto (`MapSlot`), sin puntos del proyecto; un toque coloca el símbolo, «Aceptar» confirma y «Cancelar» vuelve. Empieza en el punto central, o en la posición del receptor, o en España. Se abre desde el formulario sin salir de él, para no perder lo escrito. Un proyecto nuevo aún no existe, por eso el selector no depende de `ProjectRoute`.
- **Datos del proyecto (S009/RF-5):** fila «Punto central» con Este, Norte (o latitud y longitud) o «Sin fijar».
- **Símbolo en el mapa:** una cruz dentro de un círculo, distinta de la base, el rover y los puntos; en la leyenda y en la descripción de accesibilidad: «Punto central del proyecto» (icono y texto, RNF-T8).

Textos nuevos (es y en, en `strings.xml`):

| Caso | Texto |
|---|---|
| Sección | «Punto central (opcional)» |
| Sin fijar | «Sin fijar» |
| Acciones | «Escribir coordenadas» · «Usar mi posición» · «Elegir en el mapa» · «Cambiar» · «Quitar» |
| Sin posición | «No se puede usar la posición: el receptor no la da ahora.» |
| Fuera del ámbito | «El punto queda fuera del ámbito del sistema de coordenadas del proyecto.» |
| Fuera de la rejilla ED50 | «El punto queda fuera de la rejilla de ED50.» |
| Número no válido | «Escribe las dos coordenadas como números.» |
| Accesibilidad del símbolo | «Punto central del proyecto» |

### Decisiones técnicas
| Decisión | Motivo | Alternativa descartada |
|---|---|---|
| Dos columnas nulables en `projects` | RF-38: duplicar copia la fila entera, las copias y los paquetes copian `SELECT *`; sin tabla nueva | Tabla `project_central_points` (otra clave ajena, otro `TABLES` y otra migración para dos números) |
| Geográficas del suelo (D1) | Sirve a todos los SC de S008 y al cambio de SC (RF-15) | Este/Norte + SC en que se fijó |
| `ProjectCenter` en `core:model`, puro | Un solo cálculo, probado en JVM, sin `android.*` | Dejar un `centerOf` en cada ViewModel de importación |
| Selector a pantalla completa dentro del formulario | El proyecto nuevo aún no tiene vista de proyecto; no se pierde lo escrito | Navegar a otra ruta y devolver el resultado por el back stack |
| `CentralPointResolver` en `core:survey` | `survey` ya usa geodesia (ED50, UTM) y `ManualCoordinates` | Convertir en el ViewModel |

### Estrategia de tests
- **JVM `core:model`:** `ProjectCenterTest`: las cuatro ramas de la regla, el punto central manda sobre puntos y encuadre, borrarlo vuelve a la regla anterior, sin nada no hay centro.
- **JVM `core:survey`:** `CentralPointResolverTest`: escrito en UTM contra el vector de pyproj (≤ 1 mm en la ida y vuelta), geográficas, ED50, toque, posición sin fix, fuera del huso de ámbito y fuera de la rejilla ED50.
- **Robolectric `data`:** `TopoPhoneDatabaseTest` 17 → 18 (proyectos de antes sin punto central); `ProjectRepositoryCentralPointTest` (guardar sin redondear, editar, quitar, duplicar); `CentralPointBackupTest` (copia de seguridad y paquete `.topoproj`, ida y vuelta). Nombres de test de ≤ 60 caracteres.
- **Robolectric `app` (Compose):** `ProjectFormCentralPointTest`, `CentralPointPickerTest` (con el doble de `MapController`), `ProjectInfoCentralPointTest`, `MapCentralPointTest`, `ProjectCenterProviderTest`; los tests de importación de S040 y S042 se adaptan al proveedor.
