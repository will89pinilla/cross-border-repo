---
paths:
  - "src/main/java/com/cobre/accounts/domain/**"
---
# Reglas del dominio (spec §3.4, §4, §7.2)
- Java puro: sin Spring, JDBC, Jackson ni anotaciones de framework.
- Invariantes de §3.4 en constructores y métodos; si se violan, excepción de dominio específica (no IllegalStateException genérica).
- `Money`: inmutable, BigDecimal escala 2; operar o comparar monedas distintas lanza excepción; nunca redondea.
- `Account`: moneda y tipo inmutables; `CLIENT` nunca negativo; solo se mueve dinero de cuentas `ACTIVE`; `CLOSED` es final.
- `JournalEntry`: ≥ 2 líneas, débitos = créditos por moneda, montos > 0, moneda de la línea = moneda de su cuenta, inmutable.
- `PostingService` aplica las reglas 3 a 6 de §7.2 en ese orden, devuelve asiento o rechazo y no accede a la base.
- Records para value objects y eventos de dominio (`EntriesPosted`, `PostingRejected`).
