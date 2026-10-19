# Plan técnico — Spec 013 (copia de seguridad local y paquetes de proyecto)

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/013-copia-seguridad-local/spec.md`. Sin código.

## Estructura de módulos
- `core:formats` → el fichero de copia y de paquete (paquete Kotlin `backup`), sin Android:
  - `BackupManifest` y `BackupArchive`: escribir y leer el ZIP, con sumas SHA-256 y validación (RF-5, RF-12, RF-13);
  - `BackupFileNames`: nombres propuestos de la copia, del paquete, de las copias automáticas y de las de antes de restaurar (RF-4, RF-7, RF-19);
  - `AutoBackupPolicy`: cuándo toca una copia automática y qué copias sobran (RF-18, RF-19).
- `data` → los datos que entran y salen (paquete `backup`):
  - `DatabaseSnapshot`: copia de las tablas de Room a una base de datos aparte, entera o de un proyecto, con una instantánea coherente (RF-2, RF-3, RF-24, RNF);
  - `SettingsArchive`: los ajustes que viajan, sin los propios del móvil (RF-2, RF-3, RF-8);
  - `BackupRepository`: crear la copia, restaurarla, deshacer, exportar e importar paquetes (RF-1 a RF-16, RF-24 a RF-28);
  - `BackupFolder`: la carpeta de las copias automáticas con `DocumentsContract` (RF-17, RF-19, RF-21);
  - `AutoBackupPreferences` (DataStore "backup") y `BackupChanges`: cambios sin copiar (RF-18, RF-20, RF-21, RF-23).
- `app`:
  - `AutoBackupScheduler`: comprobaciones cada 5 min y al pasar a segundo plano (RF-18, RF-21);
  - `MeasurementInProgress`: si hay una medida en curso, visible fuera del recolector (RF-16);
  - pantalla "Copias de seguridad" en Ajustes y su `BackupsViewModel` (RF-1, RF-6, RF-11, RF-17, RF-20, RF-21);
  - aviso y alerta en la lista de proyectos (RF-21, RF-22, RF-23);
  - "Exportar paquete" e "Importar paquete" en los menús de la lista (RF-24 a RF-28).

## Modelo de datos

### El fichero
La copia completa y el paquete son el mismo formato ZIP; cambia `kind`:

| Entrada | Copia completa | Paquete | Contenido |
|---|---|---|---|
| `manifest.json` | sí | sí | Descripción y sumas (abajo) |
| `data.db` | sí | sí | Base de datos SQLite con el esquema de Room de `schemaVersion` y solo las filas copiadas |
| `settings.json` | sí | no | Ajustes que viajan (RF-2, RF-3) |

```json
{
  "format": "topophone",
  "formatVersion": 1,
  "kind": "backup",
  "schemaVersion": 6,
  "appVersion": "0.13.0",
  "createdAt": "2026-10-19T06:02:31Z",
  "projectCount": 4,
  "pointCount": 9,
  "projectId": null,
  "projectName": null,
  "files": {
    "data.db": "9f2c…(sha256 en hex)",
    "settings.json": "41ab…"
  }
}
```
- En el paquete, `kind` es `"package"` y `projectId`/`projectName` son los del proyecto (RF-26).
- `createdAt` en UTC (RNF-T10); la pantalla la muestra en hora local (RF-6).
- La copia no lleva contraseñas: en `data.db`, cada perfil NTRIP que tenía contraseña lleva `password_iv = X''` y `password_ct = X''` (marca "tenía contraseña"); los que no la tenían, `NULL` (RF-3, RF-15). Descifrar un IV vacío lanza `IllegalArgumentException`, que el repositorio ya trata como `Undecryptable` y la conexión pide la contraseña (S007/RF-6).

### Tablas que se copian

| Tabla | Copia completa | Paquete | Ids que se reasignan al importar como nuevo (RF-28) o al sustituir (RF-27) |
|---|---|---|---|
| `project_folders` | todas | no | — |
| `projects` | todas, también las de la papelera | la del proyecto | `id`; `folder_id` se fija |
| `points` | todas | las del proyecto, también las de su papelera | `id`, `project_id`; `trash_key` (= `id` en la papelera) |
| `point_history` | todas | las de sus puntos | `id`, `point_id` |
| `map_viewports` | todas | la del proyecto | `project_id` |
| `ntrip_profiles` | todas, con la marca de contraseña | no | — |

`points.copy_of` guarda el nombre del punto original, no su id: no se reasigna.

### `settings.json`
Solo viajan las claves de la tabla de abajo. Al restaurar, las que viajan y no están en el fichero se borran (vuelven al valor por defecto), y las demás claves del DataStore no se tocan (RF-8). Cada valor lleva su tipo, porque JSON no distingue un `Int` de un `Long` ni un `Float` de un `Double`. La codificación (`BackupSettingsJson`) está en `core:formats`.

```json
{
  "stores": {
    "settings": { "theme_mode": { "string": "SUN" }, "theme_before_sun": { "string": "LIGHT" } },
    "collector": { "collector_mode": { "string": "AVERAGED" }, "collector_averaging_seconds": { "int": 20 },
                   "collector_fix_only": { "boolean": true }, "collector_horizontal_limit_m": { "double": 0.03 } },
    "export": { "format": { "string": "PENZD" }, "custom_columns": { "strings": ["LATITUDE", "NAME"] } },
    "receiver": { "pole_height_m": { "double": 1.8 }, "session_space_mb": { "int": 500 } }
  },
  "appLanguage": "es"
}
```

| DataStore | Claves que viajan | Claves del móvil (no viajan ni se tocan) |
|---|---|---|
| `settings` | todas | — |
| `projects`, `collector`, `export`, `map` | todas | — |
| `receiver` | `pole_height_m`, `session_space_mb`, `corrections_alarm_s` | `last_receiver_address`, `last_receiver_name`, `notifications_asked`, `ntrip_last_profile_id`, `ntrip_was_active`, `ntrip_legacy_imported` y las heredadas de S002 |
| `backup` (nuevo) | ninguna | todas |

El idioma no está en un DataStore (lo guarda AppCompat): `appLanguage` se lee y se aplica con `AppLanguageStore`, una interfaz de `data` que implementa `app`.

### DataStore "backup" (`AutoBackupPreferences`)
| Clave | Tipo | Uso |
|---|---|---|
| `folder_uri` | String | Carpeta elegida, con el permiso persistido (RF-17) |
| `last_success_ms` | Long | Última copia automática guardada bien (RF-18, RF-20) |
| `failing` | Boolean | La última copia automática falló (RF-21) |
| `pending_changes` | Boolean | Hay cambios sin copiar (RF-18) |
| `notice_dismissed_ms` | Long | Cuándo se cerró el aviso (RF-23) |

### Ficheros privados de deshacer (RF-11)
`filesDir/backups/undo/`:
- `before-restore.zip`: la copia completa del estado anterior (mismo formato, con las marcas de contraseña);
- `ntrip-secrets.json`: `{ "<id del perfil>": { "iv": "<base64>", "ct": "<base64>" } }` con las contraseñas tal como están en la base de datos, cifradas con la clave del Keystore. No salen nunca del almacenamiento privado (RNF-T11).

Sin cambios en el esquema de Room (sigue en la versión 6).

## Algoritmos

### A1. Instantánea de la base de datos (RF-2, RF-24, RNF)
1. Abrir una conexión propia de solo lectura sobre el fichero de la base de datos (`SQLiteDatabase.openDatabase(path, OPEN_READONLY)`) y empezar una transacción diferida. Con WAL, la conexión ve una instantánea y no bloquea al escritor de Room: un punto se puede guardar mientras dura la copia.
2. Crear la base de datos destino con `TopoPhoneDatabase` en un fichero de la caché (diario `TRUNCATE`, sin WAL), para que tenga el mismo esquema y la misma identidad de Room.
3. Para cada tabla de la lista, en el orden padre → hijo, `SELECT` con el filtro de la copia o del paquete e `INSERT` en el destino columna a columna según `Cursor.getType`: los `REAL` pasan como `double` sin conversión a texto (constitución nº 2).
4. En `ntrip_profiles`, sustituir la contraseña por la marca.
5. Terminar la transacción de lectura y cerrar las dos conexiones.

### A2. Escribir el ZIP (RF-1, RF-5, RF-24)
1. Instantánea (A1) y, en la copia completa, `settings.json` (`SettingsArchive.export`).
2. SHA-256 de cada fichero; `manifest.json` con las sumas y los recuentos.
3. Escribir el ZIP en un fichero temporal de la caché y después copiarlo al destino (selector, carpeta o compartir).
4. Si falla al escribir en el destino, borrar el documento a medias (`DocumentsContract.deleteDocument` o `File.delete`) e indicar que no se ha guardado (RF-5).

### A3. Leer y validar (RF-12, RF-13, RF-14)
1. Leer solo las entradas conocidas (`manifest.json`, `data.db`, `settings.json`); las demás se ignoran. Límite de 2 GB sin comprimir por entrada.
2. `format` distinto de `"topophone"`, `kind` distinto del esperado, entrada que falta o suma que no coincide → `NotTopoPhone` o `Damaged` (RF-12).
3. `formatVersion` mayor que el que entiende la app, o `schemaVersion` mayor que la versión de Room → `Newer` (RF-13).
4. Extraer `data.db` a la caché y abrirla con `TopoPhoneDatabase.build`: Room aplica las migraciones automáticas desde su versión (RF-14). `PRAGMA integrity_check` y `foreign_key_check`; si fallan, o Room no reconoce la identidad, → `Damaged`.

### A4. Restaurar (RF-6 a RF-10)
1. Validar (A3) y mostrar `createdAt`, `projectCount` y `pointCount` (RF-6).
2. Con la confirmación, si hay una medida en curso → `MeasurementRunning` (RF-16).
3. Tomar el cerrojo de copias (no hay copia automática ni otra restauración a la vez).
4. Guardar `before-restore.zip` (A2) y `ntrip-secrets.json` en `filesDir/backups/undo/`; si las copias automáticas están activas, también una copia "antes de restaurar" en su carpeta (RF-7). Si falla la privada, no se restaura (RF-10). Si falla la de la carpeta, se sigue y se marca `failing` (RF-21).
5. En una sola transacción de escritura de Room:
   1. leer las contraseñas cifradas actuales (`id → iv, ct`);
   2. borrar las tablas en orden hijo → padre;
   3. insertar todas las filas de la copia (como en A1, de la copia validada a la base de datos de la app);
   4. volver a poner la contraseña cifrada de cada perfil que estaba en el móvil y está en la copia (RF-9).
6. Aplicar `settings.json` (`SettingsArchive.restore`, que también aplica `appLanguage`).
7. Si falla el paso 5, la transacción se deshace sola. Si falla el paso 6, se repite el paso 5 con `before-restore.zip` y se vuelven a poner sus ajustes. En los dos casos el resultado es `Failed` y los datos quedan como estaban (RF-10).
8. Las sesiones grabadas y las claves del móvil no se tocan (RF-8). El controlador NTRIP ve desaparecer o cambiar el perfil en uso por su flujo de Room y cierra o vuelve a conectar (S007/RF-8, RF-9).

### A5. Deshacer (RF-11)
1. Restaurar `before-restore.zip` con A4 pasos 5 a 7, sin guardar una nueva copia previa.
2. Volver a poner todas las contraseñas de `ntrip-secrets.json` en los perfiles que existan.
3. Borrar `filesDir/backups/undo/`: la opción desaparece.

### A6. Importar un paquete (RF-25 a RF-28)
1. Validar (A3) con `kind = "package"`.
2. `existing` = proyecto activo con el id del paquete.
3. Si hay `existing`, preguntar: sustituir o importar como nuevo (RF-26). Si hay una medida en curso, no se sustituye (RF-16).
4. En una transacción:
   - **Importar con sus ids** (no hay ningún proyecto con ese id, ni activo ni en la papelera): copiar el árbol tal cual, `folder_id = NULL`.
   - **Importar como nuevo**, o el id está en la papelera: ids nuevos (UUID) para el proyecto, los puntos y el historial; `trash_key` = id nuevo en los puntos de la papelera; `folder_id = NULL`.
   - **Sustituir:** copiar el árbol del proyecto del móvil con ids nuevos y `deleted_at = ahora` (pasa a la papelera, S006/RF-22); borrar las filas antiguas (CASCADE); copiar el del paquete con sus ids y el `folder_id` del sustituido (RF-27).
5. Nombre: si otro proyecto activo tiene el nombre (sin distinguir mayúsculas), "<nombre> (importado)", "(importado 2)"… (RF-28).

### A7. Copias automáticas (RF-17 a RF-21)
- `BackupChanges` pone `pending_changes = true` con cada cambio:
  - `InvalidationTracker.createFlow("projects", "project_folders", "points", "point_history", "ntrip_profiles")`; `map_viewports` no cuenta, porque mover el mapa no es un cambio de datos;
  - los `data` de los DataStores que viajan (sin la primera emisión);
  - los cambios de idioma.
- `AutoBackupPolicy.due(now, lastSuccessMs, pendingChanges, folderSet)` = `folderSet && pendingChanges && (lastSuccessMs == null || now - lastSuccessMs >= 1 h)`.
- `AutoBackupScheduler`:
  - comprueba cada 5 min mientras vive el proceso (el servicio del receptor lo mantiene vivo en campo) y en `MainActivity.onStop` (sin cambio de configuración);
  - al elegir la carpeta hace la primera copia sin esperar (suposición de la spec).
- Una copia:
  1. `pending_changes = false`;
  2. A2 a un temporal;
  3. `createDocument` en la carpeta con el nombre `TopoPhone_auto_<AAAAMMDD-HHMM>.zip`;
  4. copiar;
  5. si todo va bien, `last_success_ms`, `failing = false` y retención: listar los hijos que encajan con `TopoPhone_auto_\d{8}-\d{4}( \(\d+\))?\.zip`, ordenar por nombre y borrar todos menos los 24 últimos (RF-19);
  6. si algo falla, `pending_changes = true` y `failing = true` (RF-21).
- Un cambio que llega durante la copia vuelve a poner `pending_changes = true` y entra en la siguiente.

### A8. Aviso de la lista de proyectos (RF-22, RF-23)
`showNotice = folder == null && projectCount >= 1 && (dismissedMs == null || now - dismissedMs >= 7 días)`.

## Contratos

```text
sealed BackupReadResult: Valid(manifest, …) | NotTopoPhone | WrongKind(expected) | Damaged | Newer
sealed RestoreResult:   Restored(projects, points) | MeasurementRunning | Failed
sealed ImportResult:    Imported(projectId, name) | NeedsChoice(existingName) | MeasurementRunning | Failed

