# AGENTS.md — TopoPhone

## Proyecto
App Android nativa de topografía GNSS, equivalente a Emlid Flow, para receptores Emlid Reach (prioridad: **Reach RS3**).
Recibe la posición por NMEA (Bluetooth SPP o TCP), envía correcciones RTCM3 desde su propio cliente NTRIP y hace en el móvil todo lo demás:
proyectos, sistemas de coordenadas, recolección, replanteo, códigos, capas, COGO, localización e importación/exportación.
Es 100 % local: sin cuentas ni nube. Primero España (ETRS89/REGCAN95, UTM, EGM08-REDNAP).

Stack: Kotlin, Jetpack Compose, MVVM/UDF, corrutinas/Flow, Hilt, Room, DataStore, MapLibre Native.
La arquitectura y las decisiones transversales están en `docs/arquitectura.md`. La hoja de ruta está en `docs/hoja-de-ruta.md`.

## Comandos
Disponibles a partir de la spec 001; usa siempre el wrapper.
- Compilar debug: `./gradlew assembleDebug`
- Todo (tests, Lint, ktlint, reglas de módulos, telemetría, Kover): `./gradlew check`
- Tests de un módulo: `./gradlew :core:model:test`
- Herramientas del repositorio (tests): `uv run --project tools pytest`
- Trazabilidad de RF: `uv run --project tools tools/trazabilidad/rf_report.py`
- Ficheros prohibidos: `uv run --project tools tools/repo/forbidden_files.py`

## Estilo y convenciones
- Kotlin con toolchain JDK 21. Identificadores en **inglés**. Textos de usuario en **español**, siempre en `strings.xml` (nunca literales en código).
- La correspondencia de términos español ↔ código está en `docs/glosario.md`. Usa esos nombres.
- Internamente, unidades SI y ángulos en radianes. La conversión a m/ft/usft y DD/DMS/gon se hace solo en los formateadores de `core:model`.
- Los números que se escriben en ficheros usan `Locale.ROOT`; nunca la configuración regional de la interfaz.
- Las coordenadas son `Double` y nunca se redondean al guardarlas.
- Trazabilidad: tests y código citan el requisito como `S###/RF-n`; por ejemplo, `// S006/RF-3` o un test con nombre `` `S006 RF-3 ...`() ``.

## Reglas de módulos
- Ningún módulo `core:*` importa `android.*` ni usa Hilt; se construyen con constructores normales.
- `core:ntrip` no depende de ningún otro módulo. `core:gnss` no depende de `core:geodesy`. `core:survey` puede usar los dos.
- `receiver-experimental` solo se incluye en el flavor `experimental`, y el resto del código nunca depende de él.
- Ninguna dependencia nueva (Gradle o Python) sin que su `plan.md` la justifique con la alternativa descartada.

## Reglas SDD
- Antes de tocar código, lee `docs/constitution.md`, `docs/rnf-transversales.md` y la spec activa (`specs/NNN-*/spec.md`, `plan.md`, `tasks.md`).
- **Una tarea por prompt.** Implementa SOLO la tarea Tn que se pide: tests primero, luego código. Marca la tarea en `tasks.md` y **PARA**. No empieces la siguiente.
- No construyas ningún comportamiento que no esté en la spec activa. Si falta una decisión, detente y pregunta.
- No modifiques `specs/` ni `docs/constitution.md` salvo petición explícita. Los cambios de requisitos van primero a la spec, y se muestra el diff.
- Fases de cada spec y sus prompts: `docs/prompts-sdd.md`. Para crear o revisar specs usa la skill `spec-generator`.
- Nada de código, nombres ni recursos de Emlid. TopoPhone tiene interfaz y textos propios.

## Al terminar cualquier tarea
- Ejecuta `./gradlew check` (y `uv run --project tools pytest` si tocaste `tools/` o `.github/`) y confirma en tu respuesta que todo pasa.
- Indica qué `S###/RF-n` cubre la tarea y deja marcada la casilla en `tasks.md`.
