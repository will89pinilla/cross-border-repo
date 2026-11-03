---
paths:
  - "src/main/java/com/cobre/accounts/application/**"
---
# Reglas de la capa de aplicación (spec §3.3, §7.3, §7.4)
- Sin Spring ni adaptadores: solo dominio y puertos (`port/in`, `port/out`). El wiring vive en `config/`.
- La transacción por evento se abre con `UnitOfWork`; los reintentos (40P01/40001, 3 intentos, 50 ms ×2 + jitter) viven en su adaptador.
- Orden de D4: operation_date, event_id, archivo, posición. Fecha inválida = la más antigua; event_id ausente = "".
- `DependencyScheduler` (D5): un evento depende del último anterior que tocó cada una de sus cuentas CLIENT; máximo `cbmm.processing.max-concurrency`.
- Duplicados (D6): dentro de la carga gana el primero según D4 (`payload_mismatch` si difiere); entre cargas, la decisión guardada.
- Una sola carga a la vez con el puerto `LoadLock` (409 `LOAD_IN_PROGRESS`).
- Los eventos de dominio se publican DESPUÉS del commit.
