# Plan técnico — Spec 017b

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/017b-fotos-de-puntos/spec.md`. Sin código.

> **Aviso al retomarla**: S018 (replanteo) pasó por delante y usa la versión 10 de la base de datos; las tablas de fotos pasan a la versión 11 (`AutoMigration(10, 11)`, `TopoPhoneDatabaseTest` 10 → 11) donde este plan dice 10.

## Estructura de módulos
- `core:model`:
  - `PhotoPolicy`: máximo de 10 fotos por punto, 2.048 px de lado largo, el tamaño reducido de una foto y cuántas fotos de una selección caben (A1; RF-13, RF-14, RF-18);
  - `PointPhoto`, `PhotoOrigin`, `PhotoNames` (nombre de cada foto) y `PhotoStats` (cuántas y cuánto ocupan; RF-9, RF-11, RF-27);
  - `PhotoDestination`: a qué punto va la foto del recolector, según haya medida, modo automático o último punto guardado (A2; RF-2, RF-5 a RF-8);
  - `PointHistory` gana los sucesos de foto añadida y borrada, y `PointSummary.photoCount` (RF-23, RF-26).
- `core:formats` → `BackupArchive` admite las fotos como entradas `photos/<id>.jpg`, con sus sumas, y el manifiesto un campo opcional `externalPhotos` para las copias automáticas (A5, A6; RF-28 a RF-30).
- `data`:
  - tablas `point_photos` y `photo_events`, migración 9 → 10 y `DatabaseSnapshot.TABLES` con ellas (A3; RF-20, RF-23, RF-28);
  - `PhotoStore`: reducir y guardar la foto con sus metadatos, ficheros en espera, y barrido de los ficheros sin fila (A1, A4; RF-15 a RF-20);
  - `PointRepository`: guardar el punto con sus fotos en espera, añadir y borrar fotos, sus sucesos, el recuento por punto y por proyecto (A3; RF-2, RF-12, RF-22, RF-23, RF-26, RF-27);
  - papelera de puntos y de proyectos con sus fotos (A4; RF-24, RF-34);
  - `BackupRepository`: la copia completa y el paquete con fotos, la restauración con cambio de carpeta de fotos y vuelta atrás, y la restauración de una copia automática con fotos de la carpeta (A5, A7; RF-28, RF-29, RF-32, RF-33);
  - `BackupFolder` gana una subcarpeta propia para las fotos y la lectura de un fichero (A6; RF-30, RF-31).
- `app`:
  - `CollectorViewModel`: fotos en espera, destino, añadir de cámara y de galería, descartarlas al cancelar y guardarlas con el punto (A2, A3; RF-1 a RF-8, RF-13 a RF-16);
  - panel del recolector: "Foto" y "Galería", el destino y el aviso al cancelar (RF-1, RF-3, RF-7, RF-8);
  - ficha del punto: miniaturas, visor a pantalla completa, añadir, borrar y el historial con los sucesos de foto (A8; RF-9 a RF-17, RF-22, RF-23);
  - lista de objetos con la marca de fotos y datos del proyecto con el recuento (RF-26, RF-27);
  - `AutoBackupScheduler`: sube las fotos nuevas y aplica la retención (A6; RF-30, RF-31); pantalla de copias con el aviso de fotos que faltan (RF-33).
- `tools`: ninguno. Las fotos de los tests se generan en el propio test (sin dependencias de Python nuevas).

## Modelo de datos

### Fotos (Room v10)
```
point_photos(
  id TEXT PRIMARY KEY,                       // UUID; el fichero es photos/<id>.jpg
  point_id TEXT NOT NULL → points(id) ON DELETE CASCADE, INDEX,
  name TEXT NOT NULL,                        // "<punto>_<AAAAMMDD-HHMMSS>.jpg", único en el punto
  origin TEXT NOT NULL,                      // CAMERA | GALLERY
  taken_at INTEGER NOT NULL,                 // fecha de la foto (UTC, ms)
  added_at INTEGER NOT NULL,                 // cuándo se añadió
  size_bytes INTEGER NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL
)
photo_events(
  id TEXT PRIMARY KEY,
  point_id TEXT NOT NULL → points(id) ON DELETE CASCADE, INDEX,
  at INTEGER NOT NULL,                       // cuándo ocurrió
  action TEXT NOT NULL,                      // ADDED | DELETED
  photo_name TEXT NOT NULL, photo_taken_at INTEGER NOT NULL
)
```
- El orden de las fotos es el de `added_at` y, si empatan, el de su id (suposición de la spec).
- `point_photos` no guarda el contenido: el fichero está en `filesDir/photos/<id>.jpg`. Una fila sin fichero es una foto que no se puede abrir (RF-17); un fichero sin fila es huérfano y se barre (A4).
- Las fotos de una medida en curso no tienen fila: son ficheros en `filesDir/photos/pending/<id>.jpg` hasta que se guarda el punto (A3).
- `AutoMigration(9, 10)` sin especificación: solo tablas nuevas.

### Ficheros
```
filesDir/photos/<id>.jpg           fotos de puntos guardados
filesDir/photos/pending/<id>.jpg   fotos de una medida sin guardar
cacheDir/photo-capture/<n>.jpg     lo que escribe la cámara del sistema (se borra al terminar)
<carpeta de copias>/TopoPhone_fotos/<id>.jpg   fotos de las copias automáticas (A6)
```
`file_paths.xml` gana `photo-capture/` para que la cámara del sistema pueda escribir con el `FileProvider` de la app.

### En la copia y el paquete
- Copia completa y paquete: entradas `photos/<id>.jpg` en el ZIP; cada una con su suma en `manifest.files`.
- Copia automática: ninguna entrada de fotos; el manifiesto lleva `externalPhotos: { "<id>.jpg": "<sha256>" }` con las fotos que usan sus puntos. `FORMAT_VERSION` sigue en 1: el campo es opcional.

## Algoritmos

### A1. Reducir y guardar una foto (RF-15, RF-16, RF-18, RF-19, RF-20)
1. Se lee la foto de su `Uri` (cámara: fichero de `photo-capture/`; galería: `content://`, solo lectura). Nunca se escribe en ella (RF-19).
2. Se leen sus dimensiones sin decodificarla. Si es un JPEG con el lado largo ≤ 2.048, se copia tal cual al fichero de destino (no se recomprime, sin pérdida); si no, se decodifica con `inSampleSize` potencia de 2 lo mayor posible sin quedar por debajo de 2.048 px de lado largo, se escala a 2.048 exactos conservando la proporción y se escribe en JPEG con calidad 85.
3. Los metadatos de la foto original (fecha, orientación, ubicación, cámara…) se copian al fichero nuevo con `ExifInterface` del framework (RF-18). La orientación no se aplica a los píxeles: se conserva como atributo, y el visor de la app la aplica al mostrar.
4. Se escribe en `photos/pending/<id>.jpg.tmp` y se renombra al terminar: nunca queda una foto a medias (RF-20). Cualquier fallo (fichero ilegible, sin espacio) borra lo escrito y devuelve el motivo (RF-15).
5. La fecha de la foto es `DateTimeOriginal` de sus metadatos y, si no la tiene, el momento de añadirla (término de la spec).
6. Un PNG o un HEIC de la galería se escribe como JPEG; si el formato no tiene metadatos EXIF, solo quedan la fecha de añadido y el origen.

