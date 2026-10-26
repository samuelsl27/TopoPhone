# Spec 054 — Replanteo por eje y análisis de puntos

> Hito: H8 · Depende de: S049, S018, S019 · Estado: aprobada (26/10/2026)

## Contexto y objetivo
Con el eje y la rasante en el proyecto (S049), el trabajo de campo de una obra lineal es replantear puntos por **PK y desplazamiento**: «voy al PK 1+240, a 3,50 m a la derecha, y me faltan 12 cm de desmonte». S041 replantea las líneas de un DXF a lo largo de ellas, pero no sabe de PK, de derecha e izquierda del eje ni de la cota que da una rasante.

Esta spec replantea por PK y desplazamiento con la cota de la rasante, avanzando de PK en PK con un intervalo y por los puntos singulares del eje, con las ayudas de S018, y analiza en vivo dónde está el jalón respecto al eje (PK, desplazamiento y diferencia de cota). El eje y la rasante siguen siendo referencia de diseño: no se editan aquí.

## Usuarios / actores
- Topógrafo en campo con el jalón y el receptor en FIX (o una reproducción), que replantea una obra lineal.

## Historias de usuario
- H1: Como topógrafo quiero replantear puntos por PK y desplazamiento, avanzando de PK en PK, con la cota de la rasante.
- H2: Como topógrafo quiero ver en vivo el PK, el desplazamiento y la diferencia de cota de la posición del jalón respecto al eje.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec (los de S049 más):
- **Espesor**: un valor, positivo o negativo, que se suma a la cota de la rasante.
- **Punto singular**: el PK en el que cambia el tipo de tramo del eje o el vértice de la rasante (S049).

### Replantear por PK y desplazamiento (H1)
- RF-1: CUANDO el usuario elija "Replantear eje", EL SISTEMA pedirá el PK inicial, el desplazamiento, el espesor (0 por defecto) y, opcionalmente, un PK final, un intervalo de PK y la casilla "Puntos singulares", y abrirá el replanteo con la guía de S018.
- RF-2: SI no hay intervalo, ENTONCES EL SISTEMA replanteará solo el PK inicial.
- RF-3: DONDE haya intervalo, EL SISTEMA permitirá pasar al PK siguiente o al anterior, avanzando el intervalo sin salirse del eje ni del PK final.
- RF-4: DONDE esté marcada la casilla "Puntos singulares", EL SISTEMA incluirá al avanzar de PK, por orden, los de los puntos singulares del eje.
- RF-5: EL SISTEMA permitirá cambiar en el replanteo el PK, el desplazamiento y el espesor a mano.
- RF-6: MIENTRAS haya un replanteo por PK, EL SISTEMA mostrará la distancia a moverse adelante o atrás en PK y hacia dentro o fuera del eje, con un cambio a Norte y Este, y las demás ayudas de S018.
- RF-7: DONDE el eje tenga rasante, EL SISTEMA calculará la cota de diseño como la cota de la rasante en el PK más el espesor y mostrará el desmonte o el terraplén con la tolerancia vertical de S019.
- RF-8: SI el jalón queda en un lugar sin proyección sobre el eje (antes de su inicio o después de su fin), ENTONCES EL SISTEMA lo indicará y mostrará solo el Norte y el Este.
- RF-9: SI el PK o el desplazamiento no son válidos o el PK queda fuera del eje, ENTONCES EL SISTEMA no abrirá el replanteo e indicará el PK inicial y el final del eje.
- RF-10: CUANDO el usuario guarde un punto replanteado, EL SISTEMA lo guardará con el sufijo `_stkd` (S018), el PK, el desplazamiento, la cota de diseño y las diferencias, e incluirá el PK y el desplazamiento en el informe de replanteo (S019).
- RF-11: MIENTRAS haya un replanteo por PK, EL SISTEMA usará el eje activo, y permitirá cambiarlo sin salir del replanteo.

### Analizar un punto (H2)
- RF-12: CUANDO el usuario abra "Analizar", EL SISTEMA mostrará en vivo el PK y el desplazamiento de la punta del jalón respecto al eje activo, y la diferencia de cota con la rasante o con la superficie activa (S042), a elegir.
- RF-13: CUANDO el usuario pulse "Medir" en el análisis, EL SISTEMA medirá el punto como el recolector (S010) y lo guardará con su PK, su desplazamiento y su diferencia de cota.
- RF-14: SI el jalón queda en un lugar sin proyección sobre el eje, ENTONCES EL SISTEMA lo indicará y no mostrará PK ni desplazamiento.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T6: guardar puntos en una sola transacción con durabilidad completa.
  - RNF-T7 y RNF-T8: textos en recursos; zonas táctiles de 56 dp; el desplazamiento con la palabra "izquierda" o "derecha", nunca solo con el signo.
  - RNF-T9: unidades, ángulos y PK con el formato del proyecto.
  - RNF-T2: pantalla encendida en el replanteo y en el análisis.
  - RNF-T13: la guía y el análisis se refrescan a 5 Hz como máximo sin bloquear el hilo principal más de 16 ms.
- Constitución nº 2: el PK y el desplazamiento de una posición y la cota de la rasante en un PK coinciden con una referencia independiente a menos de 1 mm.
- Con una reproducción que recorre un eje, la guía de replanteo por PK coincide época a época con un cálculo aparte.

## Casos límite
| Caso | RF |
|---|---|
| Eje sin rasante | RF-7 (se replantea solo la planta) |
| Jalón antes del inicio o después del final del eje | RF-8, RF-14 |
| Intervalo que se pasa del PK final | RF-3 |
| PK fuera del eje | RF-9 |
| Desplazamiento 0 (sobre el eje) | RF-1 |
| Cambiar de eje a mitad de un replanteo | RF-11 |
| Pérdida de FIX durante el replanteo | S018/RF-22, S047/RF-12 |

## Fuera de alcance
- Replanteo por PK y código, cota con peralte → S050.
- Taludes (cabeza y pie) → S051.
- Tomar un eje, perfil longitudinal y perfil transversal → S052.
- Replanteo por voz o con distanciómetro → no previsto.
- Editar el eje o la rasante → no previsto.

## Criterios de finalización
- Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Demo en el emulador con una reproducción que recorre el eje:
  - replantear un PK con desplazamiento a la derecha y ver la guía y el desmonte;
  - avanzar de PK con un intervalo y con puntos singulares;
  - analizar la posición del jalón y medir un punto con PK y desplazamiento.
- Demo de campo: queda para el usuario.

## Dudas abiertas
- [SUPOSICIÓN: el análisis usa la superficie activa de S042 (RF-12); sin ninguna de las dos referencias solo muestra PK y desplazamiento.]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`: nueva fila S054 en H8, en estado `spec`.
- `docs/glosario.md`: se añade "Espesor" (`Thickness`).
- S018 y S019: el replanteo por PK y desplazamiento reutiliza la diana y el desmonte y terraplén.
