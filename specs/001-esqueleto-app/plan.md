# Plan técnico — Spec 001

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/001-esqueleto-app/spec.md`. Sin código.

## Versiones (estables a octubre de 2026, comprobadas en Maven Central y Google Maven)
| Componente | Versión |
|---|---|
| Gradle (wrapper) | 9.8.1, con el daemon en JDK 21 (`gradle/gradle-daemon-jvm.properties`) |
| Android Gradle Plugin | 9.4.1, con Kotlin integrado de AGP 9 |
| Kotlin / plugin Compose | 2.4.21 |
| KSP | 2.3.12 |
| Compose BOM / Material 3 | 2026.09.00 |
| Navigation Compose | 2.10.2, rutas tipadas con kotlinx-serialization |
| Hilt | 2.60.1 (androidx.hilt 1.4.0) |
| AppCompat | 1.8.0 (idioma por app desde API 26) |
| DataStore Preferences | 1.2.1 |
| AboutLibraries | 15.3.0 (Apache-2.0) |
| JUnit Jupiter (JVM) / JUnit 4 (Android) | 6.1.3 / 4.13.2 |
| kotest-assertions | 6.2.6 |
| Robolectric | 4.17 |
| kotlinx-coroutines-test / Turbine | 1.11.0 / 1.2.1 |
| ktlint-gradle | 14.2.0 |
| Kover | 0.9.11 |
| SDK | compileSdk 37, targetSdk 36 (requisito vigente de Play), minSdk 26 |

## Estructura de módulos
```
build-logic/          build incluido: convenciones Gradle y tareas de calidad y publicación
  conventions/          plugins topophone.android.application, .android.library, .jvm.library, .compose, .hilt
  src/main/…/release    VersionInfo (versión desde git), ReleaseChecks (etiqueta, firma, huella)
  src/main/…/quality    ModuleRules (dependencias e imports prohibidos), TelemetryGuard (SDK de analítica prohibidos)
app/                  Application con Hilt, MainActivity (AppCompatActivity), navegación, pantallas, recursos es/en, icono
core/model            JVM puro: ThemeMode, AppLanguage, ThemeState (lógica de alternancia del modo sol)
core/designsystem     Android: esquemas de color Claro/Oscuro/Sol, TopoPhoneTheme, componentes (barra superior con el acceso al modo sol, estado vacío)
data/                 SettingsRepository (DataStore): tema activo y tema previo al modo sol
tools/                proyecto uv (pytest, pyyaml): trazabilidad/rf_report.py, repo/forbidden_files.py, tests de los workflows
.github/workflows/    ci.yml, release.yml
release/              certificado-sha256.txt (huella pública del certificado de publicación)
```

Cobertura de RF por módulo:
- `build-logic/release` → RF-21, RF-30–RF-34.
- `build-logic/quality` → RF-24, RF-27.
- `core:model` → RF-9, RF-12, RF-15, RF-16.
- `core:designsystem` → RF-11, RF-13, RF-14.
- `data` → RF-15.
- `app` → RF-1–RF-8, RF-10, RF-16–RF-23.
- `tools` y `.github` → RF-25, RF-26, RF-28, RF-29, RF-35.

Los módulos `core:geodesy`, `core:gnss`, `core:ntrip`, `core:survey`, `core:formats`, `core:testing` y `receiver` **no se crean** en esta spec; los crea la primera spec que los use. Las reglas de RF-27 ya los incluyen y se aplican en cuanto existan.

## Modelo de datos
- **DataStore** `settings.preferences_pb`:
  - `theme_mode`: `SYSTEM | LIGHT | DARK | SUN`. Si falta, se usa `SYSTEM`.
  - `theme_before_sun`: `SYSTEM | LIGHT | DARK`. Si falta, se usa `SYSTEM`.
- **Idioma**: no se guarda en DataStore. Lo persiste AppCompat (`AppCompatDelegate.setApplicationLocales` con el servicio `AppLocalesMetadataHolderService` y `autoStoreLocales=true`). "Sistema" equivale a una lista de idiomas vacía.
- **BuildConfig** (generado): `VERSION_NAME`, `GIT_COMMIT` (12 caracteres o `unknown`), `REPO_URL`.

Ejemplo de estado: `{ theme_mode: "SUN", theme_before_sun: "DARK" }`. Al alternar se obtiene `{ theme_mode: "DARK" }` (RF-12, RF-15).

## Algoritmos

### Alternancia del modo sol (RF-12, RF-15), en `core:model`
1. `toggleSun(state)`:
   - Si `state.mode == SUN` → `mode = state.beforeSun`.
   - Si no → `beforeSun = state.mode` y `mode = SUN`.
2. `select(state, m)`, elección en Ajustes:
   - `mode = m`.
   - Si `m != SUN`, también `beforeSun = m`.
3. El tema efectivo es la función `(mode, systemDark) → LIGHT | DARK | SUN`. `SUN` no depende de `systemDark` (RF-13).

### Versión desde git (RF-21, RF-30, RF-31, RF-33), en `build-logic`
1. Entrada: `git describe --tags --match "v*" --exact-match` y la lista de etiquetas `v*`, obtenidas con `providers.exec` para que sea compatible con la caché de configuración.
2. Si la etiqueta exacta cumple la expresión `^v(0|[1-9]\d*)\.(0|[1-9]\d{0,1})\.(0|[1-9]\d{0,1})$`:
   - `versionName = X.Y.Z`.
   - `versionCode = X·10000 + Y·100 + Z`; Y y Z ≤ 99.
3. Si no hay etiqueta exacta:
   - `versionName = "<última X.Y.Z válida o 0.0.0>-dev"`.
   - `versionCode` = el de esa última versión, o 1.
4. `verifyReleaseTag(tag, ultimaPublicada)`:
   - Falla si el formato no es válido (RF-31).
   - Falla si `semver(tag) ≤ semver(ultimaPublicada)` (RF-33).
   - `ultimaPublicada` la pasa el workflow (`gh release view --json tagName`). Si no hay ninguna, se usa 0.0.0.

### Firma y huella (RF-32, RF-34), en `build-logic`
- `verifyReleaseSigning` falla si falta cualquiera de las variables `TOPOPHONE_KEYSTORE_FILE`, `TOPOPHONE_KEYSTORE_PASSWORD`, `TOPOPHONE_KEY_ALIAS` o `TOPOPHONE_KEY_PASSWORD`, o si el fichero no existe.
- `verifyReleaseCertificate`:
  - Ejecuta `apksigner verify --print-certs` sobre el APK.
  - Extrae `Signer #1 certificate SHA-256 digest`.
  - Lo compara, normalizado (minúsculas, sin `:`), con `release/certificado-sha256.txt`.
  - Si no coinciden, falla.

