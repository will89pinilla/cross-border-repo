# F09 · Un rechazo por fondos sigue rechazado cuando llegan fondos (AC04, D6, §7.5)

Semilla: ACC123456789 (la "A" de AC04) MXN ACTIVE 100.00; ACC987654321 USD ACTIVE 20.00 (la cuenta USD con fondos).
Cada saldo inicial con su apertura contra `COBRE_FUNDING_<moneda>` (D9).

| Carga | Archivo | event_id | Fecha | Qué tiene |
| --- | --- | --- | --- | --- |
| 1 | F09-e1.json | `cbmm_20250909_000901` (E1) | 10:00 | A entrega 150.00 MXN; ACC987654321 recibe 8.25 USD |
| 2 | F09-credit.json | `cbmm_20250909_000902` | 11:00 | ACC987654321 entrega 5.50 USD; A recibe 100.00 MXN |
| 3 | F09-e1.json | `cbmm_20250909_000901` (E1) | 10:00 | idéntico byte a byte al de la carga 1 |

## Resultados

| Carga | Resultado | A después |
| --- | --- | --- |
| 1 | E1 `INSUFFICIENT_FUNDS` (regla 6: 100.00 < 150.00); decisión `REJECTED` guardada | 100.00 |
| 2 | 902 `APPLIED` (ACC987654321: 20.00 ≥ 5.50) | 200.00 |
| 3 | E1 en `duplicates`, `original_decision` `REJECTED`, `payload_mismatch: false`; no se evalúa otra vez aunque ahora 200.00 ≥ 150.00 | **200.00** |

## Cálculo a mano al final de la carga 3 (saldo = créditos − débitos)

| Cuenta | Inicial | Carga 2 (902) | Final |
| --- | --- | --- | --- |
| ACC123456789 (MXN) | 100.00 | CREDIT 100.00 | **200.00** |
| ACC987654321 (USD) | 20.00 | DEBIT 5.50 | **14.50** |
| COBRE_FX_POSITION_USD | 0.00 | CREDIT 5.50 | **5.50** |
| COBRE_FX_POSITION_MXN | 0.00 | DEBIT 100.00 | **−100.00** |
| COBRE_FUNDING_MXN | −100.00 | — | −100.00 |
| COBRE_FUNDING_USD | −20.00 | — | −20.00 |

Invariante MXN: 200.00 − 100.00 − 100.00 = 0.00. USD: 14.50 + 5.50 − 20.00 = 0.00.
`entry_line`: 4 (dos aperturas) + 4 (902) = 8; E1 nunca tiene líneas.

Reportes esperados (sin `metadata`):
- Carga 1: `summary` 1/0/1/0; `rejected` = [E1, F09-e1.json, 0, `INSUFFICIENT_FUNDS`, sin detalle, `decision_stored: true`]; `accounts` = [].
- Carga 2: `summary` 1/1/0/0; `accounts` = ACC123456789 200.00, ACC987654321 14.50, COBRE_FX_POSITION_MXN −100.00, COBRE_FX_POSITION_USD 5.50.
- Carga 3: `summary` 1/0/0/1; `duplicates` = [E1, F09-e1.json, 0, `REJECTED`, false]; `accounts` = [].

## Estado

- T06: `LoadEventFilesServiceIntegrationTest.AC04_...` contra PG18.
- T07: `expected-1.json`, `expected-2.json` y `expected-3.json` desde estas tablas, y el test por la API.
