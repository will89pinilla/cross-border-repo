# demo/

Carpeta para los archivos que entregue el panel. `docker-compose.yml` la monta en el contenedor como
`/demo` (solo lectura), así que una semilla propia no obliga a reconstruir la imagen (spec §13).

## Archivos de eventos

Se dejan aquí y se cargan todos en una sola llamada, desde la raíz del repositorio:

```bash
curl -F "files=@demo/a.json" -F "files=@demo/b.json" http://localhost:8080/v1/event-files
```

Formatos aceptados (§6.2): arreglo JSON, un objeto JSON o un evento por línea (NDJSON).

## Semilla del panel

Si los archivos traen cuentas que no están en la semilla por defecto, hay dos caminos:

1. Semilla propia en `demo/seed.json`, con el formato de §6.3:

   ```json
   {
     "accounts": [
       { "account_id": "ACC123456789", "currency": "MXN", "status": "ACTIVE", "initial_balance": "200000.00" },
       { "account_id": "ACC987654321", "currency": "USD", "status": "ACTIVE", "initial_balance": "0.00" }
     ]
   }
   ```

   `status` es `ACTIVE`, `BLOCKED` o `CLOSED`; la moneda debe estar en `cbmm.currencies` (`MXN,USD`);
   los ids son ASCII imprimible sin espacios, y las cuentas `COBRE_*` no van en la semilla, porque el
   servicio las crea solo; un `account_id` no puede repetirse. Si la semilla tiene un error, el
   servicio no arranca y lo dice en el log
   (`docker compose logs accounts-service`). Arranque:

   ```bash
   docker compose down -v
   CBMM_SEED_PATH=file:/demo/seed.json docker compose up -d --wait
   ```

2. Crear con saldo 0 las cuentas desconocidas (D7):

   ```bash
   docker compose down -v
   CBMM_ACCOUNTS_AUTO_CREATE=true docker compose up -d --wait
   ```

`docker compose down -v` borra los saldos y el ledger. Sin él, la semilla nueva solo crea las cuentas
que todavía no existen: una cuenta que ya existe no se modifica (§6.3).
