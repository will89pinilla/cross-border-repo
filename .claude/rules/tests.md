---
paths:
  - "src/test/**"
---
# Tests (spec §10, §12.1, §12.2)
- Nombre con el criterio: `AC03_duplicateInSecondLoadKeepsApplied`. Un criterio puede tener varios tests.
- Dominio: JUnit sin Spring. Adaptadores y punta a punta: Testcontainers con PostgreSQL 18.
- Punta a punta: cada fixture por la API REST contra su `expected.json`, comparando sin `metadata`.
- Los `expected.json` se escriben a partir de la spec (o del oráculo secuencial de F07), nunca copiando la salida de la implementación. Usa la skill add-fixture.
- Nunca @Disabled, nunca quitar o debilitar asserts, nunca Thread.sleep para "esperar" concurrencia.
