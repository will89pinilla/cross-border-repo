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
| 2026-11-03 | El hook de prompts registró los reportes de los subagentes | Llegan como turnos de usuario (<agent-message>) | log-prompt.py ignora <agent-message>, <task-notification> y [Subagent hand-back] | hook |
| 2026-11-03 | Cuentas de Cobre usadas como origen o destino de un evento | La spec no lo cubría; el agente lo detectó en el plan de T02 | §7.2 regla 3: solo cuentas CLIENT; COBRE_* se trata como inexistente; D7 no crea COBRE_* | doc |
| 2026-11-03 | El agente creó archivos de T02 con bash, así que check-java.py no los revisó | Los hooks de Edit/Write no ven escrituras por Bash | Nuevo hook guard-bash.py (PreToolUse Bash) que bloquea escribir src/, la spec y el harness por Bash | hook |
| 2026-11-03 | Pendientes detectados en T02 | Cuentas de D7 en eventos rechazados; largo de ids | §7.2: un rechazo no crea cuentas; FIELD_TOO_LONG para account_id > 64 y event_id > 128 | doc |
| 2026-11-03 | guard-bash.py bloqueó un python3 -c de solo lectura | El patrón de scripts era demasiado amplio (cualquier open()) | Solo bloquea scripts con intención de escritura (open en modo w/a/x, write, json.dump, shutil, os.remove…) | hook |
| 2026-11-03 | Hallazgos de la revisión de T02 | ledger-reviewer y spec-reviewer | §6.1 y §7.2: AMOUNT_TOO_LARGE (máx. 17 dígitos enteros); §6.3: semilla con COBRE_* no arranca; §7.2: misma cuenta en ambas patas = CURRENCY_MISMATCH/DESTINATION; §11: PostSettlement y DomainInvariantException en domain/model | doc |
| 2026-11-03 | En T02 el agente no volvió a correr los revisores tras corregir (y lo dijo) | El paso 4 de /close-task decía "vuelve al paso 1" sin acotar: ambiguo y con riesgo de bucle | close-task: una sola segunda pasada, solo del revisor afectado y solo sobre los archivos cambiados | doc |
| 2026-11-03 | Cómo se calcula el saldo de Cobre | El ledger-reviewer vio que Money.plus por línea depende del orden | §9: SUM en SQL y una sola conversión a Money; notas en T04 y T07 | doc |
