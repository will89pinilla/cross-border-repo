# F02 · Débito mayor que el saldo (AC02)

Semilla: ACC123456789 MXN ACTIVE 100.00 (con su apertura contra `COBRE_FUNDING_MXN`), ACC987654321 USD ACTIVE 0.00.
Carga 1: `F02.json`, un arreglo con un evento.

| Pos | event_id | Qué tiene | Resultado esperado (§7.2) |
| --- | --- | --- | --- |
| 0 | `cbmm_20250909_000201` | ACC123456789 entrega 150.00 MXN, ACC987654321 recibe 8.80 USD | `INSUFFICIENT_FUNDS`, sin detalle (regla 6: 100.00 < 150.00) |

Las reglas 3 a 5 pasan: las dos cuentas existen, son `CLIENT`, sus monedas coinciden con sus patas y están `ACTIVE`.

## Cálculo a mano (saldo = créditos − débitos)

Un rechazo no cambia saldos ni crea líneas (§7.2).

| Cuenta | Antes | Después |
| --- | --- | --- |
| ACC123456789 (MXN) | 100.00 | 100.00 |
| ACC987654321 (USD) | 0.00 | 0.00 |
| COBRE_FX_POSITION_MXN | 0.00 | 0.00 |
| COBRE_FX_POSITION_USD | 0.00 | 0.00 |
| COBRE_FUNDING_MXN | −100.00 | −100.00 |

Invariante MXN: 100.00 − 100.00 = 0.00. USD: 0.00.

Reporte esperado (sin `metadata`): `files` = [F02.json, PROCESSED, 1]; `summary` = events_read 1, applied 0, rejected 1, duplicates 0;
`accounts` = [] (ninguna cuenta tiene líneas en esta carga); `rejected` = [cbmm_20250909_000201, F02.json, posición 0, `INSUFFICIENT_FUNDS`, sin detalle];
`duplicates` = []; `invariant` = MXN "0.00", USD "0.00". La decisión guardada es `REJECTED` / `INSUFFICIENT_FUNDS`.

## Estado

- T05: `PostSettlementServiceIntegrationTest.AC02_...` verifica el rechazo, la decisión guardada y que nada cambió en la base.
- T07: escribir `expected.json` desde esta tabla, cuando quede fijo el formato de cada elemento de `rejected` (§8.1 lista sus campos, no sus nombres en JSON), y el test por la API.
