# F05 · Orden por operation_date entre archivos (AC10, §7.4)

Semilla: ACC100000001 (la "A" de AC10) MXN ACTIVE 100.00; ACC200000001 USD ACTIVE 0.00 (destino de E1 y E3);
ACC200000002 USD ACTIVE 10.00 (origen de E2, "una cuenta USD con fondos"). Cada saldo inicial con su apertura contra `COBRE_FUNDING_<moneda>` (D9).

Carga 1, dos archivos. En `F05-file2.json`, E3 va **antes** que E2: la posición en el archivo no decide, decide `operation_date` (D4).

| Archivo | Pos | Evento | event_id | Fecha | Qué tiene |
| --- | --- | --- | --- | --- | --- |
| F05-file1.json | 0 | E1 | `cbmm_20250909_000501` | 10:00 | A entrega 80.00 MXN; ACC200000001 recibe 4.40 USD |
| F05-file2.json | 0 | E3 | `cbmm_20250909_000503` | 10:30 | A entrega 60.00 MXN; ACC200000001 recibe 3.30 USD |
| F05-file2.json | 1 | E2 | `cbmm_20250909_000502` | 09:00 | ACC200000002 entrega 2.75 USD; A recibe 50.00 MXN |

Orden D4: E2 (09:00), E1 (10:00), E3 (10:30). Los tres se aplican.

| Paso | A antes | Movimiento | A después |
| --- | --- | --- | --- |
| E2 | 100.00 | +50.00 | 150.00 |
| E1 | 150.00 | −80.00 | 70.00 (70 ≥ 0) |
| E3 | 70.00 | −60.00 | 10.00 (10 ≥ 0) |

Por llegada (E1, E3, E2) daría 100 − 80 = 20; E3 rechazado por fondos (20 < 60); + 50 = 70.00. Ese no es el resultado esperado.

## Cálculo a mano (saldo = créditos − débitos; líneas de §7.1)

| Cuenta | Inicial | E2 (09:00) | E1 (10:00) | E3 (10:30) | Final |
| --- | --- | --- | --- | --- | --- |
| ACC100000001 (MXN) | 100.00 | CREDIT 50.00 | DEBIT 80.00 | DEBIT 60.00 | **10.00** |
| ACC200000001 (USD) | 0.00 | — | CREDIT 4.40 | CREDIT 3.30 | **7.70** |
| ACC200000002 (USD) | 10.00 | DEBIT 2.75 | — | — | **7.25** |
| COBRE_FX_POSITION_MXN | 0.00 | DEBIT 50.00 | CREDIT 80.00 | CREDIT 60.00 | **90.00** |
| COBRE_FX_POSITION_USD | 0.00 | CREDIT 2.75 | DEBIT 4.40 | DEBIT 3.30 | **−4.95** |
| COBRE_FUNDING_MXN | −100.00 | — | — | — | −100.00 |
| COBRE_FUNDING_USD | −10.00 | — | — | — | −10.00 |

Invariante MXN: 10.00 + 90.00 − 100.00 = 0.00. USD: 7.70 + 7.25 − 4.95 − 10.00 = 0.00.

Reporte esperado (sin `metadata`): `files` = [F05-file1.json PROCESSED 1, F05-file2.json PROCESSED 2];
`summary` = events_read 3, applied 3, rejected 0, duplicates 0; `rejected` = []; `duplicates` = [];
`accounts` = las cinco cuentas con líneas en la carga (ACC100000001, ACC200000001, ACC200000002, COBRE_FX_POSITION_MXN,
COBRE_FX_POSITION_USD; las de fondeo no tienen líneas en esta carga), con los saldos finales de la tabla.

## Estado

- T06: `LoadEventFilesServiceIntegrationTest.AC10_...` verifica orden, saldos e invariante contra PG18.
- T07: escribir `expected.json` desde esta tabla y el test por la API.