interface BackupRepository {
  suspend fun writeBackup(out: OutputStream): Boolean                        // RF-1, RF-2, RF-3
  suspend fun read(input: () -> InputStream, kind: Kind): BackupReadResult   // RF-6, RF-12..14
  suspend fun restore(valid: Valid): RestoreResult                           // RF-7..10
  suspend fun undoRestore(): RestoreResult                                   // RF-11
  val undoAvailable: Flow<Boolean>
  suspend fun writePackage(projectId: String, out: OutputStream): Boolean    // RF-24
  suspend fun importPackage(valid: Valid, choice: ImportChoice?): ImportResult // RF-25..28
}
enum ImportChoice { REPLACE, AS_NEW }

interface BackupFolder {                                                     // DocumentsContract, sin androidx.documentfile
  suspend fun write(treeUri: String, name: String, from: File): Boolean
  suspend fun list(treeUri: String): List<String>
  suspend fun delete(treeUri: String, name: String): Boolean
}
```

- **Estado de la pantalla "Copias de seguridad":** `idle` → `reading` → `confirm(fecha, proyectos, puntos)` → `restoring` → `restored` | `error(motivo)`. Los motivos son "no es una copia de TopoPhone", "está dañada", "es de una versión más nueva de la app", "hay una medida en curso", "no se ha podido restaurar" y "no se ha podido guardar".
- **Textos:**
  - Ajustes, sección "Copias de seguridad": "Crear copia de seguridad", "Restaurar copia de seguridad", "Deshacer la restauración", "Carpeta de las copias automáticas", "Última copia automática: <fecha>", "Todavía no se ha hecho ninguna", "No se ha podido hacer la copia automática".
  - Confirmación: "Copia del <fecha>: <n> proyectos y <m> puntos. Sustituirá todos los datos del móvil. Antes se guarda una copia de lo que hay ahora."
  - Lista de proyectos: "Las copias automáticas están desactivadas." con el botón "Elegir carpeta"; "No se ha podido hacer la copia automática." con el botón "Revisar".
  - Paquetes: "Exportar paquete", "Importar paquete", "<nombre> ya está en el móvil", "Sustituir" y "Importar como nuevo".
- **Tipos MIME:** copia `application/zip`; paquete `application/octet-stream`, para que el selector no le añada ".zip" al nombre.

## Decisiones técnicas
| Decisión | Motivo | Alternativa descartada |
|---|---|---|
| La copia lleva una base de datos SQLite con el esquema de Room, no un volcado en JSON | Una copia o un paquete antiguos se abren con las mismas migraciones de Room que la app, que ya tienen tests por versión (RF-14, RNF-T6); los `double` pasan sin pasar por texto | JSON por tabla: habría que escribir y probar una conversión por cada versión del esquema |
| Instantánea con una conexión propia de solo lectura | En WAL ve un estado coherente y no bloquea al escritor de Room (RNF de la spec) | Copiar el fichero `.db` con su `-wal`: hace falta un *checkpoint*, que bloquea; transacción de Room: ocupa la conexión de escritura mientras dura la copia |
| Base de datos destino creada con `TopoPhoneDatabase` en un fichero aparte | Mismo esquema e identidad que la app, sin mantener el SQL a mano | `VACUUM INTO`: no está en el SQLite de Android 8 (API 26) |
| Restaurar reemplazando las filas en una transacción de Room | Atómico (RF-10); los flujos de Room avisan a las pantallas, sin reiniciar la app | Cambiar el fichero de la base de datos: obliga a cerrar Room y reiniciar el proceso |
| Marca `X''` en la contraseña de los perfiles que la tenían | Reutiliza S007/RF-6 (contraseña que no se puede descifrar → se pide) sin cambiar el esquema (RF-15) | Columna nueva "tenía contraseña": migración de Room solo para esto |
| Un paquete sustituido pasa a la papelera con ids nuevos y el importado se queda los suyos | Si el compañero lo vuelve a mandar, se reconoce como el mismo proyecto (RF-26) | Ids nuevos para el importado: el siguiente paquete ya no se reconocería |
| `kotlinx-serialization-json` en `core:formats`, solo con `JsonElement` (sin el plugin) | Ya está en el catálogo (lo usa `app`); el manifiesto y los ajustes se escriben con tipos | `org.json`: no existe en la JVM pura de `core`; JSON a mano: propenso a errores de escape |
| `DocumentsContract` para la carpeta de las copias automáticas | Crear, listar y borrar documentos sin dependencias nuevas (RNF-T5) | `androidx.documentfile`: dependencia nueva para cuatro llamadas |
| Comprobación cada 5 min en un ámbito de la app y al pasar a segundo plano | El proceso sigue vivo en campo gracias al servicio del receptor; "como mucho cada hora" no necesita despertar la app | WorkManager (ya descartado en S006) y `lifecycle-process`: dependencias nuevas; `MainActivity.onStop` basta para el segundo plano |
| `MeasurementInProgress` como `@Singleton` que actualiza `CollectorViewModel` | El estado de la medida vive en el ViewModel del recolector y la pantalla de copias no lo ve (RF-16) | Mover toda la medida a un singleton: cambio grande en S010 |
| La copia "antes de restaurar" de la carpeta, si falla, no detiene la restauración | La copia privada ya permite deshacer; el fallo se avisa como el de una copia automática | Detener la restauración: un problema de la tarjeta impediría restaurar |
| Las claves de los DataStores que viajan, en una lista explícita | Lo que es del móvil (receptor, permisos, estado de la sesión NTRIP) no viaja por error al añadir claves nuevas | Copiar los ficheros `.preferences_pb` enteros: se llevaría la dirección del receptor |
| Las reglas de `ExportFileName` (proyecto y fecha) se comparten dentro de `core:formats` y las usan los nombres de la copia | La misma regla de caracteres que S012/RF-19 (RF-4) | Duplicar la expresión regular |

## Estrategia de tests
- **Unitarios JVM (`core:formats`):**
  - `BackupArchiveTest`: ida y vuelta; sin manifiesto, otro `format`, `kind` cambiado, entrada que falta, suma que no coincide → `NotTopoPhone`, `WrongKind` o `Damaged`; `formatVersion` o `schemaVersion` mayor → `Newer`; entradas desconocidas ignoradas.
  - `BackupFileNamesTest` (RF-4, RF-7, RF-19).
  - `AutoBackupPolicyTest`: cuándo toca (RF-18) y qué copias sobran, sin tocar otros ficheros ni los de "antes de restaurar" (RF-19).
- **Robolectric `data`:**
  - `DatabaseSnapshotTest`: cada fila de cada tabla igual que la original, con los `double` comparados bit a bit (constitución nº 2); paquete solo con su proyecto; marca de contraseña (RF-3); un punto guardado mientras está abierta la instantánea se guarda y no entra en ella (RNF).
  - `SettingsArchiveTest`: viajan las claves de la lista; las del móvil no salen y no se tocan al restaurar (RF-2, RF-3, RF-8).
  - `BackupRestoreTest`:
    - restaurar sustituye todo (RF-8);
    - contraseña conservada (RF-9) o pedida (RF-15, `Undecryptable`);
    - fallo en mitad del paso 5 y del paso 6 con los datos como antes (RF-10);
    - deshacer con contraseñas (RF-11);
    - copia previa privada (RF-7).
  - `BackupMigrationTest`: una copia de cada versión 1 a 5 del esquema (creada con `createFromExportedSchema` de `TopoPhoneDatabaseTest`) se restaura sin perder datos (RF-14); versión 7 → `Newer` (RF-13).
  - `PackageTest`: exportar e importar con sus ids, como nuevo (ids nuevos y "(importado)"), sustituyendo (el antiguo en la papelera con ids nuevos y el nuevo en su carpeta), nombre ocupado e id en la papelera (RF-24 a RF-28).
  - `AutoBackupPreferencesTest`; `BackupChangesTest`: un punto marca cambios, mover el mapa no (RF-18).
- **Robolectric `app`:**
  - `AutoBackupSchedulerTest` con tiempo virtual:
    - una copia cada hora como mucho y solo con cambios;
    - la primera al elegir la carpeta;
    - al pasar a segundo plano;
    - fallo, aviso y reintento;
    - retención con un `BackupFolder` falso (RF-18, RF-19, RF-21).
  - `BackupsViewModelTest`: resumen y confirmación (RF-6), errores (RF-12, RF-13), medida en curso (RF-16), deshacer visible solo con copia previa (RF-11), escritura fallida (RF-5).
  - `BackupsScreenTest`: botones, fecha de la última copia, fallo (RF-1, RF-17, RF-20, RF-21).
  - `ProjectListBackupNoticeTest` (RF-21, RF-22, RF-23).
  - `PackageScreenTest`: menús, guardar o compartir, pregunta de sustituir o importar como nuevo (RF-24 a RF-26).
- **Rendimiento:** 10 000 puntos se copian en menos de 5 s y se restauran en menos de 10 s (Robolectric como cota; el móvil de referencia es de la demo).
- **Manual:** demo en el emulador con el selector de ficheros y una carpeta real (los de la spec). La puerta G1 es de la S014.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 | A2, `BackupsViewModel` | `BackupsViewModelTest`, `BackupsScreenTest` |
| RF-2, RF-3 | A1, `SettingsArchive` | `DatabaseSnapshotTest`, `SettingsArchiveTest` |
| RF-4 | `BackupFileNames` | `BackupFileNamesTest` |
| RF-5 | A2 paso 4 | `BackupsViewModelTest`, `AutoBackupSchedulerTest` |
| RF-6 | A3, A4 paso 1 | `BackupsViewModelTest` |
| RF-7 a RF-10 | A4 | `BackupRestoreTest` |
| RF-11 | A5 | `BackupRestoreTest`, `BackupsViewModelTest` |
| RF-12, RF-13 | A3 | `BackupArchiveTest`, `BackupMigrationTest`, `BackupsViewModelTest` |
| RF-14 | A3 paso 4 | `BackupMigrationTest` |
| RF-15 | Marca de contraseña | `BackupRestoreTest` |
| RF-16 | `MeasurementInProgress` | `BackupsViewModelTest`, `PackageScreenTest` |
| RF-17 a RF-21 | A7, `BackupFolder`, pantalla | `AutoBackupPolicyTest`, `AutoBackupSchedulerTest`, `BackupsScreenTest`, `BackupChangesTest` |
| RF-22, RF-23 | A8 | `ProjectListBackupNoticeTest` |
| RF-24 a RF-28 | A6, menús | `PackageTest`, `PackageScreenTest` |
