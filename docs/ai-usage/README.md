# Uso de IA en el Task 2

El caso pide documentar en detalle el uso de IA (prompts, capturas y casos de uso). Todo el Task 2 se
construyó con Claude Code (Opus 5.5) sobre una especificación escrita antes del código
([`docs/spec/task2-accounts-module.md`](../spec/task2-accounts-module.md)). El humano decide, y el agente
propone, implementa y revisa.

## Casos de uso de IA

| # | Caso de uso | Qué hace la IA | Qué hace el humano | Ejemplo concreto | Dónde |
|---|---|---|---|---|---|
| 1 | Especificación | Lee la spec antes de cada tarea y avisa de huecos o contradicciones. Nunca la edita: está protegida por un hook | Decide y edita la spec; cada cambio queda en [`harness-log.md`](../harness-log.md) | En el plan de T02, el agente preguntó qué pasa si un evento usa una cuenta `COBRE_*` como origen. La spec no lo cubría, y el humano fijó en §7.2 que es `ACCOUNT_NOT_FOUND` y que D7 nunca la crea | [T02](T02.md), [T05](T05.md) |
| 2 | Plan por tarea | `/next-task`: lee la tarea, sus criterios y las notas, y propone tests (con el id del criterio) y clases, sin escribir código | Aprueba, corrige o recorta el plan | En T06, el humano encontró 6 huecos en el plan del scheduler. El más grave: tomar el permiso del `Semaphore` antes de esperar las dependencias podía bloquear la carga | [T06](T06.md), [T08](T08.md) |
| 3 | Implementación con test primero | Escribe el test con el id del criterio, lo ve fallar y luego escribe el código mínimo | Pide ver el rojo antes del código y verifica que no se debilitó ningún test | AC18 falló primero con `password authentication failed for user "accounts_owner"` y pasó al crear los roles. En T07, el humano revisó los 35 tests en rojo, uno por uno, antes de aprobar el código. En T06 el agente admitió haber escrito código antes del test, y se compensó con una prueba de mutación | [T01](T01.md), [T07](T07.md), [T06](T06.md) |
| 4 | Revisores | `/close-task` lanza en paralelo dos subagentes: `spec-reviewer` (criterios, decisiones, prohibiciones) y `ledger-reviewer` (dinero, idempotencia, concurrencia, determinismo) | Lee los hallazgos y decide | Tres ALTOS encontrados y corregidos con un test primero: un exponente enorme tumbaba toda la carga (T03); la misma cuenta en las dos patas lanzaba una excepción en vez de rechazar (T05); faltaba `max-part-count` y Tomcat cortaba en 50 archivos (T07) | [T03](T03.md), [T05](T05.md), [T07](T07.md) |
| 5 | Cálculo a mano de fixtures | Con la skill `add-fixture`, calcula desde la spec (nunca desde la salida del código) cada `expected.json`, con el cálculo explicado en el README del fixture | Revisa cada cálculo contra el archivo de entrada antes de usarlo | El humano revisó evento por evento los 20 de F04 y los 12 `expected.json`. Cuando el agente corrigió un valor esperado, el humano lo recalculó sin usar su salida (−200000000000000099.98). El oráculo secuencial de T08 se validó contra esos `expected.json` | [T05](T05.md), [T07](T07.md), [T08](T08.md) |
| 6 | Triage de hallazgos | Propone una resolución para cada hallazgo MEDIO o BAJO | Acepta, cambia o rechaza la resolución; lo que no se hace va a "Límites conocidos" | El humano rechazó tres propuestas del agente. En T06, tratar un unlock fallido como error, porque convertía en 500 una carga ya terminada (se cambió por WARN + abort). En T09, documentar un `bootJar` local, porque obligaba al panel a tener Java 25 (se cambió por un Dockerfile de dos etapas). En T08, la frase "cabe con margen en 2 GB", porque solo se había medido el lector | [T06](T06.md), [T09](T09.md), [T08](T08.md) |
| 7 | Harness | Trabaja dentro de reglas, hooks y skills, y reporta cuando una regla le estorba o falla | Endurece el harness con cada corrección (doc → test → hook) | El agente creó archivos con Bash y así esquivó el hook que revisa el Java. El humano agregó `guard-bash.py`, que luego bloqueó un intento parecido en T05. `protect.py` impidió quitar asserts de un test en T02 | [T02](T02.md), [T05](T05.md), [harness-log](../harness-log.md) |

## Registro por tarea

| Tarea | Criterios | Qué se hizo | Destacado de la revisión |
|---|---|---|---|
| [T01](T01.md) | AC18 | Gradle, catálogo, Compose, migración V1, roles | `GRANT UPDATE` solo sobre `account.balance` |
| [T02](T02.md) | §3.4 | Dominio y `PostingService`, test de propiedades | `Money` con tope de precisión (`AMOUNT_TOO_LARGE`) |
| [T03](T03.md) | AC08, AC09, AC13 | Lector de 3 formatos y traductor | ALTO: `NumberFormatException` tumbaba la carga |
| [T04](T04.md) | AC18 | Persistencia, `UnitOfWork` con reintentos, semilla | Prefijo `opening_` reservado; ids ASCII |
| [T05](T05.md) | AC01, AC02, AC05–AC07, AC15 | Caso de uso `PostSettlement` | ALTO: misma cuenta en las dos patas |
| [T06](T06.md) | AC03, AC04, AC10, AC11, AC16, AC17 | Orden D4, duplicados, scheduler D5, lock de carga | MEDIO: unlock fallido devolvía al pool una conexión con el lock |
| [T07](T07.md) | AC14 | API REST, reporte y Swagger | ALTO: `max-part-count` en 50 |
| [T08](T08.md) | AC11, AC12 | Generador F07, oráculo secuencial, benchmark | Reporte completo comparado como texto entre corridas |
| [T09](T09.md) | §11 observabilidad | Métricas, logs ECS, health, imagen de dos etapas | MEDIO: la demo fallaba en un clon limpio |
| [T10](T10.md) | — | README, guion de demo ensayado, `demo/` para la semilla del panel, este registro | MEDIO: el paso 7 de la demo reiniciaba después de cargar |

## Cómo se armó este registro

| Archivo | Quién lo escribe | Contenido |
|---|---|---|
| `prompts.md` | Hook `UserPromptSubmit` (automático) | Cada prompt, con fecha y tarea en curso |
| `build-log.md` | Hook `Stop` (automático) | Cada verificación del build: fecha, tarea, PASS/FAIL |
| `T01.md` … `T10.md` | Skill `/close-task` + el humano | Resumen por tarea con la plantilla de abajo |
| `screenshots/` | El humano | Capturas de tests en verde, revisiones y demo |

## Plantilla por tarea (`TXX.md`)

```markdown
# TXX · <título>

## Prompts usados
(lista tomada de prompts.md)

## Qué produjo el agente
(clases, tests y fixtures; resumen de 3–6 líneas)

## Hallazgos de la revisión
(spec-reviewer / ledger-reviewer: qué encontraron y cómo se resolvió)

## Corregido a mano
(lo completa el humano: qué cambiaste tú y por qué)

## Evidencia
(salida final de ./gradlew build) · Captura: screenshots/TXX.png
```

## Cómo se configuró el agente

El repositorio incluye el harness de Claude Code con el que se construyó: `CLAUDE.md`, `.claude/rules/`,
`.claude/hooks/`, `.claude/agents/` y `.claude/skills/`. `docs/harness-log.md` registra cómo evolucionó.
