# Tareas — Spec 044

> Cada tarea dura 30 min como máximo, sigue el orden de dependencias y se implementa en un prompt propio, con los tests primero. Cada tarea cierra con `./gradlew check` en verde (se repite si falla un test ajeno intermitente), la casilla marcada y un commit `S044 Tn: …` en `main`.

## Aislamiento y flavors
- [x] T1. Flavors `standard` y `experimental` (dimensión `distribution`): `experimentalRelease` desactivada, sufijo `.experimental` y nombre «TopoPhone experimental»; `testExperimentalDebugUnitTest` limitada a los tests del flavor; rutas del APK en `release.yml`, `docs/publicacion.md` y `g0-procedimiento.md` (y `test_release_workflow.py`). (RF: —, prepara RF-1)
      Hecho cuando: `./gradlew assembleDebug` compila las dos variantes, `:app:assembleRelease` solo la `standard`, `verifyReleaseCertificate` sigue una sola y `check` y `uv run --project tools pytest tools` pasan.
- [x] T2. Módulo `receiver-experimental` vacío (biblioteca Android, sin Hilt) en `settings.gradle.kts`, con `experimentalImplementation` en `app`, su fila en `docs/arquitectura.md` (y ADR-21) y su regla en `ModuleRules` con test. (RF-2)
      Hecho cuando: `ModuleRulesTest` cubre «solo `app` depende del módulo y el módulo no depende de `data`» y `./gradlew check` pasa.
- [x] T3. Ranuras `ExperimentalFeature` en `main` con su implementación vacía en `standard` y una mínima en `experimental`; llamadas desde `SettingsScreen`, `ReceiverHeader`, `ReceiverMenu`, `ReceiverList` y `TopoPhoneApp` que en `standard` no pintan nada; `StandardFlavorIsolationTest` y `ExperimentalFlavorTest`. (RF-1, RF-2)
      Hecho cuando: los dos tests pasan, los tests de pantallas existentes no cambian y no hay ningún import del módulo en `main`.
- [x] T4. Tarea `checkNoExperimental` de `build-logic` sobre las variantes `standard` (classpath sin el módulo y manifiesto sin componentes ni permisos del flavor) dentro de `check`, con su test. (RF-1)
      Hecho cuando: el test de la tarea falla con un classpath o manifiesto que lleva el módulo y pasa sin él, y `check` la ejecuta.

## Cliente del receptor (módulo)
- [x] T5. `fixtures/reach/` (respuestas sintéticas marcadas como tales, con la fuente) y `ReachEndpoint` + `ReachHttpClient`: red Wi-Fi por socket, 3 s, sin redirecciones, host fijo, `mockwebserver` solo en tests; `CleartextPolicyTest` recorre también el módulo. (RF-23)
      Hecho cuando: `ReachHttpClientTest` cubre host distinto, redirección, tiempo agotado y dirección pública, y `:receiver-experimental:test` pasa.
- [x] T6. `ReachInfoReader` y `ReachPart` (información y compatibilidad por partes). (RF-11, RF-15)
      Hecho cuando: `ReachInfoReaderTest` cubre la forma esperada, un campo ausente, uno de otro tipo, sin respuesta y la compatibilidad parcial.
- [x] T7. `ReachConfigReader` con ocultación de claves sensibles. (RF-14, RF-15)
      Hecho cuando: `ReachConfigReaderTest` cubre aplanado, secciones de salida, claves ocultas y forma incompatible.
- [x] T8. `EngineIoPacket` y `ReachLiveStatus` (WebSocket Engine.IO v3: apertura, ping, eventos `broadcast`, reconexión 1 s a 30 s). (RF-13, RF-16, RF-17)
      Hecho cuando: `EngineIoPacketTest` y `ReachLiveStatusTest` pasan con el servidor falso, incluidos eventos desconocidos, corte y reconexión con reloj virtual.
- [x] T9. `AntennaHeightProposal` y `ReachWriter.setAntennaHeight` (leer, modificar el objeto `device` completo, escribir, releer). (RF-18, RF-19, RF-21, RF-22)
      Hecho cuando: `AntennaHeightProposalTest` y `ReachWriterAntennaTest` pasan, incluido que el cuerpo solo cambia `antenna_height` y el receptor que no confirma.
- [x] T10. `ReachWriter.setLogs` con comprobación por el directo. (RF-20, RF-21)
      Hecho cuando: `ReachWriterLogsTest` cubre iniciar, parar, estado que no coincide y sin directo.
