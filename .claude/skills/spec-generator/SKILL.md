---
name: spec-generator
description: Usa esta skill cuando el usuario pida crear, redactar o revisar una especificación (spec) de una funcionalidad de TopoPhone. Guía una entrevista de requisitos y produce un spec.md siguiendo la plantilla del proyecto.
---

# Generador de specs (TopoPhone)

Convierte una idea vaga en una especificación acordada. La spec es el
contrato: si algo no está aquí, no se implementa.

Adaptada de mouredev/hello-sdd para un proyecto con 42 specs encadenadas.

## Proceso

1. **Lee el contexto.**
   - `docs/constitution.md`
   - `docs/rnf-transversales.md`
   - `docs/hoja-de-ruta.md`: número, alcance, dependencias y lo que va en specs posteriores.
   - `docs/glosario.md`
   - Las secciones relevantes de `docs/inventario-funcional.md`: qué hace la app de referencia y con qué fuente.
   - Las specs previas de `specs/`, para respetar convenciones y no contradecir lo ya acordado.
2. **Entrevista al usuario.**
   - Preguntas de **UNA en UNA**, máximo 6, esperando respuesta antes de la siguiente.
   - Céntrate en casos límite, comportamiento ante errores, umbrales numéricos y qué queda fuera.
   - No propongas soluciones técnicas. Si el usuario pregunta "¿cómo lo harías?", redirige al QUÉ.
   - Prioriza las preguntas cuya respuesta cambie lo que hay que construir; descarta las que tengan una respuesta obvia por defecto.
3. **Usa el número de la hoja de ruta.**
   - La ruta es `specs/NNN-<slug>/spec.md`, con el NNN y el slug de `docs/hoja-de-ruta.md`.
   - Si la spec no está en la hoja de ruta, pregunta antes de inventar un número.
4. **Redacta** usando `spec-template.md` de esta skill, sin saltarte secciones.
   - Criterios de aceptación **siempre en notación EARS**, numerados RF-1, RF-2, … dentro de la spec. Fuera de ella se citan como `S###/RF-n`.
   - Cada requisito debe ser verificable: si no se te ocurre cómo comprobarlo, está mal escrito.
5. **Requisitos no funcionales.** Cita los transversales que apliquen (`RNF-T3`, …) en lugar de repetirlos. Añade solo los específicos de esta spec, con umbral numérico.
6. **Fuera de alcance.** Cada exclusión que vaya a hacerse más adelante apunta a su spec (`→ S014`).
7. **Marca lo que no sepas** como `[NECESITA ACLARACIÓN: pregunta concreta]`.
   Nunca rellenes un hueco inventando: un hueco visible es información,
   una suposición silenciosa es deuda.
8. **Pide aprobación explícita** al terminar. No pases al plan ni escribas
   código hasta tenerla.

## Reglas

- La spec describe **QUÉ** y **POR QUÉ**. Prohibido incluir stack, arquitectura,
  nombres de archivos, esquemas de datos, algoritmos, librerías o firmas de
  funciones: eso va en `plan.md`. Las tolerancias numéricas (mm, s, Hz) sí son
  requisitos y van en la spec.
- Incluye **siempre** la sección "Fuera de alcance". Es la que evita que la
  funcionalidad crezca sola.
- Un requisito, una frase. Si necesitas un "y" para unir dos comportamientos,
  son dos requisitos.
- Sin adjetivos no medibles: "rápido", "intuitivo", "robusto", "preciso" no son
  requisitos. Escribe el umbral o no lo escribas.
- Términos topográficos en español según `docs/glosario.md` (jalón, replanteo,
  huso, vértice, cota…).
- Idioma: español.
- Tamaño: si la spec pasa de unos 20 RF o no cabe en unas 25 tareas de 30 min
  como máximo, propón dividirla y actualizar la hoja de ruta.

## Notación EARS

Cinco patrones. Elige el que corresponda, no mezcles:

| Patrón | Forma | Cuándo |
|---|---|---|
| Ubicuo | EL SISTEMA \<hará\> | siempre cierto |
| Dirigido por evento | CUANDO \<disparador\>, EL SISTEMA \<hará\> | responde a algo |
| Estado | MIENTRAS \<estado\>, EL SISTEMA \<hará\> | durante una condición |
| Opcional | DONDE \<característica\>, EL SISTEMA \<hará\> | solo si está presente |
| No deseado | SI \<condición\>, ENTONCES EL SISTEMA \<hará\> | errores y casos límite |

Ejemplo bien escrito:

> RF-4: SI la solución deja de ser FIX durante la recolección de un punto con
> control de precisión activo, ENTONCES EL SISTEMA descartará las épocas
> no FIX y mostrará el aviso "Solución perdida" en menos de 1 s.

Mal escrito, para contrastar:

> ~~RF-4: El sistema debe manejar bien la pérdida de FIX y ser preciso.~~
> Sin patrón EARS, sin criterio verificable, dos ideas en una frase y un
> adjetivo no medible.

## Al revisar una spec existente

Si el usuario pide revisar en vez de crear, no reescribas: **detecta y lista**,
numerado, en cuatro bloques:

1. Ambigüedades.
2. Contradicciones entre requisitos, o con specs anteriores.
3. Casos límite no cubiertos.
4. Conflictos con la constitución o con los RNF transversales.

No propongas soluciones hasta que te lo pidan.
