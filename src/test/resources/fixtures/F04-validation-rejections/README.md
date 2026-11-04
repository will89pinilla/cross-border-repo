# F04 · Rechazos por validación (AC08, AC09; AC05–AC07 en T05)

Por ahora solo trae la parte de AC08 y AC09, que es la que cubre T03. Los eventos de AC05 (moneda incorrecta), AC06 (cuenta inexistente) y AC07 (cuenta bloqueada) se agregan en T05, junto con la cuenta `BLOCKED` de la semilla.

Semilla: la del caso (§2): ACC123456789 MXN 200000.00, ACC987654321 USD 0.00.

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

## Cálculo a mano de los válidos (saldo = créditos − débitos)

Orden D4 en la carga: el 407 tiene fecha inválida y cuenta como la más antigua (§7.3), y después van los demás por `operation_date`. Solo se aplican 401 y 410.

| Cuenta | Inicial | 401 | 410 | Final |
| --- | --- | --- | --- | --- |
| ACC123456789 (MXN) | 200000.00 | −100.00 | −10.50 | 199889.50 |
| ACC987654321 (USD) | 0.00 | +5.50 | +0.58 | 6.08 |
| COBRE_FX_POSITION_MXN | 0.00 | +100.00 | +10.50 | 110.50 |
| COBRE_FX_POSITION_USD | 0.00 | −5.50 | −0.58 | −6.08 |

Invariante MXN: 199889.50 + 110.50 − 200000.00 (`COBRE_FUNDING_MXN`) = 0.00. USD: 6.08 − 6.08 = 0.00.

`summary`: events_read 10, applied 2, rejected 8, duplicates 0. `rejected`, en orden D4: 407, 402, 403, 404, 405, 406, 408, 409.

## Estado

- T03: el lector y el traductor se prueban con este archivo (`CbmmEventTranslatorTest.AC08_fixtureF04...`).
- T05: agregar los eventos de AC05–AC07 y la cuenta `BLOCKED`, y volver a calcular esta tabla.
- T07: falta `expected.json` (reporte sin `metadata`), escrito desde esta tabla, y el test por la API.
