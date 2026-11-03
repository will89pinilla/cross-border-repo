---
name: add-fixture
description: Cómo crear un fixture (F01–F09) con su seed, archivos de entrada y expected.json. Úsala siempre que una tarea necesite datos de prueba de punta a punta.
---
Estructura (spec §11, §12.1):
src/test/resources/fixtures/F0X-<nombre>/
├── seed.json            semilla de cuentas (formato §6.3)
├── loads/1/<archivos>   archivos de la carga 1 (y loads/2, loads/3 si el criterio tiene varias cargas, como F03 y F09)
└── expected.json        reporte esperado SIN metadata (o expected-<n>.json por carga)

Reglas:
1. El expected.json se calcula A MANO desde la spec: saldos con la convención saldo = créditos − débitos, las 4 líneas de §7.1,
   orden de cuentas y líneas de §8.1, montos como texto con 2 decimales, invariant "0.00" por moneda.
   Escribe el cálculo como comentario en un README.md del fixture para que el humano lo revise.
2. NUNCA generes el expected.json ejecutando la implementación y copiando su salida: el test dejaría de probar algo.
3. F07 es la excepción: su oráculo es la implementación secuencial de referencia dentro de los tests (§12.1).
4. Usa los ids de cuenta y eventos de la spec cuando existan (ACC123456789, ACC987654321, cbmm_20250909_000123).
5. Al terminar, pide al humano que revise el README.md del fixture antes de dar el criterio por terminado.