### A2. Destino de la foto del recolector (RF-2, RF-5 a RF-8)
`PhotoDestination.resolve(measuring, autoRunning, lastSaved)`:

| Situación | Destino |
|---|---|
| modo automático en marcha | último punto guardado por el modo (RF-6); sin él, ninguno (RF-7) |
| medida en marcha, en pausa o esperando confirmar, sin modo automático | el punto en medida, con las fotos en espera (RF-2) |
| nada en marcha | último punto guardado desde el recolector (RF-5); sin él, ninguno (RF-7) |

- El último punto guardado se olvida al cerrar el recolector y se sustituye al guardar otro. Si el punto desaparece (borrado a la papelera) el destino pasa a ninguno.
- Cuántas fotos lleva el destino: las en espera si es una medida, o las de la base de datos si es un punto guardado (RF-8).
- Una función pura en `core:model`; el `CollectorViewModel` la llama con su estado.

### A3. Guardado con el punto y fotos en una transacción (RF-2, RF-4, RF-12, RF-20, RF-23)
- `create(point, photos: List<StagedPhoto>)`: se mueven los ficheros de `pending/` a `photos/` (renombrado en el mismo sistema de ficheros) y, en una sola transacción de Room, se insertan el punto, sus frases NMEA y las filas de sus fotos. Si la transacción falla, los ficheros vuelven a `pending/`. No se anota ningún suceso: las fotos de la medida son parte de la creación del punto.
- `addPhotos(pointId, staged)`: como arriba para un punto que ya existe, con el límite de 10 comprobado dentro de la transacción (RF-14) y un `photo_events` `ADDED` por foto (RF-23). No marca el punto como editado: no toca `points` ni `point_history`.
- `deletePhoto(photoId)`: en una transacción, borra la fila y anota `DELETED` con el nombre y la fecha de la foto; después borra el fichero. Si el borrado del fichero falla, el barrido lo recoge (A4) (RF-22, RF-23).
- Las fotos en espera de una medida que acaba sin guardar se borran de `pending/` (RF-4); el barrido de `pending/` al arrancar la app cubre un cierre a media medida.

