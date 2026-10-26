# Plan técnico — Spec 046

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/046-antena-y-jalon/spec.md`. Sin código.

## Estructura de módulos
- `core:survey`
  - `AntennaProfile` (reemplaza al enum `ReceiverModel`), `AntennaCatalog` con los modelos de RF-1, `SavedPole`, `PoleKind`.
  - `PoleGeometry`: altura vertical desde la inclinada y altura de antena (RF-8, RF-17, RF-18, RF-19, RF-20).
  - `ActiveGear`: el jalón y el perfil activos de un receptor ya resueltos, con su estado (válido o el motivo de que no lo sea; RF-28).
  - `GearValidation`: nombres únicos y rangos de perfiles y jalones (RF-3, RF-4, RF-9, RF-10, RF-11, RF-12).
  - `PoleHeight.parse` se conserva (RF-10, S002/RF-31).
  - `LiveTip`, `GroundPoint`, `PointMeasurement`, `PointCorrections` y `ProjectPosition` pasan a recibir un `AntennaProfile` con `RS3` por defecto en lugar de un `ReceiverModel` (RF-20, RF-21).
- `core:model`
  - `Measurement` gana `GearUsed` (nombre y altura vertical del jalón, tipo y altura introducida, perfil, desplazamiento y altura de antena; RF-24); `poleHeightM` se mantiene.
- `data`
  - Tablas `antenna_profiles`, `saved_poles` y `receiver_gear` en Room v16 (RF-5, RF-9, RF-11, RF-13, RF-30); `GearRepository`.
  - Columnas nuevas en `points` para `GearUsed` (RF-24).
  - `GearLegacyImport`: la altura de jalón guardada pasa al nuevo jalón (RF-29).
- `app`
  - Pantalla "Antena y jalón" en el menú del receptor (RF-1 a RF-8, RF-14 a RF-19) y selector rápido en el recolector, el replanteo y las coordenadas (RF-14, RF-15, RF-23).
  - `CollectorViewModel`, `StakeoutViewModel`, `LineStakeoutViewModel`, `ReceiverViewModel` y `TiltViewModel` leen el `ActiveGear` en lugar de `preferences.poleHeightM` (RF-20, RF-23, RF-27, RF-28).
  - Fichas de puntos con el jalón y la antena usados (RF-24).
- `tools/geodesia` y `tools/antena`: un conjunto `antena` con la punta del jalón con cada perfil calculada con pyproj, y la altura vertical √(i² − r²) (RNF de la spec).

## Modelo de datos
### Room v16
La versión 16 añade tres tablas y cinco columnas nulas a `points`; la migración es automática (`AutoMigration(15, 16)`). S047 y S017b pasan a v17 y v18.

| Tabla | Columnas | Notas |
|---|---|---|
| `antenna_profiles` | `id` (UUID, PK), `name` (único, sin distinguir mayúsculas), `offset_m` (REAL), `radius_m` (REAL, nulo) | Solo los propios; el catálogo vive en el código, así que no ocupa filas ni se puede borrar (RF-1) |
| `saved_poles` | `id` (UUID, PK), `name` (único), `kind` (`VERTICAL` o `SLANT`), `entered_m` (REAL) | `entered_m` es lo escrito: la altura vertical o la inclinada (RF-16) |
| `receiver_gear` | `receiver_key` (PK: la dirección Bluetooth o `replay`, la clave de `TiltPreferences`), `profile_ref` (TEXT), `active_pole_id` (UUID, nulo) | `profile_ref` es `catalog:RS3` o `own:<uuid>`; una fila por receptor que el usuario ha configurado |

Columnas nuevas de `points` (nulas en los puntos anteriores): `pole_name` (TEXT), `pole_kind` (TEXT), `pole_entered_m` (REAL), `antenna_profile` (TEXT), `antenna_offset_m` (REAL). La altura vertical y la de antena de un punto ya están en `pole_height` y en `antenna_h` menos la altura del suelo; la altura de antena se guarda también como `antenna_height_m` (REAL, nulo) para la ficha.

El punto guarda una **copia** del nombre y del desplazamiento: editar o borrar luego un perfil o un jalón no cambia ningún punto (RF-25, constitución nº 1).

Ejemplo de un punto medido con un RS4 y un jalón inclinado de 1,95 m con un perfil propio de radio 0,06 m:
```
pole_name = "Jalón corto", pole_kind = SLANT, pole_entered_m = 1.950
pole_height = 1.9491   (√(1,950² − 0,06²))
antenna_profile = "Reach RS4", antenna_offset_m = 0.095, antenna_height_m = 2.0441
```

### Preferencias
`ReceiverPreferences.poleHeightM` se conserva solo para `GearLegacyImport`, que lo lee una vez y lo borra (como `NtripLegacyImport`, S007/RF-10).

### Copias (RF-30)
Las tablas nuevas son de la base de datos y viajan en la copia completa sin cambios en `DatabaseSnapshot`; los paquetes `.topoproj` solo llevan las tablas de un proyecto, así que no las incluyen.

## Algoritmos
### A1. Altura vertical del jalón (RF-8, RF-16 a RF-19)
```
vertical(pole, profile):
  si pole.kind == VERTICAL            -> pole.entered
  si profile.radius == null           -> Invalid(NO_RADIUS)
  si pole.entered < profile.radius    -> Invalid(SLANT_SHORTER_THAN_RADIUS)
  devolver sqrt(pole.entered² − profile.radius²)
