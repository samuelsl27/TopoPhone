# Spec 044 — Módulo experimental del receptor

> Hito: H7 · Depende de: S043 (además, de S021 y S046; ver Cambios) · Estado: borrador (sin entrevista, 28/10/2026)

## Contexto y objetivo
El receptor tiene una interfaz local que el fabricante no documenta y que sirve para leer su información, su batería y su configuración, ver su estado en directo y cambiar un par de ajustes. Es útil (por ejemplo, la altura de antena del modo compensado, o parar un log sin salir de la app), pero se obtuvo por ingeniería inversa, los términos del fabricante la prohíben y puede cambiar con cualquier firmware (`docs/referencias/emlid/enlaces.md`).

La constitución (nº 5) exige aislarla: vive en un módulo y en un flavor `experimental`, desactivada por defecto y con aviso legal. Esta spec define ese módulo y sus límites. El núcleo de la app (S002 a S043) no la necesita ni depende de ella: nada de lo que mide o replantea TopoPhone puede venir de aquí.

## Usuarios / actores
- Usuario avanzado que compila o instala el flavor `experimental` bajo su responsabilidad.

## Historias de usuario
- H1: Como usuario quiero que el módulo no exista en la app normal y que, en la experimental, esté apagado hasta que acepte el aviso legal.
- H2: Como usuario quiero leer del receptor su información, su batería, su configuración y su estado en directo.
- H3: Como usuario quiero cambiar la altura de antena y arrancar o parar los logs del receptor, viendo antes y después lo que cambia.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Módulo experimental**: la parte de la app que habla con la interfaz local no documentada del receptor; solo está en el flavor `experimental`.
- **Aviso legal**: texto que explica que la interfaz no es oficial, que su uso puede infringir los términos del fabricante y que puede dejar de funcionar.
- **Receptor compatible**: el que responde con la forma que el módulo conoce; con cualquier otra, el módulo funciona en solo lectura o no funciona.

### Aislamiento y activación (H1)
- RF-1: EL SISTEMA no incluirá el módulo experimental en el flavor normal: ni sus clases, ni sus permisos, ni su configuración de red.
- RF-2: EL SISTEMA no hará depender ningún módulo de dominio, de datos ni de receptor del módulo experimental, y la regla de módulos lo comprobará.
- RF-3: DONDE el flavor sea `experimental`, EL SISTEMA tendrá el módulo desactivado de fábrica, con un interruptor "Módulo experimental" en Ajustes.
- RF-4: CUANDO el usuario active el interruptor, EL SISTEMA mostrará el aviso legal completo y solo activará el módulo si acepta, y guardará la fecha de la aceptación.
- RF-5: CUANDO el usuario desactive el módulo, EL SISTEMA cerrará sus conexiones y ocultará sus pantallas, sin afectar a la sesión del receptor.
- RF-6: MIENTRAS el módulo esté activo, EL SISTEMA mostrará en la cabecera del receptor la marca "Experimental".

### Leer del receptor (H2)
- RF-7: CUANDO el módulo esté activo y haya un receptor Wi-Fi (S021) o una dirección de receptor guardada (S043/RF-13), EL SISTEMA leerá del receptor su modelo, número de serie, firmware, rol y altura de antena configurada.
- RF-8: CUANDO el usuario pulse "Rellenar el perfil de equipo", EL SISTEMA propondrá el modelo, la serie y el firmware leídos para el perfil de S043/RF-18, y los guardará solo si el usuario confirma.
- RF-9: MIENTRAS el módulo esté activo, EL SISTEMA mostrará en la pantalla del receptor el nivel de batería y el estado de los logs activos.
- RF-10: CUANDO el usuario abra "Configuración del receptor", EL SISTEMA mostrará en solo lectura los ajustes de posicionamiento y de salida que el receptor devuelva.
- RF-11: SI el receptor no responde en 3 s o su respuesta no tiene la forma esperada, ENTONCES EL SISTEMA lo indicará como "Receptor no compatible", no hará ninguna escritura y seguirá funcionando todo lo demás.
- RF-12: EL SISTEMA obtendrá el estado en directo por una conexión aparte de la sesión del receptor, y su pérdida o su error no afectarán a la posición, las correcciones ni la grabación.