- [x] T11. `ReachAudit` sobre `AppLog`. (RF-24)
      Hecho cuando: `ReachAuditTest` comprueba hora (la del log), operación y valores, y que nunca aparece un valor de clave oculta.
- [x] T12. `ReachDiscovery`, `ServiceBrowser` y su adaptador `NsdServiceBrowser`. (RF-7, RF-9, RF-10)
      Hecho cuando: `ReachDiscoveryTest` cubre repetidos, direcciones públicas descartadas, los 10 s, la parada y «ninguno», con un navegador falso.

## Activación y datos en la app
- [x] T13. `ExperimentalPreferences` (DataStore fuera de las copias) y `ExperimentalFeatureImpl`: interruptor «Módulo experimental» en Ajustes y aviso legal (es y en). (RF-3, RF-4, RF-5)
      Hecho cuando: `ExperimentalPreferencesTest` y `ExperimentalSettingsTest` cubren apagado de fábrica, aceptar con fecha, rechazar, desactivar (olvida la aceptación) y el aviso otra vez.
- [ ] T14. `ExperimentalSession`: dirección según la fuente (Wi-Fi, Bluetooth con IP, sin dirección), cliente, directo y cierre al desactivar, sin tocar `ReceiverSession`. (RF-5, RF-11, RF-16, RF-25)
      Hecho cuando: `ExperimentalSessionTest` cubre cada fuente, desactivar cierra y la pérdida del directo no afecta a la sesión.

## Pantallas
- [ ] T15. Entrada «Módulo experimental» en el menú, pantalla con la información leída, marca «Experimental» en la cabecera y «Rellenar el perfil de equipo» con confirmación. (RF-6, RF-11, RF-12, RF-15)
      Hecho cuando: `ExperimentalPanelScreenTest` cubre la marca, la lectura, «Receptor no compatible» y el perfil que solo se guarda al confirmar.
- [ ] T16. Batería, estado de los logs y «Sin estado del receptor» (cabecera y panel). (RF-13, RF-17)
      Hecho cuando: el test de la pantalla cubre nivel, logs activos, el corte y el reintento.
- [ ] T17. «Configuración del receptor» en solo lectura. (RF-14, RF-15)
      Hecho cuando: `ReceiverConfigScreenTest` cubre ajustes, claves ocultas y parte incompatible.
- [ ] T18. «Enviar altura de antena» con propuesta según el modo, aviso de doble cuenta y confirmación. (RF-18, RF-19, RF-21)
      Hecho cuando: `AntennaHeightScreenTest` cubre cada modo, editar, confirmar, coincide y no coincide.
- [ ] T19. «Iniciar log» y «Parar log» con confirmación y aviso al parar. (RF-20, RF-21)
      Hecho cuando: `LogsScreenTest` cubre ambos, el aviso, el estado resultante y sin directo.
- [ ] T20. «Buscar receptores» (botón en la lista, permiso de red local, resultados, «Ya añadido», ninguno) y alta con el formulario de S021 solo si se confirma. (RF-7, RF-8, RF-9, RF-10)
      Hecho cuando: `DiscoveryScreenTest` cubre resultados, ninguno, repetido y alta confirmada y cancelada.
- [ ] T21. Accesibilidad y textos: etiquetas, 56 dp, estados por texto, español e inglés con las mismas claves, ningún texto del fabricante, ninguna cadena de usuario en el código. (RNF-T7, RNF-T8, RNF-T14)
      Hecho cuando: `ExperimentalAccessibilityTest` pasa y `checkHardcodedUiText` no encuentra literales.

## Herramientas y cierre
- [ ] T22. `tools/simulador/servidor_reach.py` (HTTP y WebSocket Engine.IO v3 con opciones `--incompatible`, `--lento`, `--cortar-directo`, `--sin-logs`) con su test y su fila en `tools/LEEME.md`; `enlaces.md` con lo leído del código de referencia. (RF-11 a RF-21)
      Hecho cuando: `uv run --project tools pytest tools` pasa con el test nuevo.
- [ ] T23. Validación final: recorrer la spec RF por RF, `rf_report.py`, `check`, `pytest` de `tools`, `forbidden_files`, `assembleDebug`, demo en el emulador con `servidor_reach.py` y la sección «Cambios al implementar» y «Validación» de `plan.md`; hoja de ruta con S044 implementada. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report.py` no muestra RF huérfanos y la demo funciona; lo que no se pueda en el emulador (búsqueda real, formas reales, unidad de la altura, directo por WebSocket) queda anotado para el campo.