### A4. Ficheros sin fila y borrados en cascada (RF-24, RF-34)
- Las filas de `point_photos` se van con su punto por la cascada (borrado definitivo, vaciado de la papelera, borrado definitivo de un proyecto). Los ficheros no: `PhotoStore.sweep()` borra los de `photos/` sin fila y lo vacío de `pending/` que no sea de la medida en curso.
- Se llama a `sweep()` al arrancar la app y tras `deletePermanently`, `purgeTrash` de puntos y de proyectos, `deleteForever` de un proyecto, la restauración y el deshacer de la restauración, y la importación de un paquete que sustituye un proyecto.
- Papelera de puntos y de proyectos: no hay nada que hacer, las filas siguen en su sitio y las fotos con ellas; al restaurar vuelven con el punto (RF-24, RF-34).
- Duplicar un punto no copia filas de foto (RF-25).

### A5. Copia completa, restauración y paquete con fotos (RF-28, RF-29)
- **Escribir**: `BackupArchive.write` recibe además los ficheros `photos/<id>.jpg` de todas las filas de `point_photos` (en la copia completa) o de los puntos del proyecto, también los de la papelera (en el paquete). Las sumas van en el manifiesto. Las entradas se escriben una a una; ningún fichero pasa de 2 GiB (ya vale para el límite de entrada).
- **Leer**: `BackupArchive.read` extrae también las entradas `photos/<id>.jpg` en `extractTo/photos/`. El nombre de entrada se valida con `^photos/[A-Za-z0-9-]{1,64}\.jpg$` (nada de `..` ni rutas absolutas) y cada entrada debe estar en el manifiesto con su suma (un ZIP con fotos de más, o con una suma distinta, está dañado).
- **Restaurar**: primero se copian las fotos extraídas a `photos.incoming/`. Dentro del gancho `beforeCommit` de la transacción que sustituye las tablas se intercambian los directorios: `photos` pasa a `photos.old` y `photos.incoming` a `photos`. Si la transacción falla después, se vuelve a intercambiar y la base de datos se deshace sola (RF-10 de S013). `photos.old` se borra solo cuando todo ha terminado.
- **Deshacer**: la copia del estado anterior (`before-restore.zip`) es una copia completa y lleva las fotos; deshacer repite el mismo intercambio (RF-28 / S013/RF-11).
- **Importar paquete**: las fotos extraídas se copian a `photos/` antes de la transacción, con el id que lleven en las filas. Si el proyecto se importa con ids nuevos (nuevo, o id ocupado) o sustituye al del móvil, las fotos cuyo id ya existe reciben un id nuevo y su fila se actualiza; el fichero se copia con el nombre nuevo. Una foto idéntica puede quedar duplicada en el disco: no se deduplica.
- La copia de seguridad crece: la primera copia de un móvil con 1 GB de fotos tarda y ocupa. S013/RF-5 cubre el "sin espacio".

