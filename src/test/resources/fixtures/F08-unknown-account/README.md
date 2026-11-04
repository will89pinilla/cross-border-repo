# F08 · Cuentas fuera de la semilla, con D7 apagado y encendido (AC06, AC15)

Semilla: la del caso (§2): ACC123456789 MXN ACTIVE 200000.00, ACC987654321 USD ACTIVE 0.00.
Carga 1: `F08.json`, un arreglo con dos eventos. Ni ACC555000001 ni ACC555000002 están en la semilla.

| Pos | event_id | Fecha | Qué tiene |
| --- | --- | --- | --- |
| 0 | `cbmm_20250909_000801` | 10:00 | ACC123456789 entrega 850.00 MXN; **ACC555000001** (desconocida) recibe 50.00 USD |
| 1 | `cbmm_20250909_000802` | 11:00 | **ACC555000002** (desconocida) entrega 100.00 MXN; ACC987654321 recibe 5.00 USD |

Orden D4: 801, 802.

## D7 apagado (`cbmm.accounts.auto-create=false`): AC06

| event_id | Resultado (§7.2, regla 3) |
| --- | --- |
| 801 | `ACCOUNT_NOT_FOUND` / `DESTINATION` |
| 802 | `ACCOUNT_NOT_FOUND` / `ORIGIN` |

Nada cambia y no se crea ninguna cuenta. Saldos finales: ACC123456789 200000.00, ACC987654321 0.00, posiciones en 0.00.
Invariante MXN: 200000.00 − 200000.00 = 0.00; USD 0.00.
`summary`: events_read 2, applied 0, rejected 2, duplicates 0. `accounts` = [].

## D7 encendido (`cbmm.accounts.auto-create=true`): AC15

- 801: la regla 3 crea ACC555000001 como `CLIENT`, `ACTIVE`, USD (la moneda de su pata), saldo 0, y el evento se aplica.
- 802: la regla 3 crea ACC555000002 en MXN con saldo 0, así que la regla 6 la rechaza: `INSUFFICIENT_FUNDS` (0.00 < 100.00).
  Un rechazo no tiene efectos: **ACC555000002 no queda creada**.

Cálculo a mano (saldo = créditos − débitos):

| Cuenta | Antes | 801 | Después |
| --- | --- | --- | --- |
| ACC123456789 (MXN) | 200000.00 | −850.00 | 199150.00 |
| ACC555000001 (USD) | no existe | +50.00 | 50.00 (creada en USD) |
| COBRE_FX_POSITION_MXN | 0.00 | +850.00 | 850.00 |
| COBRE_FX_POSITION_USD | 0.00 | −50.00 | −50.00 |
| ACC987654321 (USD) | 0.00 | | 0.00 (sin líneas: no sale en `accounts`) |
| COBRE_FUNDING_MXN | −200000.00 | | −200000.00 (sin líneas en esta carga) |

Invariante MXN: 199150.00 + 850.00 − 200000.00 = 0.00. USD: 50.00 − 50.00 = 0.00.
`summary`: events_read 2, applied 1, rejected 1, duplicates 0. `accounts`, por `account_id`: ACC123456789, ACC555000001,
COBRE_FX_POSITION_MXN, COBRE_FX_POSITION_USD, cada una con su línea de 801. `rejected` = [802, `INSUFFICIENT_FUNDS`, sin detalle].

## Estado

- T05: `PostSettlementServiceIntegrationTest.AC06_...` y `AC15_...` verifican los dos modos en la base.
- T07: escribir `expected-d7-off.json` y `expected-d7-on.json` desde estas tablas, cuando quede fijo el formato de cada elemento de `rejected`, y el test por la API.
