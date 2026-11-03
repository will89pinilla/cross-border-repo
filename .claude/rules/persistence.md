---
paths:
  - "src/main/java/com/cobre/accounts/adapters/out/persistence/**"
  - "src/main/resources/db/migration/**"
---
# Persistencia (spec §9, D10, D12, D13)
- `JdbcClient`, sin JPA. Batch para insertar las líneas del asiento.
- `SELECT ... FOR UPDATE` solo de cuentas CLIENT, en orden ascendente de `account_id`.
- `settlement_decision`: la PK `source_event_id` respalda la idempotencia; un conflicto revierte la transacción y el evento se reporta como duplicado.
- Solo INSERT en `journal_entry`, `entry_line` y `settlement_decision`. UPDATE solo en `account` (saldo de CLIENT).
- Roles: Flyway como `accounts_owner`; la app como `accounts_app` con los permisos de §9 (AC18).
- Una migración ya versionada no se edita: se crea `V<n+1>__...`.
- Saldo de cuentas de Cobre: suma de líneas (CREDIT +, DEBIT −); su columna `balance` es NULL.
