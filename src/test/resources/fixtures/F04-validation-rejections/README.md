# F04 · Rechazos por validación (AC05–AC09)

Semilla: la del caso (§2) más una cuenta bloqueada para AC07:

| Cuenta | Moneda | Estado | Saldo inicial |
| --- | --- | --- | --- |
| ACC123456789 | MXN | ACTIVE | 200000.00 |
| ACC987654321 | USD | ACTIVE | 0.00 |
| ACC555000009 | MXN | BLOCKED | 1000.00 (recibe su apertura aunque esté bloqueada, §6.3) |

Con la semilla, `COBRE_FUNDING_MXN` = −200000.00 − 1000.00 = −201000.00.

## Eventos de `F04-invalid-events.json` (posición = índice en el arreglo)

| Pos | event_id | Qué tiene | Resultado esperado (§7.2) |
| --- | --- | --- | --- |
| 0 | `cbmm_20250909_000401` | Válido: 100.00 MXN → 5.50 USD | Se aplica |
| 1 | `..._000402` | `origin.amount` 0 | `INVALID_EVENT` / `NON_POSITIVE_AMOUNT` |
| 2 | `..._000403` | `destination.amount` −5.50 | `INVALID_EVENT` / `NON_POSITIVE_AMOUNT` |
| 3 | `..._000404` | `origin.amount` 10.005 (3 decimales) | `INVALID_EVENT` / `TOO_MANY_DECIMALS`; no se redondea a 10.01 |
| 4 | `..._000405` | MXN en las dos patas | `INVALID_EVENT` / `SAME_CURRENCY` |
| 5 | `..._000406` | Sin `destination` | `INVALID_EVENT` / `MISSING_FIELD` |
| 6 | `..._000407` | Fecha `2025-09-09 09:00:00`, sin zona | `INVALID_EVENT` / `INVALID_DATE` |
| 7 | `..._000408` | `origin.currency` EUR | `INVALID_EVENT` / `UNSUPPORTED_CURRENCY` |
| 8 | `..._000409` | `event_type` `cross_border_refund` | `UNSUPPORTED_EVENT_TYPE` (sin detalle) |
| 9 | `..._000410` | Válido: `"10.500"` MXN (= 10.50, ceros finales) → 0.58 USD | Se aplica |
| 10 | `..._000411` | AC05: origen ACC987654321 (cuenta USD) con pata MXN | `CURRENCY_MISMATCH` / `ORIGIN` (regla 4; las dos cuentas existen) |
| 11 | `..._000412` | AC06: origen ACC404000001, fuera de la semilla | `ACCOUNT_NOT_FOUND` / `ORIGIN` |
| 12 | `..._000413` | AC06: destino ACC404000002, fuera de la semilla | `ACCOUNT_NOT_FOUND` / `DESTINATION` |
| 13 | `..._000414` | Origen `COBRE_FX_POSITION_MXN` (existe, pero es de Cobre) | `ACCOUNT_NOT_FOUND` / `ORIGIN` (regla 3: se trata como inexistente) |
| 14 | `..._000415` | AC07: origen ACC555000009 (`BLOCKED`), con fondos | `ACCOUNT_INACTIVE` / `ORIGIN` |
| 15 | `..._000416` | AC07: destino ACC555000009 (`BLOCKED`); origen ACC987654321 en USD | `ACCOUNT_INACTIVE` / `DESTINATION` (la regla 5 va antes que la 6, así que el saldo del origen no importa) |

Los eventos 411 a 416 pasan las reglas 1 y 2 (se traducen a `PostSettlement`) y se rechazan con las reglas 3 a 5, dentro de su transacción.

## Cálculo a mano (saldo = créditos − débitos)

Orden D4 en la carga: el 407 tiene fecha inválida y cuenta como la más antigua (§7.3); después van los demás por `operation_date`, que en este archivo coincide con la posición (401, 402, …, 416). Solo se aplican 401 y 410; un rechazo no cambia saldos ni crea líneas.

| Cuenta | Inicial | 401 | 410 | Final |
| --- | --- | --- | --- | --- |
| ACC123456789 (MXN) | 200000.00 | −100.00 | −10.50 | 199889.50 |
| ACC987654321 (USD) | 0.00 | +5.50 | +0.58 | 6.08 |
| ACC555000009 (MXN) | 1000.00 | | | 1000.00 (sin líneas en esta carga: no sale en `accounts`) |
| COBRE_FX_POSITION_MXN | 0.00 | +100.00 | +10.50 | 110.50 |
| COBRE_FX_POSITION_USD | 0.00 | −5.50 | −0.58 | −6.08 |
| COBRE_FUNDING_MXN | −201000.00 | | | −201000.00 (sin líneas en esta carga) |

Invariante MXN: 199889.50 + 1000.00 + 110.50 − 201000.00 = 0.00. USD: 6.08 − 6.08 = 0.00.

`summary`: events_read 16, applied 2, rejected 14, duplicates 0. `rejected`, en orden D4: 407, 402, 403, 404, 405, 406, 408, 409, 411, 412, 413, 414, 415, 416.

## Estado

- T03: el lector y el traductor se prueban con este archivo (`CbmmEventTranslatorTest.AC08_fixtureF04...`).
- T05: reglas 3 a 5 con este archivo y esta semilla (`PostSettlementServiceIntegrationTest`, AC05–AC07).
- T07: falta `expected.json` (reporte sin `metadata`), escrito desde esta tabla, y el test por la API.