### A6. Copias automáticas con las fotos aparte (RF-30, RF-31)
- Carpeta `TopoPhone_fotos/` dentro de la carpeta de copias, creada la primera vez. Contiene `<id>.jpg`; ningún otro fichero de la carpeta de copias se toca (S013/RF-19).
- Orden de `backupNow()`:
  1. se copian a la subcarpeta las fotos de las filas que aún no están en ella (nombre = id; ninguna se repite);
  2. se escribe el ZIP (sin entradas de fotos) con `externalPhotos` = id → suma de las fotos de las filas;
  3. se borran los ZIP que sobran (24 conservados, S013/RF-19);
  4. retención: se lee el manifiesto (primera entrada) de cada ZIP que queda, y se borran de la subcarpeta las fotos que ninguno nombra.
- Si falla el paso 1 o el 2, la copia automática falla como cualquier otra (S013/RF-21): se avisa y se reintenta. Un ZIP nunca nombra una foto que no esté ya en la subcarpeta.
- `before-restore-*.zip` de la carpeta (S013/RF-7) es una copia completa con fotos dentro y nunca se borra: no entra en la retención.
- La subida inicial de todas las fotos puede tardar; se hace en la comprobación de las copias automáticas y no bloquea la interfaz.

### A7. Restaurar una copia automática (RF-32, RF-33)
- `Backups.read` devuelve el manifiesto con `externalPhotos`. Si no está vacío, `restore` recibe un `ExternalPhotos` (el proveedor de la subcarpeta de la carpeta configurada) y, antes del intercambio de A5, copia a `photos.incoming/` cada foto que pida el manifiesto: la abre, comprueba su suma y la guarda; si falta o la suma no coincide, cuenta una foto no recuperada.
- Las filas de fotos sin fichero se restauran igual (el punto se restaura con ella: se abrirá como RF-17) y `RestoreResult.Restored` lleva `missingPhotos`; la pantalla de copias dice cuántas (RF-33).
- Si no hay carpeta configurada o el usuario elige un ZIP de otro sitio, todas las fotos cuentan como no recuperadas.

### A8. Mostrar las fotos (RF-9, RF-10, RF-11, RF-17)
- Miniaturas: decodificación en segundo plano con `inSampleSize` hasta unos 256 px, caché `LruCache` de la app (sin librerías de imágenes). La orientación se lee de los metadatos y se aplica con una matriz.
- Visor a pantalla completa: `Dialog` con `HorizontalPager` y zoom con `transformable`; carga la foto entera con `inSampleSize` según la pantalla. Con la foto abierta, fecha, origen y tamaño del fichero.
- Fichero que no se puede decodificar: en su lugar, un recuadro con su nombre y "No se puede abrir la foto" (RF-17); las demás siguen.

## Contratos

### Estado del recolector (`CollectorUiState`)
- `photos: PhotoUi` con `destination` (nombre del punto o "ninguno" con su motivo), `count` (de 10), `canAdd`, `adding` y el último error (RF-1, RF-7, RF-8, RF-15).
- `cancel()` con fotos en espera no cancela: `pendingCancel = n` hasta que el usuario confirma o vuelve (RF-3).
- Ya existe `MeasurementInProgress`; las fotos en espera no cambian lo que bloquea (S013/RF-16).

### Botones y diálogos
- Recolector: "Foto" y "Galería" en una fila, de 56 dp, junto a "Medir"; el texto del destino ("Foto para el punto 12 · 3 de 10").
- Ficha: sección "Fotos" con las miniaturas, "Foto" y "Galería"; cada miniatura abre el visor; el visor lleva "Borrar".
- Diálogos: descartar fotos al cancelar (RF-3), borrar una foto (RF-22), y avisos de límite y de error.