```
La altura vertical se calcula siempre que hace falta y no se guarda en el jalón, así que al cambiar el perfil (RF-19) se recalcula sola.
Referencia: pitágoras con el radio de la base y la medida del regatón al borde.

### A2. Altura de antena y punta (RF-20, RF-21, RF-22)
```
antennaHeight = vertical(pole, profile) + profile.offset
```
- `VERTICAL` y `APP`: `LiveTip` y `GroundPoint` restan `antennaHeight` como hoy (S002/RF-32, ETC_rev_3, ADR-7); solo cambia de dónde sale el desplazamiento.
- `RECEIVER` (S016/RF-14): la posición GGA es la punta; no se resta nada y la altura de antena se muestra como dato (RF-22). Al corregir un punto, S016/RF-25 desplaza la punta solo por la diferencia de jalón, que no depende del desplazamiento.
- Con `RS3` y 1,800 m da 1,934 m como hoy: `AntennaProfile.RS3.offsetM = 0.134` (RF-21).

### A3. Resolver el equipo activo (RF-7, RF-13, RF-28)
```
resolve(key):
  fila = receiver_gear[key]
  profile = fila?.profile_ref -> catálogo o propio; si falta (borrado) -> RS3
  pole    = saved_poles[fila?.active_pole_id] ?: primer jalón de la lista (por nombre "Jalón estándar" o el más antiguo)
  vertical = vertical(pole, profile)
  devolver ActiveGear(profile, pole, vertical, antennaHeight, status)
