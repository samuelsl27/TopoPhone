# Spec 022 — GNSS del móvil y receptor virtual

> Hito: H2 · Depende de: S021 (en realidad, de S003, S005 y S047; ver Cambios) · Estado: spec (aprobada el 15/11/2026)

## Contexto y objetivo
Hay dos situaciones en que el topógrafo quiere usar la app sin un receptor Reach: mirar un sitio o marcar un punto aproximado con el GNSS del propio móvil (de 1 a 3 m), y aprender, probar o hacer una demostración con un receptor que se comporta como uno real pero que no existe. La reproducción de sesiones (S003) cubre lo grabado, pero no permite generar una situación a medida (pérdida de FIX, inclinación, cambio de base).

Esta spec añade dos fuentes de posición a la lista de receptores: el **GNSS del móvil**, que nunca se presenta como FIX, y el **receptor virtual**, que sigue escenarios guionizados y repetibles. Ambas pasan por la misma sesión del receptor que las demás, así que el recolector, el replanteo y el mapa no cambian.

## Usuarios / actores
- Topógrafo en campo que solo necesita una posición aproximada.
- Topógrafo que se forma o enseña la app, o el equipo que hace demos y pruebas sin receptor.

## Historias de usuario
- H1: Como topógrafo quiero usar el GNSS del móvil como receptor, sabiendo siempre que es una posición de pocos metros.
- H2: Como usuario quiero un receptor virtual con escenarios conocidos para practicar o probar la app sin receptor.
- H3: Como topógrafo quiero que ningún punto de un móvil o de una simulación pase por una medida RTK.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **GNSS del móvil**: la posición que da el sistema Android; no usa correcciones ni receptor externo.
- **Receptor virtual**: fuente de épocas generadas por la app a partir de un escenario; es una fuente simulada (como una reproducción, S003/RF-44, RF-45).
- **Escenario**: guion de duración fija con la posición, el tipo de solución y los eventos de cada instante; con el mismo escenario y la misma posición inicial, las épocas son siempre las mismas.

### GNSS del móvil (H1, H3)
- RF-1: EL SISTEMA ofrecerá "GNSS del móvil" en la lista de receptores (S005/RF-2) y en "Añadir receptor".
- RF-2: CUANDO el usuario lo elija, EL SISTEMA pedirá el permiso de ubicación precisa explicando que sirve para leer el GNSS del móvil; SI se deniega, ENTONCES lo indicará y cómo concederlo (RNF-T1).
- RF-3: SI la ubicación del móvil está desactivada, ENTONCES EL SISTEMA lo indicará y ofrecerá abrir los ajustes de ubicación.
- RF-4: MIENTRAS esté en uso el GNSS del móvil, EL SISTEMA mantendrá la lectura en segundo plano con el servicio de S004/RF-7 a RF-11, de tipo ubicación (RNF-T2).
- RF-5: EL SISTEMA presentará la solución del GNSS del móvil siempre como SINGLE, sea cual sea la precisión que declare Android, con la precisión horizontal que dé Android y la vertical como no disponible si no la da.
- RF-6: EL SISTEMA tomará como altura la elipsoidal que da Android, sin aplicar la separación del geoide de la GGA (S002/RF-33), y calculará el punto del suelo sin altura de jalón ni desplazamiento al centro de fase.
- RF-7: SI Android indica que la posición es simulada por otra app, ENTONCES EL SISTEMA lo avisará y marcará los puntos medidos con ella como tales.
- RF-8: SI hace más de 3 s que no llega una posición, ENTONCES EL SISTEMA mostrará "Sin datos" (RNF-T3).
- RF-9: EL SISTEMA no ofrecerá NTRIP, inclinación ni base con el GNSS del móvil, e indicará el motivo si el usuario lo pide.
- RF-10: EL SISTEMA no relajará los controles de S010 ni de S047: con el ajuste por defecto de solo FIX, el recolector y el replanteo no medirán con el móvil hasta que el usuario admita SINGLE con su confirmación.
- RF-11: CUANDO se guarde un punto con el GNSS del móvil, EL SISTEMA lo guardará como un punto real (no simulado) con la fuente "GNSS del móvil", la precisión de Android y sus metadatos, sin HDOP ni edad de correcciones.
- RF-20: EL SISTEMA no grabará sesión con el GNSS del móvil.