### Cámara y galería
- Cámara: `ActivityResultContracts.TakePicture` con un `Uri` del `FileProvider` apuntando a `cacheDir/photo-capture/`. No se declara el permiso `CAMERA`: la cámara del sistema hace la foto y no hace falta pedirlo (RNF-T1).
- Galería: `ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)`, con el selector del sistema (sin permiso de lectura de medios); el recolector corta lo que no cabe (RF-13).
- Cierre de la app con la cámara abierta: el destino y el fichero de captura se guardan en el `SavedStateHandle` y se recuperan al volver; si el destino era una medida que ya no existe, la foto se descarta; si era un punto guardado, se añade.

### Textos nuevos (en `strings.xml`, es y en)
Foto, Galería, "Foto para el punto %s · %d de %d", "Mide un punto primero", "Fotos", "%d fotos", "Máximo de 10 fotos por punto", "%d fotos no caben y no se han añadido", "No se ha podido guardar la foto: %s", "No se puede abrir la foto", "¿Descartar %d fotos?", "¿Borrar esta foto?", "Foto añadida"/"Foto borrada" (historial), "%d fotos (%s)", "%d fotos no se han podido recuperar".

### Accesibilidad (RNF-T8)
- Cada miniatura: "Foto 2 de 5 del punto 12" y "Abrir"; el visor anuncia "Foto 2 de 5" al pasar; los avisos de límite y de error son regiones vivas.

## Decisiones técnicas
| Decisión | Por qué | Alternativa descartada |
|---|---|---|
| `ExifInterface` y `BitmapFactory` del framework | minSdk 26 los tiene; sin dependencias nuevas | `androidx.exifinterface` y una librería de imágenes: dependencia nueva sin necesidad |
| Cámara del sistema con `TakePicture` y `FileProvider` | Sin permiso `CAMERA`, sin pantalla de cámara propia que mantener (RNF-T1) | CameraX: dependencia, permiso y una interfaz entera |
| `PickMultipleVisualMedia` | Sin permiso de lectura de medios; el selector del sistema ya deja elegir varias | `READ_MEDIA_IMAGES` y una galería propia |
| Un JPEG al 85 % con el lado largo a 2.048 px; las menores se copian tal cual | 0,5 a 1 MB por foto y sin pérdida si no hace falta reducir | PNG o sin reducir: 3 a 8 MB por foto |
| Metadatos copiados con `ExifInterface`, orientación como atributo | El usuario pidió conservarlos tal cual (RF-18); girar los píxeles perdería la orientación original | Aplicar la rotación a los píxeles |
| Tabla `point_photos` y ficheros fuera de la base de datos | Una foto de 1 MB en la base reventaría la ventana del cursor (≈ 2 MB, como pasó con las frases NMEA, S017/A6) | BLOB en la tabla |
| Fotos en espera en `photos/pending/` y movidas al guardar | Una medida cancelada no deja filas; un cierre a media medida solo deja ficheros que el barrido borra | Insertarlas con el punto ya creado (no existe todavía) o guardarlas en memoria (12 MP cada una) |
| `photo_events` aparte de `point_history` | `point_history` guarda versiones completas del punto y compara entre ellas: un suceso de foto no es una versión y marcaría el punto como editado (S011/RF-12) | Reutilizar `point_history` con columnas nulas |
| El barrido borra ficheros sin fila, además de borrarlos al eliminar | Las cascadas de Room no tocan ficheros; así cubre papelera, proyectos, restauración y cierres a medias | Borrar a mano en cada sitio: se olvida alguno |
| Fotos de la copia completa y del paquete dentro del ZIP (A5) | Un solo fichero para mover o guardar, con las sumas del manifiesto | Un ZIP aparte de fotos: dos ficheros que se pierden por separado |
| Fotos de las copias automáticas en una subcarpeta con el id como nombre (A6) | Una sola copia de cada foto, sin multiplicar el espacio por 24 (decisión del usuario) | Dentro de cada ZIP, o fuera de las automáticas |
| La retención lee el manifiesto de los ZIP que quedan (A6) | Sin índice aparte que pueda desincronizarse; son 24 lecturas pequeñas | Un fichero `index.json` en la subcarpeta |
| `FORMAT_VERSION` sigue en 1; `externalPhotos` es opcional | Un campo nuevo opcional lo ignora una app anterior y no rompe nada | Subir la versión del formato |
| Intercambio de directorios dentro de `beforeCommit` (A5) | La transacción de Room todavía se puede deshacer y el intercambio también | Copiar sobre `photos/`: un fallo deja una mezcla |
| Las fotos idénticas duplicadas en un paquete que sustituye un proyecto no se deduplican | Simple y correcto; el coste son unos MB | Hash y enlaces: complejidad sin necesidad |
| `AutoMigration` 9 → 10 sin especificación | Solo tablas nuevas | `RoomDatabase.Migration` a mano |

