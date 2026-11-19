# Fixtures de `fixtures/reach/` (S044)

**Todas son sintéticas.** Las escribió el equipo a partir de las formas que usa el código de referencia de la API no oficial del receptor
(`bscholer/home-assistant-emlid`, licencia MIT, leído el 18/11/2026); **ninguna es una respuesta grabada de un receptor real**. Sirven a
`mockwebserver` en los tests de `receiver-experimental`. Cuando la persona usuaria aporte las respuestas de su RS3 se sustituirán por ellas.

| Fichero | Contenido |
|---|---|
| `info.json` | Respuesta de `GET /info` con modelo, serie y versión |
| `configuration.json` | Respuesta de `GET /configuration`: dispositivo, posicionamiento, entradas y salidas, y claves sensibles (de mentira) |
| `configuration-incompatible.json` | `GET /configuration` con otra forma: la altura de antena es un texto y falta el objeto `device` de `/info` |
| `info-incompatible.json` | `GET /info` sin el número de serie y con la versión como número |
| `engineio-open.txt` | Primer paquete del directo (`0{…}`: `sid`, `pingInterval`, `pingTimeout`) |
| `battery-status.txt` | Paquete `42["broadcast",{"name":"battery_status",…}]` |
| `active-logs.txt` | Paquete `42["broadcast",{"name":"active_logs",…}]` con un log escribiendo |
| `active-logs-stopped.txt` | El mismo evento con ningún log escribiendo |
