# Tareas — Spec 043

> Cada tarea dura 30 min como máximo, sigue el orden de dependencias y se implementa con los tests primero. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`), marcar la casilla y un commit `S043 Tn: …`.

- [x] T1. `core:model`: `EquipmentProfile`, `ConfigList`, `ConfigStep`, `CheckId`, `CheckStatus`, `ReceiverCheck` y `PrivateAddress` (cuatro rangos, sin nombres ni IPv6, puerto opcional). (RF-12, RF-13, RF-14, RF-18)
      Hecho cuando: `PrivateAddressTest` en verde con los bordes de cada rango (172.15, 172.16, 172.31, 172.32, 169.253, 169.254, 192.167, 192.168, 192.169), `127.0.0.1`, `reach.local`, IPv6, públicas y puertos 0, 1, 65535 y 65536.
- [x] T2. `core:gnss`: `ReferenceStationDecoder` lee el año ITRF de 1005 y 1006 y `ReferenceStation.itrfYear` lo guarda (0 = sin declarar). (RF-8)
      Hecho cuando: `ReferenceStationDecoderTest` en verde con 1005 y 1006 con año 2014, con año 2020 y con 0, y los tests de S015 intactos.
- [x] T3. `core:survey`: `ObservationWindow` (ventana de 10 s de épocas y fotos por segundo de las RTCM, con tiempo cubierto). (RF-5)
      Hecho cuando: `ObservationWindowTest` en verde: descarta lo de más de 10 s, da el delta de RTCM entre la primera y la última foto y la tasa sobre el tiempo cubierto.
- [x] T4. `core:survey`: `ReceiverChecker` con las comprobaciones de NMEA (`GGA_RATE`, `SENTENCES`, `SOLUTION`, `CORRECTION_AGE`, `UTC_TIME`) y «Sin datos». (RF-6, RF-9, RF-10)
      Hecho cuando: `ReceiverCheckerNmeaTest` en verde, con épocas sintéticas y con `rs3-completo` y `rs3-corte` de `fixtures/nmea`, cada comprobación en `OK`, `WARNING`, `FAIL` y `NO_DATA`, el caso de la GGA a 1 Hz con otras frases a 5 Hz, y cada fallo con su causa y su paso.
- [x] T5. `core:survey`: `ReceiverChecker` con `TILT`, `RTCM_BASE`, `RTCM_OBSERVATIONS`, `RTCM_GLONASS` y `DATUM`. (RF-7, RF-8, RF-9)
      Hecho cuando: `ReceiverCheckerTiltRtcmTest` en verde con `rs3-inclinacion` y `rs3-base-shift`: sin ETC no hay comprobación de inclinación, con ETC en `SETUP` y con aviso salen `WARNING`, sin 1005/1006 sale `FAIL` con su causa, y el datum con año ITRF y proyecto ETRS89 da `WARNING`, con año 0 «No se puede comprobar».
- [x] T6. `core:model`: `ConfigLists` con las cuatro listas y sus pasos, y sus textos en es y en. (RF-1, RF-2)
      Hecho cuando: `ConfigListsTest` en verde: cuatro listas, ids únicos, cada paso comprobable apunta a una comprobación válida y cada texto `guide_*` existe en es y en con las mismas claves.
- [x] T7. `data`: Room v36 (`config_steps`, `equipment_profiles`, tres columnas de `points`, `AutoMigration(35, 36)`, esquema `36.json`) y sus filas en `DatabaseSnapshot.TABLES`. (RF-3, RF-18, RF-19, RNF-T6)
      Hecho cuando: el test de migración v35 a v36 de `TopoPhoneDatabaseTest` en verde, con los puntos anteriores intactos y los de las dos tablas nuevas vacías, y los de versiones anteriores intactos.
- [x] T8. `data`: `ConfigProgressRepository` y `EquipmentRepository` (perfil y dirección web) y `Measurement.equipment` guardado con el punto. (RF-3, RF-13, RF-18, RF-19)
      Hecho cuando: `ConfigProgressRepositoryTest`, `EquipmentRepositoryTest` y `PointEquipmentTest` en verde, y la copia de seguridad y el paquete de proyecto llevan los tres campos del punto.
- [x] T9. `core:formats`: las tres `ExportColumn` de equipo en el CSV y su prueba, con los tres CSV de referencia regenerados. (RF-20)
      Hecho cuando: `CsvExportTest` y `CsvExportGoldenTest` en verde con las columnas tras `AUTOMATIC` y `tools/formatos/test_csv_exportado.py` en verde.
- [x] T10. `core:formats`: los tres atributos de equipo en GeoPackage y las tres columnas en Shapefile (`RX_MODEL`, `RX_SERIAL`, `RX_FW`), con sus referencias regeneradas. (RF-20)
      Hecho cuando: `GpkgPointAttributesTest`, `ShpColumnNamesTest` y `ShpExportBuilderTest` en verde y `test_gpkg_exportado.py` y `test_shp_exportado.py` en verde con los ficheros de `fixtures/formatos` regenerados.
- [x] T11. `core:formats` y `app`: el KML lleva los campos de equipo y la pantalla de exportar los ofrece en «Personalizado» con su texto. (RF-20)
      Hecho cuando: `KmlExportBuilderTest`, `ExportScreenTest` y `test_kml_exportado.py` en verde con `fixtures/formatos/kml-exportado` regenerado.
- [x] T12. `app`: `CurrentEquipment` y el perfil guardado por el recolector y el control de base al empezar a medir (no con el móvil, el virtual ni una reproducción). (RF-19, RF-23)
      Hecho cuando: `CollectorEquipmentTest` en verde: el punto guarda el perfil de ese momento, uno editado después no cambia el ya guardado y los puntos del virtual, del móvil y de una reproducción no llevan perfil.
- [x] T13. `app`: «Datos del receptor» (formulario de modelo, serie y firmware, catálogo de S046, «Usar su perfil de antena») y la fila de equipo en la ficha del punto. (RF-18, RF-20, RF-21)
      Hecho cuando: `EquipmentScreenTest` y `PointEquipmentDetailsTest` en verde: se guarda y se edita por receptor, elegir un modelo del catálogo propone su perfil y no cambia el que ya había hasta pulsar, y la ficha muestra los tres campos solo si el punto los tiene.
- [x] T14. `app`: `ReceiverWindowRecorder` (la ventana de 10 s a partir de `ReceiverSession.epochs` y del estado del NTRIP). (RF-5)
      Hecho cuando: `ReceiverWindowRecorderTest` en verde con una sesión real de `fixtures/nmea` y un NTRIP falso: la ventana se llena, olvida lo viejo, funciona con una reproducción y con el receptor virtual y se vacía al desconectar.
- [x] T15. `app`: `GuideViewModel` y `GuideScreen` (las cuatro listas, marcar y desmarcar por receptor, reiniciar y los contadores de hechos y comprobados). (RF-1, RF-2, RF-3, RF-4)
      Hecho cuando: `GuideViewModelTest` y `GuideScreenTest` en verde: el progreso es por receptor y sobrevive al reinicio de la pantalla, reiniciar vacía solo esa lista y los contadores dicen cuántos pasos están hechos y cuántos comprobados.
- [x] T16. `app`: «Comprobar» en la Guía: el resultado por paso con texto e icono, la causa y el paso que lo resuelve, y «Sin datos» con la remisión al diagnóstico. (RF-5, RF-9, RF-10, RF-11)
      Hecho cuando: `GuideChecksTest` en verde con una reproducción y el receptor virtual: cada comprobación en Correcto, Aviso y Fallo, «Sin datos» remite a S004/RF-27 y RF-28, termina en menos de 1 s y nada se escribe en el receptor.
- [ ] T17. `app`: las entradas «Guía» y «Datos del receptor» del menú, sus rutas, y las fuentes: con el móvil y con el virtual se sustituyen por «no aplica a esta fuente» (el virtual conserva «Comprobar»). (RF-1, RF-18, RF-22, RF-23)
      Hecho cuando: `GuideSourcesTest` y `ReceiverMenuTest` en verde: ocho entradas en su orden con un receptor real, y con el móvil y el virtual lo que dice RF-22 y RF-23.
- [ ] T18. `app`: `ReceiverWebViewModel` (dirección por receptor, temporizador de 10 s, reintentar, abrir en el navegador) con `PrivateAddress`. (RF-12, RF-13, RF-14, RF-16)
      Hecho cuando: `ReceiverWebViewModelTest` en verde: un receptor Wi-Fi abre `http://<su IP>:80/`, uno Bluetooth pide la IP y la recuerda, una pública o un nombre se rechaza, y a los 10 s sin carga sale el fallo con sus causas y las dos salidas.