## Estrategia de tests
- **Unitarios JVM (`core:model`)**:
  - `PhotoPolicyTest`: tamaños reducidos (apaisada, vertical, menor que el máximo, cuadrada), cuántas caben de una selección y el límite de 10 (RF-13, RF-14, RF-18).
  - `PhotoDestinationTest`: las filas de A2, también el modo automático con otro punto en medida y la desaparición del último punto (RF-2, RF-5 a RF-8).
  - `PhotoNamesTest`: nombre único en el punto con dos fotos en el mismo segundo (RF-23).
  - `PointHistoryTest` con los sucesos de foto entre las versiones (RF-23).
- **`core:formats`**: `BackupArchiveTest` con fotos: ida y vuelta, una entrada con `..`, una suma cambiada, una foto de más, `externalPhotos` y un manifiesto sin ellas (RF-28 a RF-30).
- **`data` (Robolectric, gráficos `NATIVE`, fotos JPEG generadas en el test con `Bitmap` y `ExifInterface`)**:
  - `PhotoStoreTest`: una foto de 12 MP baja a 2.048 px, una de 1.600 px se copia sin recomprimir, la fecha, la orientación y la ubicación se conservan, la original de la galería no cambia, un fichero ilegible no deja nada y un fallo de escritura tampoco (RF-15, RF-18, RF-19, RF-20).
  - `PointRepositoryPhotosTest`: guardar el punto con sus fotos en una transacción, un fallo a medias no deja ni filas ni ficheros, el límite de 10, borrar con el suceso, añadir sin marcar el punto como editado, duplicar sin fotos (RF-2, RF-12, RF-14, RF-20, RF-22, RF-23, RF-25).
  - `PhotoCleanupTest`: la cascada y el barrido tras borrar un punto de la papelera, vaciar la papelera y borrar un proyecto; restaurar devuelve las fotos (RF-24, RF-34).
  - `TopoPhoneDatabaseTest` 9 → 10: los puntos conservan sus datos y las tablas nuevas quedan vacías; `DatabaseSnapshotTest` con las tablas nuevas; `PointRepositoryExportTest` sin cambios.
  - `BackupRepositoryPhotosTest`: copia completa con fotos, restaurar, un fallo en el intercambio que deja las fotos como estaban, deshacer, paquete con fotos importado como nuevo y sustituyendo, un paquete con ids repetidos (RF-28, RF-29).
  - `AutoBackupPhotosTest` con una `BackupFolder` falsa con subcarpeta: copia las fotos nuevas una vez, no repite en la siguiente copia, la retención conserva las que usa algún ZIP y borra las demás, y una foto que falta se cuenta al restaurar (RF-30 a RF-33).
- **Robolectric (`app`)**:
  - `CollectorViewModelPhotosTest`, con la `ReceiverSession` real y las épocas de una en una: fotos durante la medida y al guardar, cancelar con confirmación, medida fallida, fuera de tolerancia, modo automático, sin punto, el límite y el cierre de la app con la cámara abierta (RF-1 a RF-8, RF-13 a RF-16).
  - `CollectorPanelPhotosTest`: los botones en cada situación, el destino, la confirmación y los textos (RF-1, RF-3, RF-7, RF-8).
  - `PointPhotosScreenTest`: miniaturas, visor con paso entre fotos, datos de la foto, foto ilegible, añadir y límite, borrar con confirmación y el historial con los sucesos (RF-9 a RF-17, RF-22, RF-23).
  - `ObjectListPhotosTest` y `ProjectDataPhotosTest`: la marca con el recuento y las cifras del proyecto (RF-26, RF-27).
  - `BackupsScreenTest` con el aviso de fotos no recuperadas (RF-33).
