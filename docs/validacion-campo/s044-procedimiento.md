# Validación de campo del módulo experimental (S044): procedimiento y plantilla del acta

El módulo experimental de S044 se probó con un receptor **de mentira** (`tools/simulador/servidor_reach.py`) y con respuestas **sintéticas** (`fixtures/reach/`), escritas a partir del código de una integración de terceros. Este procedimiento lo prueba con un RS3 real y deja anotado lo que el emulador no puede ver. Va aparte de G0 ([g0-procedimiento.md](g0-procedimiento.md)): no hace falta salir al campo, basta el RS3 encendido en tu red Wi-Fi.

> **Escribe en el receptor.** Solo cambia la altura de antena y el inicio o parada de los logs, pero es un receptor de verdad: **anota antes el valor de altura de antena que tiene** (Emlid Flow o la pantalla del módulo) para poder devolverlo.

## Antes de empezar
1. Instala la variante `experimental` (otra app, junto a la normal y con sus propios datos):
   ```
   ./gradlew :app:assembleExperimentalDebug
   adb install -r app/build/outputs/apk/experimental/debug/app-experimental-debug.apk
   ```
   Es `es.topophone.app.experimental` («TopoPhone experimental»). **No existe como versión publicable**: solo `debug`.
2. Pon el RS3 y el móvil en la **misma red Wi-Fi** (la del hotspot del receptor, normalmente `192.168.42.1`, o tu red local) y anota la **versión de firmware** del receptor.
3. Desconecta Emlid Flow del receptor si lo usas por Bluetooth: el firmware admite una sola conexión Bluetooth.
4. En TopoPhone experimental: **Ajustes → Experimental → Módulo experimental**, lee el aviso y acepta.

## Pasos y qué comprobar
Marca cada uno en el acta. Lo que no salga como se describe es justo lo que hay que anotar.

1. **Receptor en uso.** En Receptor, añade un receptor **Wi-Fi (TCP)** con la IP del RS3 y el puerto de su salida de posición, y conéctalo (con Android 17 o posterior, concede el permiso de red local). *Comprueba:* en la cabecera salen la marca «Experimental», «Batería: N %» y «Logs: …». Anota si la batería coincide con la que dice Emlid Flow.
2. **Información (RF-11).** Menú → **Módulo experimental**. *Comprueba:* modelo, número de serie, firmware, rol y altura de antena. **Anota** si el firmware es el que viste en el paso 2 de «Antes de empezar» y **la unidad de la altura de antena** (¿1,800 m es lo que ves en Emlid Flow, o sale otra cosa como 180 o 1800?).
3. **Configuración (RF-14).** «Configuración del receptor». *Comprueba:* salen ajustes de posicionamiento y de salida y los valores de claves (`password`, `psk`, `token`, `key`…) salen **«oculto»**. Si sale alguna clave en claro, **no sigas y anótalo**.
4. **Perfil de equipo (RF-12).** «Rellenar el perfil de equipo» → Guardar. *Comprueba:* en Datos del receptor aparecen modelo, serie y firmware.
5. **Estado en directo (RF-13, RF-16, RF-17).** Con el panel abierto, apaga y enciende el Wi-Fi del móvil. *Comprueba:* sale «Sin estado del receptor» y vuelve solo en unos segundos; la posición y las correcciones no se cortan. **Anota** si el directo conecta a la primera: es la prueba de que el WebSocket directo funciona con este firmware.
6. **Altura de antena (RF-18, RF-19, RF-22).** «Enviar altura de antena», pon un valor distinto al actual (por ejemplo 1,5) y confirma. *Comprueba:* «Confirmado: el receptor tiene 1,500 m» y que Emlid Flow ve el mismo valor, y que **no cambió ningún otro ajuste** (rol, modo de posicionamiento, salidas). Después **devuelve el valor original**.
7. **Logs (RF-20).** «Logs del receptor»: «Iniciar log» y «Parar log». *Comprueba:* el estado cambia en el panel y en Emlid Flow; al parar, el log se cierra. Si falta el estado de los logs, no se ofrece ninguna acción: anótalo.
8. **Buscar receptores (RF-7 a RF-10).** Desconecta el receptor y abre «Buscar receptores» en la lista de receptores (concede el permiso de red local si lo pide). *Comprueba:* en 10 s sale el RS3 con su nombre y su IP; **anota el nombre con el que se anuncia**. Pulsa «Añadir» y confirma el alta; el receptor sale en la lista de Wi-Fi. Si no sale nada, anota si es por la red (aislamiento de clientes) o porque el RS3 no anuncia el servicio `_reach._tcp`.
9. **Desactivar (RF-5, RF-6).** Ajustes → desactiva el módulo. *Comprueba:* desaparecen la marca, la entrada del menú y «Buscar receptores», y el RS3 sigue conectado para la posición.

## Si el módulo dice «Receptor no compatible»
Es lo esperado cuando las respuestas del firmware tienen otra forma. Captura las respuestas (paso siguiente) y súbelas: con ellas se ajusta el lector.

## Capturar las respuestas reales
Desde un ordenador en la misma red (sustituye la IP):
```
curl -s http://192.168.42.1/info > info-fw<versión>.json
curl -s http://192.168.42.1/configuration > configuration-fw<versión>.json
```
**Antes de compartirlas o guardarlas en el repositorio, bórrales los secretos:** `/configuration` puede traer contraseñas del Wi-Fi, del caster NTRIP o de tokens. Sustituye cada valor de una clave que contenga `pass`, `psk`, `token`, `secret` o `key` por `"x"`, y **no publiques** coordenadas de tu propiedad ni números de serie que no quieras compartir.

Para pasarlas a los tests **no sobrescribas** las fixtures sintéticas: los tests de `receiver-experimental` dependen de sus valores. Añádelas como `fixtures/reach/info-real-fw<versión>.json` y `configuration-real-fw<versión>.json`, apunta la fuente en `fixtures/reach/LEEME.md` (ya no son sintéticas) y se añade un test que las lea con `ReachInfoReader` y `ReachConfigReader`.

## Plantilla del acta
Se guarda en `docs/validacion-campo/AAAA-MM-DD-s044/acta.md`.
```markdown
# Acta de validación — S044 módulo experimental — <AAAA-MM-DD>
- Receptor: RS3 FW <x.y> · Móvil: <modelo / Android> · TopoPhone experimental <versión/commit>
- Red: <Wi-Fi del receptor o red local> · IP del receptor: <…>

| Paso | Qué se comprueba | Resultado | OK |
|---|---|---|---|
| 1 | Marca, batería y logs en la cabecera | | |
| 2 | Información: modelo, serie, firmware, rol, altura de antena | | |
| 3 | Configuración en solo lectura, claves ocultas | | |
| 4 | Perfil de equipo | | |
| 5 | Directo: corte y vuelta, sin afectar a la posición | | |
| 6 | Altura de antena: confirmada, sin cambiar otros ajustes, valor original devuelto | | |
| 7 | Iniciar y parar logs | | |
| 8 | Buscar receptores: aparece, nombre anunciado, alta | | |
| 9 | Desactivar el módulo | | |

- **Unidad de `antenna_height`:** <metros / otra: …>
- **Directo por WebSocket sin pasar por polling:** <conecta / no conecta>
- **Nombre del servicio mDNS:** <el que se anuncia, o «no se anuncia»>
- **Formas de las respuestas:** <iguales a las sintéticas / distintas: …> (ficheros adjuntos, sin secretos)
- Incidencias:
- Veredicto: APTO / NO APTO · Firmado: <nombre>
```
