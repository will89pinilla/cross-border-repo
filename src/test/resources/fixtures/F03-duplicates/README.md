# F03 · Duplicados en la misma carga y entre cargas (AC03, AC17)

Semilla: la del caso (§2): ACC123456789 MXN ACTIVE 200000.00, ACC987654321 USD ACTIVE 0.00.

Carga 1: `F03-a.json` y `F03-b.json`. Carga 2: los mismos dos archivos otra vez (`loads/2`, copia exacta de `loads/1`).

| Archivo | Pos | event_id | Fecha | Qué tiene |
| --- | --- | --- | --- | --- |
| F03-a.json | 0 | `cbmm_20250909_000301` | 10:00 | ACC123456789 entrega 1000.00 MXN; ACC987654321 recibe 55.00 USD |
| F03-a.json | 1 | `cbmm_20250909_000302` | **12:00** | 100.00 MXN → 5.50 USD (versión "X") |
| F03-b.json | 0 | `cbmm_20250909_000301` | 10:00 | idéntico byte a byte a F03-a.json:0 |
| F03-b.json | 1 | `cbmm_20250909_000302` | **11:00** | 200.00 MXN → 11.00 USD (versión "Y": mismo id, otro contenido) |

## Carga 1

Orden D4 (fecha, event_id, archivo, posición):

| # | Evento | Resultado | Por qué |
| --- | --- | --- | --- |
| 1 | 301 @ F03-a.json:0 (10:00) | `APPLIED` | primero de su id; empata en fecha e id con el de F03-b y gana por nombre de archivo |
| 2 | 301 @ F03-b.json:0 (10:00) | duplicado, `APPLIED`, `payload_mismatch: false` | AC03: mismo contenido, mismo `payload_hash` |
| 3 | 302 @ F03-b.json:1 (11:00) | `APPLIED` | AC17: gana por D4 (11:00 < 12:00) aunque F03-a se lea antes |
| 4 | 302 @ F03-a.json:1 (12:00) | duplicado, `APPLIED`, `payload_mismatch: true` | AC17: su hash difiere del guardado con la decisión de Y |

Los duplicados no tocan la base (§7.5): solo hay dos asientos nuevos (301 y 302-Y).

Cálculo a mano (saldo = créditos − débitos):

| Cuenta | Inicial | 301 | 302 (Y) | Final |
| --- | --- | --- | --- | --- |
| ACC123456789 (MXN) | 200000.00 | DEBIT 1000.00 | DEBIT 200.00 | **198800.00** |
| ACC987654321 (USD) | 0.00 | CREDIT 55.00 | CREDIT 11.00 | **66.00** |
| COBRE_FX_POSITION_MXN | 0.00 | CREDIT 1000.00 | CREDIT 200.00 | **1200.00** |
| COBRE_FX_POSITION_USD | 0.00 | DEBIT 55.00 | DEBIT 11.00 | **−66.00** |
| COBRE_FUNDING_MXN | −200000.00 | — | — | −200000.00 |

Invariante MXN: 198800.00 + 1200.00 − 200000.00 = 0.00. USD: 66.00 − 66.00 = 0.00.
`summary`: events_read 4, applied 2, rejected 0, duplicates 2. `entry_line`: 2 (apertura) + 8 = 10.

## Carga 2 (los mismos archivos)

Todo `event_id` ya tiene decisión (paso 1 de §7.3, D6): los cuatro salen en `duplicates`, en el mismo orden D4 de la carga 1.

| # | Evento | `original_decision` | `payload_mismatch` |
| --- | --- | --- | --- |
| 1 | 301 @ F03-a.json:0 | `APPLIED` | false |
| 2 | 301 @ F03-b.json:0 | `APPLIED` | false |
| 3 | 302 @ F03-b.json:1 (Y) | `APPLIED` | false (igual al hash guardado, el de Y) |
| 4 | 302 @ F03-a.json:1 (X) | `APPLIED` | true |

Saldos y líneas sin cambio: los de la tabla de la carga 1, `entry_line` sigue en 10.
`summary`: events_read 4, applied 0, rejected 0, duplicates 4. `accounts` = [] (ninguna línea en esta carga).

## Estado

- T06: `LoadEventFilesServiceIntegrationTest.AC03_...` y `AC17_...` contra PG18.
- T07: `expected-1.json` y `expected-2.json` desde estas tablas, y el test por la API.
