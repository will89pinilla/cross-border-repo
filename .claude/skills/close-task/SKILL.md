---
name: close-task
description: Cierra la tarea en curso: revisión, registro de uso de IA, progreso y commit.
disable-model-invocation: true
---
1. Corre `./gradlew build --quiet`. Si falla, detente.
2. Corre `git diff --name-only HEAD` y `git status --porcelain` para listar los archivos cambiados.
3. Lanza EN PARALELO, con la tarea y esa lista: el subagente spec-reviewer y, si la tarea es T02, T04, T05, T06 o T08
   (o se tocó domain/, persistence o el scheduler), el subagente ledger-reviewer.
4. Muéstrame sus hallazgos. Corrige los ALTOS y las brechas de criterios; los MEDIOS y BAJOS, pregúntame.
   Si cambiaste código por los hallazgos, haz UNA segunda pasada acotada (nunca más de una):
   - Corre `./gradlew build`.
   - Vuelve a lanzar solo el revisor cuyo ámbito tocaste (ledger-reviewer si cambió dinero, persistencia,
     concurrencia o idempotencia; spec-reviewer si cambiaron criterios o tests), pasándole SOLO los archivos
     que cambiaron en esta ronda de correcciones.
   - Si encuentra un ALTO, corrígelo. Si solo encuentra MEDIOS o BAJOS, anótalos en progress.json para la
     tarea que corresponda; no abras otra ronda.
   - Si los cambios fueron solo de documentación, Javadoc o tests nuevos que pasan, no hace falta segunda pasada:
     dilo explícitamente.
5. Crea docs/ai-usage/<TAREA>.md con la plantilla de docs/ai-usage/README.md:
   prompts de esta tarea (tómalos de docs/ai-usage/prompts.md), qué produjiste, qué hallazgos hubo en la revisión
   y la salida final del build. Deja la sección "Corregido a mano" y "Captura" para que yo la complete.
6. En docs/progress.json: status "done", passes true, notes de una línea, "next" a la siguiente tarea, "last_session".
7. Propón el commit con el formato `T05: <resumen> (AC01, AC02, ...)` y ejecútalo (me pedirá confirmación).
