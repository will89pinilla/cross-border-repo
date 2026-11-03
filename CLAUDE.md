# accounts-service · CBMM Task 2 (Cobre challenge)

Fuente de verdad: `docs/spec/task2-accounts-module.md` (la "spec"): decisiones D1–D16 (§5), glosario (§4),
procesamiento (§7), salida (§8), persistencia (§9), criterios AC01–AC18 (§10), stack (§11) y reglas de IA (§12).
Contexto, solo diseño (no se implementa): `docs/design/task1-event-flow.md`. Enunciado: `docs/challenge/`.
Entrega: 2026-11-05 08:00 (America/Bogota). Prioridad: criterios de aceptación en verde antes que extras.

## Comandos (existen a partir de T01)
- Terminado = `./gradlew build` en verde (corre ArchUnit y Testcontainers: Docker debe estar arriba)
- Un test: `./gradlew test --tests 'com.cobre.accounts.<Clase>'`
- App local: `docker compose up -d` · Benchmark (T08): `./gradlew benchmark`
- `docker compose down -v` borra los datos: solo lo corre el humano

## Cómo se trabaja
- Una tarea del backlog (spec §12.5) a la vez, según `docs/progress.json`. Se empieza con `/next-task` y se cierra con `/close-task`.
- Primero el test del criterio, con su id en el nombre (`AC05_currencyMismatchOnOrigin`), luego el código.
- Al decir que algo funciona, muestra el comando que corriste y su salida.
- Si la spec es ambigua, se contradice o no cubre un caso: detente y pregunta. La spec está protegida: propón el cambio, no lo hagas.
- Ninguna dependencia nueva ni cambio de versión sin que el humano actualice antes la spec §11.

## Convenciones que no se deducen del código
- Nombres del glosario (§4) obligatorios en clases, tablas, métricas y tests. "Transacción" solo en el endpoint `/transactions`.
- Dinero: `Money` (BigDecimal, escala 2). Los montos se leen como BigDecimal desde el texto del JSON, nunca pasando por double. Nunca se redondea un monto de entrada (D11).
- La transacción la abre `application` a través del puerto `UnitOfWork`; su adaptador usa `TransactionTemplate`. Sin `@Transactional` en `domain` ni en `application`.
- Locks: `SELECT ... FOR UPDATE` solo sobre cuentas `CLIENT`, en orden ascendente de `account_id` (D12).
- El saldo de las cuentas de Cobre es la suma de sus líneas; nunca se actualiza una fila por asiento (D10).
- `journal_entry`, `entry_line` y `settlement_decision`: solo INSERT (D13).
- Determinismo: orden D4 y scheduler D5. Dos reportes sin `metadata` deben ser idénticos (AC11).
- Los `expected.json` de los fixtures se derivan de la spec, nunca copiando la salida de la implementación.

## Trampas
- Spring Boot 4 trae Jackson 3 (paquetes `tools.jackson.*`) y JUnit 6. Verifica imports y compatibilidad antes de asumir APIs de Jackson 2 o JUnit 5.
- Pruebas de propiedades: JUnit 6 nativo (`@ParameterizedTest` + generador de semilla fija). No agregues jqwik ni otra librería de property-based testing (spec §11).

## Estado del trabajo
@docs/progress.json

## Al compactar
Conserva: tarea en curso, criterios pendientes, archivos modificados y el último error del build.
