# Registro del harness

Cada vez que corrijas al agente en algo que debería saber, anótalo aquí y decide a qué nivel sube.
Niveles: doc (spec/CLAUDE.md/rules) → test → hook/deny. Solo el humano edita CLAUDE.md, la spec y los hooks.

| Fecha | Qué pasó | Causa | Cambio en el harness | Nivel |
|---|---|---|---|---|
| 2026-11-03 | Harness v1 creado antes del primer commit | — | CLAUDE.md, rules, 4 hooks, 2 revisores, 3 skills | — |
| 2026-11-03 | Decisión pendiente: jqwik y JUnit 6 | jqwik 1.10.x se basa en JUnit Platform 1.x; Spring Boot 4 usa JUnit 6 | Resuelta: jqwik reemplazado por JUnit 6 nativo con generador de semilla fija; spec §11 y §12.2 actualizadas | doc |
| 2026-11-03 | Stack actualizado tras verificar versiones | Spec citaba Spring Boot 4.1.1 | Spec §11: Spring Boot 4.1.2, Gradle 9.7.0, ArchUnit 1.5.0 (`archunit-junit6`) | doc |