- **Extremo a extremo** (`PhotosEndToEndTest`): una medida con foto, guardar, foto tras guardar, ficha, borrar a la papelera, restaurar, copia completa, restaurar y deshacer con las fotos presentes.
- **Rendimiento** (`PhotoPerformanceTest`): reducir una foto de 12 MP en menos de 2 s y que la ficha con 10 fotos se componga en menos de 1 s (RNF).
- **Manual o de campo**: demo en el emulador (criterios de finalización); en campo, fotos con la cámara real y una copia automática con unos 100 puntos con fotos.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 | Botones, contratos de cámara y galería | `CollectorPanelPhotosTest`, `CollectorViewModelPhotosTest` |
| RF-2 | A2, A3 | `PhotoDestinationTest`, `CollectorViewModelPhotosTest`, `PointRepositoryPhotosTest` |
| RF-3 | `pendingCancel` | `CollectorViewModelPhotosTest`, `CollectorPanelPhotosTest` |
| RF-4 | A3 | `CollectorViewModelPhotosTest` |
| RF-5 | A2 | `PhotoDestinationTest`, `CollectorViewModelPhotosTest` |
| RF-6 | A2 | `PhotoDestinationTest`, `CollectorViewModelPhotosTest` |
| RF-7 | A2, panel | `PhotoDestinationTest`, `CollectorPanelPhotosTest` |
| RF-8 | A2, panel | `PhotoDestinationTest`, `CollectorPanelPhotosTest` |
| RF-9 | A8 | `PointPhotosScreenTest` |
| RF-10 | A8 | `PointPhotosScreenTest` |
| RF-11 | A8 | `PointPhotosScreenTest` |
| RF-12 | A3 | `PointRepositoryPhotosTest`, `PointPhotosScreenTest` |
| RF-13 | `PhotoPolicy` | `PhotoPolicyTest`, `CollectorViewModelPhotosTest` |
| RF-14 | `PhotoPolicy`, A3 | `PhotoPolicyTest`, `PointRepositoryPhotosTest` |
| RF-15 | A1 | `PhotoStoreTest`, `CollectorViewModelPhotosTest` |
| RF-16 | contratos | `CollectorViewModelPhotosTest`, `PointPhotosScreenTest` |
| RF-17 | A8 | `PointPhotosScreenTest` |
| RF-18 | A1 | `PhotoPolicyTest`, `PhotoStoreTest` |
| RF-19 | A1 | `PhotoStoreTest` |
| RF-20 | A1, A3 | `PhotoStoreTest`, `PointRepositoryPhotosTest` |
| RF-21 | A3 | `PointRepositoryPhotosTest` |
| RF-22 | A3 | `PointRepositoryPhotosTest`, `PointPhotosScreenTest` |
| RF-23 | A3, `photo_events` | `PointRepositoryPhotosTest`, `PointHistoryTest`, `PointPhotosScreenTest` |
| RF-24 | A4 | `PhotoCleanupTest` |
| RF-25 | A4 | `PointRepositoryPhotosTest` |
| RF-26 | `PointSummary.photoCount` | `ObjectListPhotosTest` |
| RF-27 | `PhotoStats` | `ProjectDataPhotosTest` |
| RF-28 | A5 | `BackupArchiveTest`, `BackupRepositoryPhotosTest`, `DatabaseSnapshotTest` |
| RF-29 | A5 | `BackupRepositoryPhotosTest` |
| RF-30 | A6 | `AutoBackupPhotosTest` |
| RF-31 | A6 | `AutoBackupPhotosTest` |
| RF-32 | A7 | `AutoBackupPhotosTest` |
| RF-33 | A7 | `AutoBackupPhotosTest`, `BackupsScreenTest` |
| RF-34 | A4 | `PhotoCleanupTest` |