- [ ] T19. `receiver`: `NetworkRouter.bindProcessToWifi` (pide la red Wi-Fi sin exigir internet y la enlaza al proceso mientras se usa). (RF-15, RNF-T4)
      Hecho cuando: `NetworkRouterWebTest` en verde con una fuente de redes falsa: enlaza la del Wi-Fi sin internet, desenlaza al cerrar y avisa si no hay Wi-Fi.
- [ ] T20. `app`: `ReceiverWebScreen` con el `WebView` restringido (JavaScript sí; ficheros, contenido, cookies de terceros, contenido mixto y puentes no; solo peticiones a IP privadas) y el manifiesto con el tráfico en claro. (RF-12, RF-14, RF-15, RF-17)
      Hecho cuando: `ReceiverWebScreenTest` en verde: los ajustes del `WebView` son los de D9, una petición a una dirección pública o a otro puerto de otra IP se bloquea, una redirección a otra dirección no se sigue, la sesión de posición sigue abierta con la vista visible y un test recorre los clientes HTTP de la app sin hallar ninguno que acepte HTTP público.
- [ ] T21. `tools`: `simulador/servidor_web.py` (un Reach Panel falso con la biblioteca estándar: una página, un fallo y una redirección) y su test. (RF-14, RF-16)
      Hecho cuando: `test_servidor_web.py` en verde, 6 tests de `tools` nuevos, sin ficheros prohibidos y su fila en `tools/LEEME.md`.
- [ ] T22. `app`: accesibilidad y textos de la Guía, las comprobaciones, la vista web y «Datos del receptor»: etiquetas y 56 dp, inglés y claves iguales en es y en, sin literales en el código. (RNF-T7, RNF-T8, RNF-T14)
      Hecho cuando: `GuideAccessibilityTest` en verde: cada estado se distingue por texto o icono y no solo por color y ningún texto procede del fabricante.
- [ ] T23. Validación final: recorrer la spec RF por RF, `rf_report.py`, `check`, `pytest` de `tools`, `forbidden_files`, `assembleDebug` y la demo (cada comprobación en Correcto, Aviso y Fallo con reproducciones y el virtual, la vista web contra `servidor_web.py`, la RAM de la vista); `plan.md` con «Cambios al implementar» y la validación y la hoja de ruta con S043 implementada (Room v36). (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report.py` no muestra RF huérfanos y la demo funciona.