### Receptor virtual (H2, H3)
- RF-12: EL SISTEMA ofrecerá "Receptor virtual" en la lista de receptores y en "Añadir receptor", y lo conectará como cualquier receptor, con épocas a 1 Hz en tiempo real.
- RF-13: CUANDO el usuario elija el receptor virtual, EL SISTEMA le dejará elegir escenario y posición inicial (el centro del proyecto de S006/RF-37 por defecto, o unas coordenadas).
- RF-14: EL SISTEMA incluirá estos escenarios: "FIX estable", "Pérdida de FIX y recuperación", "FLOAT y SINGLE", "Corte de datos", "Cambio de base", "Inclinación con el movimiento en ocho" y "Paseo en línea recta", cada uno con su descripción y su duración.
- RF-15: EL SISTEMA generará las épocas del escenario (GGA, GST, GSA, GSV, ZDA y, donde proceda, EBP y ETC) con la misma lógica de acoplamiento y de estados que un receptor real.
- RF-16: MIENTRAS se ejecute un escenario, EL SISTEMA mostrará "Simulado" y el nombre del escenario en la cabecera y en el indicador de estado, junto a la velocidad (×1 o ×5), que se podrá cambiar.
- RF-17: CUANDO termine el escenario, EL SISTEMA ofrecerá repetirlo, elegir otro o desconectar.
- RF-18: EL SISTEMA no grabará sesión ni admitirá NTRIP con el receptor virtual (como S003/RF-44 y RF-45).
- RF-19: CUANDO se guarde un punto con el receptor virtual, EL SISTEMA lo guardará sin pedir confirmación, marcado como simulado, lo mostrará como tal en su ficha y lo escribirá en "Todas las columnas" de la exportación CSV (S012/RF-6).

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T1: el permiso de ubicación se pide al elegir el móvil, no al arrancar.
  - RNF-T2: servicio de tipo `location` solo en el modo GNSS del móvil.
  - RNF-T3: estados explícitos y "Sin datos" a los 2 s en el virtual y a los 3 s en el móvil.
  - RNF-T7 y RNF-T8: textos en recursos y zonas táctiles de 56 dp; el estado SINGLE o "Simulado" se indica con texto, no solo con color.
  - RNF-T10: la hora UTC de las épocas virtuales parte del reloj del móvil al conectar y avanza con el escenario.
- Con el mismo escenario y posición, dos ejecuciones dan épocas idénticas byte a byte.
- El receptor virtual no consume más de un 5 % de CPU en el móvil de referencia.

## Casos límite
| Caso | RF |
|---|---|
| Permiso de ubicación denegado | RF-2 |
| Ubicación apagada | RF-3 |
| Android declara 1 m de precisión | RF-5 |
| Otra app inyecta una posición RTK como ubicación simulada | RF-7 |
| Medir con el móvil con «solo FIX» | RF-10 |
| Fin del escenario con un punto sin guardar | RF-17 |
| Replanteo con un escenario de paseo | RF-14, RF-16 |

## Fuera de alcance
- Escenarios escritos o importados por el usuario → no previsto.
- Usar una posición simulada por otra app como si fuera RTK → no previsto.
- NTRIP o PPP en el móvil → no previsto.
- Medidas de calidad geodésica con el móvil → no previsto (constitución nº 3).
- Receptor virtual como herramienta de pruebas automáticas de otras specs → ya cubierto por S003 y `core:testing`.

## Criterios de finalización
- Todos los RF con test en verde, informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Demo en el emulador: cada escenario recorrido en el recolector y el replanteo, y el GNSS del móvil con una ubicación simulada del emulador.
- Demo de campo: queda para el usuario (GNSS del móvil al aire libre).

## Dudas abiertas
Ninguna. Resueltas en la entrevista del 15/11/2026:
- El receptor virtual está siempre disponible en la lista, sin ajuste de desarrollador (RF-12).
- Los puntos del GNSS del móvil se guardan como reales, con su fuente y su precisión, sin marca de «aproximado» (RF-11); medir con ellos exige admitir SINGLE y confirmar (RF-10).
- Los puntos del receptor virtual se guardan marcados como simulados, sin confirmación y en cualquier proyecto (RF-19).
- Los siete escenarios son fijos de la app, sin editor ni importación (RF-14).
- Ni el móvil ni el virtual graban sesión (RF-18 para el virtual, RF-20 para el móvil).

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`: S022 pasa a `spec`; dependencia corregida a S003, S005 y S047 en lugar de S021 (ninguna de las dos fuentes usa TCP).
- `docs/glosario.md`: añadir «GNSS del móvil», «Receptor virtual», «Escenario» y «Fuente de la posición»; S010/RF-18 y S012/RF-6: fuente de la posición.
- `docs/rnf-transversales.md` no cambia (RNF-T1 y RNF-T2 ya prevén el modo GNSS del móvil).
