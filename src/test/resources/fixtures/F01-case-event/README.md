# F01 · El evento del caso (AC01, AC14)

Semilla: la del caso (§2): ACC123456789 MXN ACTIVE 200000.00, ACC987654321 USD ACTIVE 0.00.
Carga 1: `F01.json`, un objeto JSON con el evento `cbmm_20250909_000123` de §6.1 (montos como número JSON).

## Asiento esperado (§7.1), `posted_at` = 2025-09-09T15:32:10Z

| Línea | Cuenta | Dirección | Monto |
| --- | --- | --- | --- |
| 1 | ACC123456789 | DEBIT | 15000.50 MXN |
| 2 | COBRE_FX_POSITION_MXN | CREDIT | 15000.50 MXN |
| 3 | COBRE_FX_POSITION_USD | DEBIT | 880.25 USD |
| 4 | ACC987654321 | CREDIT | 880.25 USD |

## Cálculo a mano (saldo = créditos − débitos), igual a la tabla de §2

| Cuenta | Antes | Evento | Después |
| --- | --- | --- | --- |
| ACC123456789 (MXN) | 200000.00 | −15000.50 | 184999.50 |
| ACC987654321 (USD) | 0.00 | +880.25 | 880.25 |
| COBRE_FX_POSITION_MXN | 0.00 | +15000.50 | 15000.50 |
| COBRE_FX_POSITION_USD | 0.00 | −880.25 | −880.25 |
| COBRE_FUNDING_MXN | −200000.00 | | −200000.00 (sin líneas en esta carga: no sale en `accounts`) |
| COBRE_FUNDING_USD | 0.00 | | 0.00 (sin líneas: no sale en `accounts`) |

Invariante MXN: 184999.50 + 15000.50 − 200000.00 = 0.00. USD: 880.25 − 880.25 = 0.00.

`expected.json` es el reporte de §8.1 sin `metadata`, tal como lo da la spec.

## Estado

- T05: `PostSettlementServiceIntegrationTest.AC01_...` verifica saldos, las 4 líneas, la decisión `APPLIED` y el invariante en la base.
- T07: el test por la API compara el reporte con `expected.json`.
