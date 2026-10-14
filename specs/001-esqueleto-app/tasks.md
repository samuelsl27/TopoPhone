# Tareas — Spec 001

> Cada tarea dura 30 min como máximo, sigue el orden de dependencias y se implementa en un prompt propio, con los tests primero.

- [x] T1. Wrapper de Gradle 9.8.1, `settings.gradle.kts`, catálogo `libs.versions.toml`, build incluido `build-logic` con las convenciones `android.application`, `android.library`, `jvm.library`, `compose` y `hilt`; módulos `app`, `core:model`, `core:designsystem` y `data`; daemon en JDK 21. (RF-1)
      Hecho cuando: `./gradlew assembleDebug` genera el APK y `./gradlew test` termina (0 tests) sin errores.
- [x] T2. `build-logic`: VersionInfo (versión desde git, `-dev`, versionCode) y los campos BuildConfig `VERSION_NAME` y `GIT_COMMIT`. (RF-21, RF-30, RF-31, RF-33)
      Hecho cuando: `VersionInfoTest` en verde con los casos del plan y `BuildConfig.VERSION_NAME` = `0.0.0-dev` sin etiqueta.
- [x] T3. `build-logic`: ModuleRules (grafo de dependencias, convención jvm en `core`, imports prohibidos) y la tarea `checkModuleRules` conectada a `check`. (RF-27)
      Hecho cuando: `ModuleRulesTest` en verde; un import `android.` provocado a propósito en `core:model` hace fallar `check` con su ruta y línea.
- [x] T4. `build-logic`: TelemetryGuard y la tarea `checkNoTelemetry` conectada a `check`. (RF-24)
      Hecho cuando: `TelemetryGuardTest` en verde y `./gradlew check` en verde.
- [x] T5. ktlint, Android Lint (`MissingTranslation`, `HardcodedText` y `UnusedResources` como error), `checkHardcodedUiText` para Compose y Kover (≥ 80 % en `core:*`) conectados a `check`. (RF-19, RF-28)
      Hecho cuando: `./gradlew check` en verde; un texto escrito directamente en el código provocado a propósito hace fallar Lint.
- [x] T6. `tools/` como proyecto uv (pytest, pyyaml) y `trazabilidad/rf_report.py` con sus tests. (RF-26, RF-35)
      Hecho cuando: `uv run --project tools pytest tools` en verde y `rf_report.py` lista los RF de S001.
- [ ] T7. `tools/repo/forbidden_files.py` con sus tests. (RF-29)
      Hecho cuando: los tests en verde y el script pasa limpio sobre el repositorio.
- [ ] T8. `core:model`: ThemeMode, AppLanguage, ThemeState (alternancia, selección, tema efectivo). (RF-9, RF-12, RF-13, RF-15, RF-16)
      Hecho cuando: `ThemeStateTest` en verde y `koverVerify` en verde.
- [ ] T9. `data`: SettingsRepository con DataStore (tema activo y previo). (RF-15)
      Hecho cuando: `SettingsRepositoryTest` en verde (lee los valores por defecto y conserva lo guardado tras recrear el repositorio).
- [ ] T10. `core:designsystem`: esquemas Claro, Oscuro y Sol, TopoPhoneTheme, TopoPhoneTopBar y EmptyState. (RF-11, RF-13, RF-14)
      Hecho cuando: `SunContrastTest` en verde (todos los pares ≥ 7:1) y Sol con `systemDark = true` sigue en claro.
- [ ] T11. `app`: Application con Hilt, MainActivity (AppCompat), textos es/en con `app_name`, icono adaptativo provisional, `localeConfig` y recursos de idioma limitados a es/en (`localeFilters`). (RF-1, RF-17, RF-19)
      Hecho cuando: `AppManifestTest` y `StringsParityTest` en verde; la app arranca en el emulador o en Robolectric.
- [ ] T12. `app`: navegación de tres secciones con barra inferior y estados vacíos. (RF-2, RF-3, RF-4)
      Hecho cuando: `NavigationTest` (casos de RF-2 a RF-4) en verde.
- [ ] T13. `app`: Atrás, giro y restauración tras la muerte del proceso. (RF-5, RF-6, RF-7, RF-8)
      Hecho cuando: `NavigationTest` (RF-5, RF-6) y `StateRestorationTest` en verde.
- [ ] T14. `app`: Ajustes con el selector de tema conectado al repositorio y el tema Sistema reactivo. (RF-9, RF-10, RF-15)
      Hecho cuando: `SettingsThemeTest` en verde (cambiar el `uiMode` cambia el tema sin reiniciar).
- [ ] T15. `app`: acceso rápido al modo sol en las tres secciones. (RF-11, RF-12)
      Hecho cuando: `SunToggleTest` en verde en las tres secciones.
- [ ] T16. `app`: selector de idioma con el idioma por app de AppCompat. (RF-16, RF-17, RF-18)
      Hecho cuando: `LanguageTest` en verde en API 28 y 34.
- [ ] T17. `app`: pantalla Acerca de. (RF-20, RF-21, RF-22)
      Hecho cuando: `AboutScreenTest` en verde (versión, commit, licencia, repositorio, aviso).
- [ ] T18. `app`: Licencias y atribuciones con AboutLibraries. (RF-23)
      Hecho cuando: `LicensesScreenTest` en verde, incluida al menos una dependencia indirecta conocida.
- [ ] T19. Tests de diseño (RNF): 360 × 640 dp en vertical y horizontal, fuente al 150 % y 200 %, zonas táctiles ≥ 56 dp. (RNF, RNF-T8)
      Hecho cuando: `LayoutRnfTest` en verde.
- [ ] T20. `.github/workflows/ci.yml` (con actionlint), `LICENSE` (texto GPL-3.0) y `test_ci_workflow.py`. (RF-25, RF-28)
      Hecho cuando: pytest en verde y actionlint sin errores en local.
- [ ] T21. `build-logic`: ReleaseChecks y las tareas `verifyReleaseTag`, `verifyReleaseSigning` y `verifyReleaseCertificate`, con la firma de publicación leída de variables de entorno. (RF-30, RF-31, RF-32, RF-33, RF-34)
      Hecho cuando: `ReleaseChecksTest` en verde; `verifyReleaseSigning` sin variables falla con un mensaje claro.
- [ ] T22. `.github/workflows/release.yml`, `test_release_workflow.py` y `docs/publicacion.md` (generar la clave con keytool, custodia, secretos de GitHub, `release/certificado-sha256.txt`). (RF-30–RF-34)
      Hecho cuando: pytest en verde y actionlint sin errores.
- [ ] T23. **(Requiere confirmación del usuario.)** Crear el repositorio público `samuelsl27/TopoPhone`, hacer push de `main`, abrir un pull request de prueba con el CI en verde y configurar los secretos de firma (el usuario genera la clave).
      Hecho cuando: el CI sale en verde en GitHub en el pull request de prueba.
- [ ] T24. Validación final: recorrer la spec RF por RF, ejecutar `rf_report`, la demo manual y medir los RNF de tiempo en el móvil; publicar `v0.0.1` y luego `v0.0.2`. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no da RF huérfanos, v0.0.2 se instala como actualización de v0.0.1 y la demo funciona.
