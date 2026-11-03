---
paths:
  - "src/main/java/com/cobre/accounts/adapters/in/**"
---
# Adaptadores de entrada (spec §6, §8)
- Lector de archivos: detecta arreglo JSON, objeto único o NDJSON según §6.2; arreglo u objeto inválido = archivo `UNREADABLE`; línea NDJSON inválida = `INVALID_EVENT/MALFORMED_JSON`.
- `amount` se lee como BigDecimal desde el TEXTO del JSON (número o string), nunca vía double. `10.500` válido, `10.005` no.
- Reglas 1 y 2 de §7.2 se validan aquí; el traductor (capa anticorrupción) produce el comando `PostSettlement`.
- REST: `POST /v1/event-files` (multipart `files`), `GET /v1/accounts/{id}/balance`, `GET /v1/accounts/{id}/transactions?limit&offset` (limit ≤ 1000).
- Montos en la salida: texto con exactamente 2 decimales, sin separador de miles. Errores con códigos de §8.2.
- Un adaptador no depende de otro adaptador (§11), con una sola excepción: `adapters.in.rest` puede usar `adapters.in.file`.
