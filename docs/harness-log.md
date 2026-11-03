# Registro del harness

Cada vez que corrijas al agente en algo que debería saber, anótalo aquí y decide a qué nivel sube.
Niveles: doc (spec/CLAUDE.md/rules) → test → hook/deny. Solo el humano edita CLAUDE.md, la spec y los hooks.

| Fecha | Qué pasó | Causa | Cambio en el harness | Nivel |
|---|---|---|---|---|
| 2026-11-03 | Harness v1 creado antes del primer commit | — | CLAUDE.md, rules, 4 hooks, 2 revisores, 3 skills | — |
| 2026-11-03 | Decisión pendiente: jqwik y JUnit 6 | jqwik 1.10.x se basa en JUnit Platform 1.x; Spring Boot 4 usa JUnit 6 | Resuelta: jqwik reemplazado por JUnit 6 nativo con generador de semilla fija; spec §11 y §12.2 actualizadas | doc |
| 2026-11-03 | Stack actualizado tras verificar versiones | Spec citaba Spring Boot 4.1.1 | Spec §11: Spring Boot 4.1.2, Gradle 9.7.0, ArchUnit 1.5.0 (`archunit-junit6`) | doc |
| 2026-11-03 | Ajustes de la spec durante T01 | El agente detectó la ambigüedad de adaptadores vs §3.3 y faltaba definir las aperturas | §11 excepción rest→file; §9 FK compuesta, UNIQUE y NOT NULL; §6.3 aperturas sin decisión | doc |
| 2026-11-03 | Tipos de columnas de settlement_decision | La spec no los definía; el agente los propuso en T01 | §9: reason_detail varchar(64), payload_hash varchar(128) | doc |
| 2026-11-03 | Hallazgos de la revisión de T01 | ledger-reviewer y spec-reviewer (MEDIOS y BAJOS) | §9: UPDATE solo sobre account.balance, CHECK de exclusión APPLIED/REJECTED, UNIQUE(entry_id); §11: javax.sql en la regla de domain. El agente corrigió .gitignore (out/ ignoraba adapters/out) | doc |