### Reglas de módulos (RF-27), en `build-logic/quality`
- La tabla de dependencias permitidas es la de `docs/arquitectura.md`. Ejemplos: `core:gnss` no puede depender de `core:geodesy`; ningún `core:*` puede depender de `app`, `data` ni `receiver*`.
- `checkModuleRules`, en la raíz y conectada a `check`:
  - Recorre las dependencias de proyecto de cada módulo.
  - Comprueba que los `core:*` (salvo `core:designsystem`) aplican la convención `jvm.library`, es decir, que no usan el plugin de Android.
  - Busca en `src/main/**/*.kt` de esos módulos los imports prohibidos `android.`, `androidx.`, `dagger.` y `javax.inject.`.
  - Cada infracción se informa con su ruta y línea.

### Sin telemetría (RF-24)
- `checkNoTelemetry` resuelve `releaseRuntimeClasspath` de `app`.
- Falla si encuentra grupos de una lista prohibida: `com.google.firebase`, `com.google.android.gms:play-services-analytics*`, `com.crashlytics`, `io.sentry`, `com.microsoft.appcenter`, `com.bugsnag`, `com.datadoghq`, `com.newrelic`, `com.amplitude`, `com.mixpanel`, `io.embrace`, `com.instabug`.

### Trazabilidad (RF-26), en `tools/trazabilidad/rf_report.py`
1. Lee `docs/hoja-de-ruta.md`. Las specs `implementada` son exigibles (RF-26); las `en curso` solo se listan (RF-35).
2. Por cada spec, extrae de `specs/NNN-*/spec.md` los RF con la expresión `^- RF-(\d+):`.
3. Busca citas en `**/src/test*/**/*.kt`, `**/src/androidTest/**/*.kt`, `build-logic/**/src/test/**/*.kt` y `tools/**/test_*.py`, con la expresión `S(\d{3})[ /_]RF-?(\d+)` (insensible a mayúsculas). Así vale tanto `S001/RF-12` en comentarios o docstrings como `` `S001 RF-12 …` `` en nombres de test de Kotlin (las barras no son válidas en nombres JVM).
4. Escribe un informe por spec: RF → ficheros que lo citan.
5. Sale con 1 si hay RF huérfanos en specs `implementada`, o citas a RF que no existen. Las specs `en curso` muestran sus pendientes con código 0.

