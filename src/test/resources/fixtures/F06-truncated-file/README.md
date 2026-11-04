# F06 · Archivo truncado y archivo válido (AC13)

Una carga con dos archivos. Semilla: la del caso (§2): ACC123456789 MXN 200000.00, ACC987654321 USD 0.00.

| Archivo | Contenido | Resultado esperado (§6.2) |
| --- | --- | --- |
| `F06-truncated.json` | Arreglo cortado dentro del segundo objeto | `UNREADABLE`, `events_read` 0; ninguno de sus eventos se aplica (tampoco `..._000611`, que estaba completo) |
| `F06-valid.json` | Arreglo con dos eventos válidos (el 602 con montos como texto) | `PROCESSED`, `events_read` 2; los dos se aplican |

## Cálculo a mano (saldo = créditos − débitos)

Orden D4 en la carga: 601 (10:00), 602 (11:00). Los del archivo truncado no existen para la carga.

| Cuenta | Inicial | 601 | 602 | Final |
| --- | --- | --- | --- | --- |
| ACC123456789 (MXN) | 200000.00 | −1000.00 | −500.25 | 198499.75 |
| ACC987654321 (USD) | 0.00 | +55.10 | +27.50 | 82.60 |
| COBRE_FX_POSITION_MXN | 0.00 | +1000.00 | +500.25 | 1500.25 |
| COBRE_FX_POSITION_USD | 0.00 | −55.10 | −27.50 | −82.60 |
| COBRE_FUNDING_MXN | −200000.00 | | | −200000.00 (sin líneas en esta carga: no sale en `accounts`) |

Invariante MXN: 198499.75 + 1500.25 − 200000.00 = 0.00. USD: 82.60 − 82.60 = 0.00.

`summary`: events_read 2, applied 2, rejected 0, duplicates 0.

## Estado

- T03: el lector se prueba con estos archivos (`EventFileReaderTest.AC13_fixtureF06...`).
- T07: falta `expected.json` (reporte sin `metadata`), escrito desde esta tabla, y el test por la API.
