# Prompts SDD por fase

Basados en `samples/prompts.md` de [mouredev/hello-sdd](https://github.com/mouredev/hello-sdd), adaptados a TopoPhone.
Sustituye `NNN-slug` por la spec activa de `docs/hoja-de-ruta.md`.

**Ciclo:**
1. Spec
2. Clarificación (vuelve a la spec hasta cerrar las decisiones)
3. Plan
4. Tareas
5. Implementación, una tarea por prompt
6. Validación
7. ¿Hay cambios? Si los hay, se vuelve a la spec. Si no, la spec pasa a `implementada` en la hoja de ruta.

| Fase | Prompt |
|---|---|
| Spec (entrevista) | "NO escribas código. Usa la skill spec-generator para la spec `NNN-slug` de la hoja de ruta. Lee la constitución, los RNF transversales, el glosario y la sección relevante del inventario funcional. Hazme preguntas de UNA en UNA (máx. 6) sobre casos límite, errores, umbrales y alcance. Después genera `specs/NNN-slug/spec.md` con la plantilla. Solo el QUÉ y el POR QUÉ." |
| Clarificación | "Revisa `specs/NNN-slug/spec.md` como un QA profesional. Lista, numerado: (1) ambigüedades, (2) contradicciones (también con specs anteriores), (3) casos límite no cubiertos, (4) conflictos con la constitución o los RNF transversales. Solo detecta, no resuelvas." |
| Plan | "Lee la constitución, `docs/arquitectura.md` y la spec NNN. Sin código: genera `specs/NNN-slug/plan.md` con la plantilla `docs/plantillas/plan.md`: módulos, modelo de datos con ejemplo, algoritmos en pseudocódigo con referencias, contratos y estados, decisiones justificadas (con la alternativa descartada) y estrategia de tests. Indica qué RF cubre cada parte." |
| Tareas | "A partir de spec.md y plan.md, genera `specs/NNN-slug/tasks.md` con la plantilla: tareas de 30 min como máximo, ordenadas por dependencia, cada una con sus RF y una línea 'Hecho cuando:' verificable. Con checkboxes. La última tarea es la validación." |
| Implementación | "Implementa SOLO la tarea Tn de la spec NNN siguiendo plan.md y la constitución. Escribe primero los tests (citando `SNNN/RF-x`) y luego el código. Ejecuta `./gradlew test` y muéstrame el resultado. Marca Tn en tasks.md, indica qué RF cubre y PÁRATE." |
| Validación | "Recorre `specs/NNN-slug/spec.md` RF por RF: qué test lo cubre y el resultado de ejecutarlo. Ejecuta `python tools/trazabilidad/rf_report.py`. Comprueba los criterios de finalización y dame un veredicto: ¿la spec está cumplida?" |
| Cambio | "Nuevo requisito para la spec NNN: <X>. NO toques código: actualiza primero spec.md (RF nuevo en EARS y sus casos límite) y muéstrame el diff." |
| Campo | "Prepara el acta de validación de campo del hito Hn según `docs/validacion-campo/protocolo.md`, con los ensayos que aplican y las tolerancias." |