### Ficheros prohibidos (RF-29), en `tools/repo/forbidden_files.py`
- Recorre `git ls-files`.
- Prohíbe:
  - las extensiones `.pdf`, `.jks`, `.keystore`, `.p12`, `.pfx`, `.pem`, `.key`;
  - los nombres `keystore.properties`, `local.properties`, `google-services.json`;
  - el contenido que contenga `-----BEGIN [A-Z ]*PRIVATE KEY-----`.
- Lista los ficheros y sale con 1.

## Contratos

### Navegación
- Tres rutas de primer nivel, `@Serializable object Survey | Receiver | Settings`, cada una con su grafo anidado.
- La barra inferior sigue el patrón de varias pilas: `popUpTo(startDestination){ saveState = true }`, `launchSingleTop`, `restoreState`. Atrás en Receiver o Settings vuelve a Survey (RF-5), y Atrás en Survey sale de la app (RF-6).
- Settings contiene las subrutas `About` y `Licenses`.
- La sección activa, el desplazamiento (`rememberLazyListState`) y los diálogos (`rememberSaveable`) sobreviven al giro y a la muerte del proceso (RF-7, RF-8).

### Pantallas
| Pantalla | Contenido |
|---|---|
| Survey / Receiver | `EmptyState(titulo, descripcion)` (RF-4) |
| Settings | Grupo "Apariencia" (tema: 4 opciones), grupo "Idioma" (3 opciones), "Acerca de", "Licencias y atribuciones" |
| About | Nombre, `VERSION_NAME`, `GIT_COMMIT`, "Licencia GPL-3.0", enlace al repositorio (abre el navegador), aviso de no afiliación |
| Licenses | Lista de AboutLibraries a partir de los metadatos generados en compilación |

### Barra superior
`TopoPhoneTopBar(titulo)`, siempre con la acción "Modo sol", un icono de sol con `contentDescription` y una zona táctil de 56 dp como mínimo (RF-11, RNF-T8).

### Textos
- `values/strings.xml` en español (idioma por defecto, RF-17) y `values-en/strings.xml`.
- `app_name` = "TopoPhone" en los dos.
- `res/xml/locales_config.xml` generado (`generateLocaleConfig = true`).

## Decisiones técnicas
| Decisión | Motivo | Alternativa descartada |
|---|---|---|
| Convenciones en `build-logic` (build incluido) | 40 specs añadirán módulos; una sola fuente de configuración | `subprojects {}` en el build raíz (frágil y sin caché) |
| Kotlin integrado de AGP 9 en los módulos Android; `org.jetbrains.kotlin.jvm` en los `core` | Lo recomendado en AGP 9 | Aplicar `kotlin-android` (obsoleto en AGP 9) |
| Navigation Compose con rutas tipadas | Patrón estándar de varias pilas; lo necesitarán las specs con pantallas anidadas | Estado propio con `rememberSaveable` (habría que rehacerlo en S007); Navigation 3 (menos ejemplos del patrón de barra inferior) |
| `MainActivity : AppCompatActivity` y `AppCompatDelegate.setApplicationLocales` | Idioma por app también en API 26–32 (RF-18) | API nativa de `LocaleManager` (solo API 33+) |
| Tema y tema previo en DataStore; idioma en AppCompat | Cada dato tiene una única fuente | Guardar también el idioma en DataStore (dos fuentes que pueden divergir) |
| AboutLibraries para las licencias | Genera en compilación los metadatos de todas las dependencias, también indirectas (RF-23); Apache-2.0 | Lista manual (se queda desactualizada) |
| Reglas de módulos, imports y telemetría como tareas propias de `build-logic` | Sin dependencias; mensajes precisos; probadas con JUnit | Konsist (compatibilidad incierta con Kotlin 2.4); detekt 1.23 (compilado para Kotlin 2.0; detekt 2.0 sigue en alfa) |
| Estilo: ktlint + Android Lint con `MissingTranslation`, `HardcodedText` y `UnusedResources` como error | Imprescindible para RF-19 y RF-28 | detekt (ver la fila anterior); se reevaluará cuando salga detekt 2.0 estable |
| Versión derivada de la etiqueta git | La etiqueta es la única fuente de la versión (RF-30, RF-33) | `versionName` escrito a mano (se desincroniza) |
| Comprobación de la huella del certificado frente a un fichero del repositorio | Detecta una clave sustituida antes de publicar (RF-34) | Fiarse de que el secreto es el correcto |
| Herramientas del repositorio en Python con uv | Ya previsto en `tools/`; pytest prueba los scripts y los workflows | Scripts bash (sin tests) |
| Tests de estructura de los workflows (pytest + pyyaml) más actionlint en el CI | Así RF-25, RF-28 y RF-30–34 tienen test automático (constitución nº 8) | Verificación solo manual (contradice la constitución, punto 16 de la clarificación) |

