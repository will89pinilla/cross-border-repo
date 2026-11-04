# F07 · Generador con semilla fija (AC11, AC12, benchmark)

F07 no tiene archivos ni `expected.json` en disco: se genera en memoria con una semilla fija, y su resultado esperado
lo da el oráculo secuencial (spec §12.1).

- Generador: `support/SettlementScenarioGenerator`. `generate(cuentas, eventos)` produce las cuentas de la semilla y los
  eventos; `seedJson` y `eventFiles(scenario, archivos)` los escriben como semilla (§6.3) y archivos de eventos (§6.2:
  pares en arreglo JSON, impares en NDJSON).
- Qué contiene: colisiones sobre pocas cuentas, fondos insuficientes, cuentas desconocidas, ids `COBRE_*`, monedas
  cruzadas y cuentas `BLOCKED`/`CLOSED`. Además, ~40 % de los eventos comparten uno de 10 minutos (desempata `event_id`),
  ~5 % se repiten idénticos y ~3 % se repiten con el mismo `event_id` y otro monto, en el mismo archivo o en otro.
- Oráculo: `support/SequentialOracle`. Un hilo, en memoria. Reutiliza `EventFileReader` (reglas 1 y 2) y
  `PostingService` (reglas 3 a 6); implementa por su cuenta D4, D6 (en la carga y entre cargas, con `payload_mismatch`)
  y los saldos (Cobre = suma de líneas, D10). Se valida contra los `expected.json` escritos a mano de F03, F05 y F09
  (`SequentialOracleTest`).
- Tests: `application/service/F07SequentialOracleTest` con las semillas 7, 42 y 20261105, 50 cuentas, 1,500 eventos y
  4 archivos; concurrencia 1, 4 y 16; orden normal e invertido; y un caso de dos cargas.
- Benchmark (`./gradlew benchmark`): 1,000 cuentas y 100,000 eventos.
