---
name: next-task
description: Inicia una sesión tomando la siguiente tarea del backlog desde docs/progress.json.
disable-model-invocation: true
---
1. Lee docs/progress.json y `git log --oneline -10`.
2. Si existe ./gradlew, corre `./gradlew build --quiet`. Si falla, DETENTE y muéstrame el error: arreglar el build va primero.
3. Toma la tarea indicada en "next" (o la que te diga en el argumento: $ARGUMENTS). Revisa que sus dependencias estén en "done".
   Márcala "in_progress" en docs/progress.json.
4. Lee en la spec la fila de la tarea (§12.5), sus criterios (§10) y las secciones que esos criterios citan.
5. Propón un plan corto: por cada criterio, qué test (con su id en el nombre) y qué clases. Incluye qué fixtures necesitas.
   No escribas código todavía. Espera mi OK.
6. Con mi OK: criterio por criterio, primero el test (que falle), luego el código mínimo, luego `./gradlew build`.
7. Cuando todo esté verde, dime: "Listo para /close-task" con el comando y la salida del build como evidencia.