## Estrategia de tests
- **JVM (JUnit 6 + kotest)**:
  - `core:model`: alternancia y selección del tema, tema efectivo (RF-9, RF-12, RF-13, RF-15).
  - `build-logic`: VersionInfo con casos `v0.1.0`, `v1.2.3`, sin etiqueta, `v1.2`, `1.2.3`, `v1.100.0` y regresión de versión; ReleaseChecks (variables ausentes, huella con y sin `:`); ModuleRules (grafo válido e inválido, import `android.` en un `core`); TelemetryGuard.
- **Robolectric + Compose UI test (JUnit 4)**:
  - `app`:
    - Navegación y estados vacíos (RF-2–RF-6).
    - Giro con `ActivityScenario.recreate()` y `StateRestorationTester` para la muerte del proceso (RF-7, RF-8).
    - Ajustes de tema con `uiMode` nocturno cambiante (RF-9, RF-10, RF-15).
    - Acceso rápido al modo sol en las tres secciones (RF-11, RF-12).
    - Idioma en `@Config(sdk = [28, 34])` (RF-16–RF-18).
    - Acerca de y licencias (RF-20–RF-23).
    - Manifiesto: id, minSdk y nombre (RF-1).
  - `data`: SettingsRepository con un DataStore temporal (RF-15).
- **Diseño (RNF)**: Robolectric con `qualifiers = "w360dp-h640dp-port"` y `"-land"`, `fontScale` 1.5 y 2.0. Se comprueba que los nodos clave se muestran o se alcanzan desplazando, y que los controles miden ≥ 56 dp (RNF-T8).
- **Contraste (RF-14)**: test JVM con la fórmula de luminancia relativa de WCAG 2.x sobre todos los pares `onX/X` del esquema Sol, ≥ 7:1.
- **Recursos (RF-19)**: test JVM que compara las claves de `values/strings.xml` y `values-en/strings.xml`, más Lint con `MissingTranslation` como error.
- **Python (pytest)**:
  - `rf_report` con specs y tests de muestra (RF-26).
  - `forbidden_files` con un repositorio git temporal (RF-29).
  - Estructura de `ci.yml`: eventos `push` y `pull_request`, pasos `check`, pytest, rf_report y forbidden_files, sin `continue-on-error` (RF-25, RF-28).
  - Estructura de `release.yml`: disparador `tags: v*`, depende del job de comprobaciones y ejecuta `verifyReleaseTag`, `verifyReleaseSigning` y `verifyReleaseCertificate` antes de `gh release create` (RF-30–RF-34).
- **Cobertura**: Kover ≥ 80 % de líneas en `core:*` (`koverVerify` en `check`).
- **Manual (validación, T24)**: tiempos del RNF (arranque < 2 s, cambio de sección < 300 ms, modo sol < 500 ms) medidos en el móvil de referencia, el modo sol al aire libre, la instalación de v0.1.0 y la actualización a v0.1.1.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 | app (manifiesto, applicationId, minSdk) | `AppManifestTest` |
| RF-2–RF-6 | Navegación | `NavigationTest` |
| RF-7, RF-8 | Navegación + estado guardable | `StateRestorationTest` |
| RF-9, RF-12, RF-13, RF-15 | `core:model` ThemeState | `ThemeStateTest` |
| RF-10 | Tema efectivo con `uiMode` | `SettingsThemeTest` |
| RF-11 | TopoPhoneTopBar en las tres secciones | `SunToggleTest` |
| RF-14 | Esquema Sol | `SunContrastTest` |
| RF-15 (persistencia) | `data` SettingsRepository | `SettingsRepositoryTest` |
| RF-16–RF-18 | Idioma con AppCompat | `LanguageTest` |
| RF-19 | Recursos + Lint | `StringsParityTest` |
| RF-20–RF-22 | About + VersionInfo | `AboutScreenTest`, `VersionInfoTest` |
| RF-23 | AboutLibraries | `LicensesScreenTest` |
| RF-24 | TelemetryGuard | `TelemetryGuardTest` |
| RF-25, RF-28 | `ci.yml` | `test_ci_workflow.py` |
| RF-26, RF-35 | `rf_report.py` | `test_rf_report.py` |
| RF-27 | ModuleRules | `ModuleRulesTest` |
| RF-29 | `forbidden_files.py` | `test_forbidden_files.py` |
| RF-30–RF-34 | ReleaseChecks + `release.yml` | `ReleaseChecksTest`, `VersionInfoTest`, `test_release_workflow.py` |