### Escribir en el receptor (H3)
- RF-13: CUANDO el usuario pulse "Enviar altura de antena", EL SISTEMA mostrará el valor actual del receptor y el nuevo, con la altura de antena del jalón activo (S046) como propuesta editable, y pedirá confirmación.
- RF-14: CUANDO el usuario confirme, EL SISTEMA enviará la altura, volverá a leer el ajuste y mostrará si coincide con lo enviado.
- RF-15: CUANDO el usuario pulse "Iniciar log" o "Parar log", EL SISTEMA mostrará el estado actual de los logs y pedirá confirmación, y después comprobará el estado resultante.
- RF-16: SI una escritura falla, no se confirma o el valor leído después no coincide, ENTONCES EL SISTEMA lo indicará con el valor que quedó en el receptor y no reintentará solo.
- RF-17: EL SISTEMA no enviará ningún otro dato al receptor, ni siquiera a petición del usuario.
- RF-18: EL SISTEMA hará todas las peticiones del módulo por la red Wi-Fi del receptor y solo hacia direcciones privadas o de enlace local (RNF-T4).

### Rastro (H1, H3)
- RF-19: CUANDO se haga una lectura de configuración o una escritura, EL SISTEMA la anotará en el log de la app (S014) con la hora, la operación y los valores, sin secretos.
- RF-20: EL SISTEMA no guardará nunca datos del módulo en un punto, un proyecto o un CSV.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T4: HTTP sin cifrar solo hacia direcciones privadas y de enlace local, y solo en el flavor `experimental`.
  - RNF-T7 y RNF-T8: textos en español y en recursos; zonas táctiles de 56 dp.
  - RNF-T11 y RNF-T12: el log y el informe de sistema no contienen secretos.
  - RNF-T14: el aviso legal no usa la marca del fabricante como nombre de la app ni recursos suyos.
- Una petición del módulo tiene un tiempo máximo de 3 s y no se hace en el hilo principal.
- Una comprobación de compilación demuestra que el APK del flavor normal no contiene clases del módulo.

## Casos límite
| Caso | RF |
|---|---|
| Receptor con firmware que cambia la forma de las respuestas | RF-11 |
| Receptor apagado a mitad de una escritura | RF-16 |
| Estado en directo que se corta | RF-12 |
| Usuario rechaza el aviso legal | RF-4 |
| Dirección pública escrita por error | RF-18 |
| Perfil de equipo ya rellenado a mano | RF-8 |
| Enviar la altura con la inclinación calculada por la app | RF-13 |

## Fuera de alcance
- Cambiar rol, modo de posicionamiento, salidas, Wi-Fi o LoRa del receptor → no previsto.
- Descargar, borrar o convertir logs → no previsto.
- Actualizar el firmware o reiniciar el receptor → no previsto.
- Usar datos del módulo para medir, replantear o validar puntos → no previsto (constitución nº 5).
- Distribuir el flavor `experimental` como release público → no previsto hasta resolver el aviso de permiso (ver Dudas).

## Criterios de finalización
- Todos los RF con test en verde (con un servidor falso basado en respuestas registradas), informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde, también para el flavor `experimental`.
- Demo en el emulador: activar con el aviso legal, leer y escribir contra el servidor falso, y un receptor no compatible.
- Demo de campo: queda para el usuario (lectura y escritura reales con su RS3, con un firmware anotado).

## Dudas abiertas
- [SUPOSICIÓN: el flavor `experimental` solo se compila y se instala a mano; no se publica en GitHub Releases hasta pedir permiso al fabricante (`enlaces.md`).]
- [SUPOSICIÓN: se escribe solo la altura de antena y los logs (lo que dice la hoja de ruta); la lectura es de solo lectura.]
- [SUPOSICIÓN: la altura de antena que se propone es la del jalón activo; falta decidir cómo convive con el modo compensado, en el que el jalón va a 0 en la app (S016).]
- [SUPOSICIÓN: no se usa el descubrimiento mDNS del módulo; la dirección sale del receptor Wi-Fi o de S043.]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`: S044 pasa a `spec`; dependencias ampliadas con S021 y S046.
- `docs/glosario.md`: añadir «Módulo experimental» y «Aviso legal».
- `docs/arquitectura.md` (ADR-18) no cambia; `docs/inventario-funcional.md`: las filas «Exp» apuntan a S044.