```
`status` es `OK`, `NO_POLE`, `NO_RADIUS` o `SLANT_SHORTER_THAN_RADIUS`; con cualquier otro valor, el recolector y el replanteo no dejan medir e indican cómo configurarlo (RF-28).

### A4. Validación (RF-3, RF-4, RF-9 a RF-12)
- Nombres: se comparan sin espacios a los lados y sin distinguir mayúsculas ni tildes (como S006/RF-7); perfiles hasta 40 caracteres, jalones hasta 30.
- Rangos: desplazamiento de 0 a 1 m, radio de 0 a 0,3 m, altura de 0 a 10 m (`PoleHeight.parse`, con punto o coma).
- Borrado de un perfil propio: lista los receptores que lo usan (`receiver_gear.profile_ref`) y no lo borra (RF-6). Borrado de un jalón: no si es el último (RF-11); si es el activo de algún receptor, exige elegir antes otro (RF-12).

### A5. Cambios durante una medida (RF-25 a RF-27)
- El equipo activo se lee al empezar una medida y se guarda con el punto. Un cambio durante un promediado o un modo automático se bloquea en el selector (RF-27); fuera de ellos, el cambio afecta solo a las medidas siguientes (RF-25).
- Si el punto de la sesión más reciente tiene otro equipo, el selector muestra el aviso de RF-26 una vez por cambio.

### A6. Actualización (RF-29)
`GearLegacyImport`, al abrir la app por primera vez con esta versión:
1. Si `receiver_gear`, `saved_poles` y `antenna_profiles` están vacías, lee `POLE_HEIGHT` de `ReceiverPreferences`.
2. Crea un jalón "Jalón estándar" con 1,800 m, o con la altura guardada y el nombre "Jalón anterior" si era otra, de tipo `VERTICAL`.
3. Ninguna fila de `receiver_gear`: cada receptor cae en RS3 y en ese jalón (A3).
4. Borra `POLE_HEIGHT` y no vuelve a importar.
Los puntos anteriores no se tocan: sus columnas nuevas quedan nulas y la ficha muestra solo el jalón (RF-29).

## Contratos
### `GearRepository` (data)
- `profiles(): Flow<List<AntennaProfileItem>>` (catálogo y propios), `poles(): Flow<List<SavedPole>>`.
- `gear(key: String): Flow<ActiveGear>`.
- `saveProfile`, `deleteProfile`, `savePole`, `deletePole`, `setProfile(key, ref)`, `setActivePole(key, id)`: cada una en una transacción y devuelve `GearResult` (`Ok` o el motivo: `NameTaken`, `OutOfRange`, `InUse(receivers)`, `LastPole`, `ActiveElsewhere(receivers)`).

### Estados y bloqueos
`CollectorViewModel`, `StakeoutViewModel` y `LineStakeoutViewModel` reciben `gear: Flow<ActiveGear>` y lo combinan con la clave de `CurrentTilt`. `MeasureBlocker` gana `NO_GEAR` (reemplaza a `INVALID_POLE`).

### Textos nuevos (`strings.xml`, es y en)
Antena y jalón (título, perfil, jalón, tipo vertical e inclinada, altura inclinada, altura vertical, altura de antena), los motivos de `GearResult` y de `ActiveGear.status`, el aviso del cambio a mitad de sesión (RF-26), el bloqueo durante una medida (RF-27), el aviso del modo compensado (RF-22) y el del cambio de perfil con jalones inclinados (RF-19).

### Accesibilidad (RNF-T8)
El selector lleva la etiqueta "Jalón activo <nombre>, altura de antena <valor>"; zonas táctiles de 56 dp.

## Decisiones técnicas
| Decisión | Motivo | Alternativa descartada |
|---|---|---|
| Perfiles y jalones en Room, no en DataStore | RNF-T6: una transacción con durabilidad, nombres únicos con restricción y viaje automático en la copia completa (RF-30) | DataStore con una cadena por lista (sin transacciones, sin restricciones) |
| El catálogo en el código, no en la base | No se edita ni se borra (RF-1) y se actualiza con la app | Filas del catálogo en la base (migración por cada modelo nuevo) |
| El punto guarda una copia del equipo | Constitución nº 1: editar un perfil no cambia lo medido (RF-25) | Referencia por id (un cambio reescribiría el pasado) |
| La altura vertical no se guarda en el jalón | Cambiar el perfil la recalcula sola (RF-19) | Guardarla (obliga a recalcular y se puede desincronizar) |
| `AntennaProfile` sustituye al enum `ReceiverModel` | Perfiles propios y valores por receptor (RF-3, RF-7) | Un enum con más modelos (no admite perfiles propios) |
| La clave del receptor es la de `TiltPreferences` | Un solo concepto de "receptor" en la app (S016/RF-1) | Una clave nueva (dos identidades del mismo receptor) |
| RS3 y RS2 en 0,134 m | Lo ya validado en G0 y lo que dice Emlid (ETC_rev_3, soporte); el centro de fase en L1 es 134,9 mm | Usar 134,9 mm (cambiaría los puntos ya medidos, RF-21) |
| Sin dependencias nuevas | `AGENTS.md`: nada nuevo sin justificación | — |

## Estrategia de tests
- Unitarios JVM (`core:survey`): `PoleGeometryTest` (A1: vertical, inclinada con radio, sin radio, menor que el radio, igual al radio y 0,1 mm contra √(i² − r²)); `AntennaCatalogTest` (valores de RF-1); `GearValidationTest` (nombres, rangos, coma decimal); `ActiveGearTest` (A3: sin fila, perfil borrado, sin jalón, estado); punta del jalón con cada perfil contra `fixtures/antena/` (pyproj, ≤ 1 mm) y con el RS3 y 1,800 m igual que `fixtures/` anteriores (≤ 0,1 mm).
- `data`: `TopoPhoneDatabaseTest` 15 → 16 con puntos antiguos; `GearRepositoryTest` (altas, ediciones, nombres repetidos sin distinguir mayúsculas, `InUse`, `LastPole`, `ActiveElsewhere`, una transacción por operación); `GearLegacyImportTest`; `BackupGearTest` (la copia completa lo incluye; el paquete no).
- Robolectric (`app`): pantalla "Antena y jalón", selector rápido en el recolector y el replanteo, bloqueo durante un promediado, aviso del cambio a mitad de sesión y bloqueo sin equipo válido.
- Replay NMEA: `fixtures/nmea/` con la reproducción de una medida con el RS3, el mismo punto que antes (RF-21) y con otro perfil.
- Campo: puerta G0 (el 0,134 m del RS3 frente a 134,9 mm, ≤ 1 mm de cota) y el ensayo "jalones de 1,8 m y 2,0 m" del protocolo de campo.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1, RF-2 | `AntennaCatalog` | `AntennaCatalogTest` |
| RF-3, RF-4 | A4 `GearValidation`, pantalla | `GearValidationTest`, `GearScreenTest` |
| RF-5, RF-6 | `GearRepository`, A4 | `GearRepositoryTest` |
| RF-7, RF-8 | A3 | `ActiveGearTest`, `GearScreenTest` |
| RF-9 a RF-12 | A4, `GearRepository` | `GearValidationTest`, `GearRepositoryTest` |
| RF-13 | `receiver_gear` | `GearRepositoryTest` |
| RF-14, RF-15 | selector rápido | `GearSelectorTest`, `CollectorPanelTest` |
| RF-16 a RF-19 | A1 | `PoleGeometryTest`, `GearScreenTest` |
| RF-20, RF-21 | A2 | tests de punta del jalón con los fixtures |
| RF-22 | A2 (RECEIVER) | `GearTiltReceiverTest` |
| RF-23 | los tres ViewModels | `StakeoutViewModelGearTest`, `CollectorViewModelGearTest` |
| RF-24 | columnas de `points`, fichas | `TopoPhoneDatabaseTest`, `PointDetailsGearTest` |
| RF-25, RF-26 | A5 | `CollectorViewModelGearTest` |
| RF-27, RF-28 | A5, A3 | `CollectorViewModelGearTest` |
| RF-29 | A6 | `GearLegacyImportTest`, `TopoPhoneDatabaseTest` |
| RF-30 | copias | `BackupGearTest` |

## Pendiente para otras specs
- Comprobar la altura configurada en el receptor → S043; escribirla → S044.
- Las columnas de jalón y antena en la exportación CSV no están en la spec: si se quieren, primero se añade un RF a S046 o a S012 (propuesta: "Todas las columnas", sin tocar PENZD ni PNEZD).
