# cross-border-repo · accounts-service (CBMM Task 2)

Módulo de cuentas del caso Cross Border Money Movements de Cobre: saldos y un ledger de partida doble,
con procesamiento concurrente y determinístico de archivos de eventos. Spring Boot 4 y arquitectura
hexagonal sobre PostgreSQL 18.

| Documento | Contenido |
|---|---|
| [Especificación del Task 2](docs/spec/task2-accounts-module.md) | Fuente de verdad: decisiones D1–D16, criterios AC01–AC18, stack |
| [Diseño del flujo de eventos (Task 1)](docs/design/task1-event-flow.md) | El sistema CBMM completo; aquí solo se implementa el Componente B |
| [Enunciado](docs/challenge/cobre-challenge.md) | El caso tal como lo entregó Cobre |
| [Uso de IA](docs/ai-usage/README.md) | Casos de uso, prompts, revisiones y lo corregido a mano, tarea por tarea |

## Evaluar en 3 comandos

Solo se necesita Docker. La primera construcción compila el jar dentro de la imagen y baja Gradle y las
dependencias, así que tarda unos minutos.

```bash
docker compose up -d --build --wait
curl -F "files=@src/test/resources/fixtures/F01-case-event/loads/1/F01.json" http://localhost:8080/v1/event-files
curl http://localhost:8080/v1/accounts/ACC123456789/balance
```

La carga aplica el evento del enunciado: ACC123456789 pasa de 200,000.00 a 184,999.50 MXN, ACC987654321
pasa de 0.00 a 880.25 USD y el `invariant` (suma de saldos por moneda) queda en `"0.00"`.
`--wait` espera a que el servicio esté `healthy` antes de devolver el control.

## Guion de la demo

Todos los comandos se corren desde la raíz del repositorio. Los archivos son los fixtures de los tests:
lo que se ve en la demo es lo mismo que verifican los criterios de aceptación.

```bash
F=src/test/resources/fixtures
```

**1. El evento del caso (AC01).** Sobre una base recién levantada (`docker compose up -d --build --wait`)
se carga F01 y se consulta el saldo y el extracto. Si ya corriste "Evaluar en 3 comandos", F01 ya está
cargado: solo falta el `/transactions`, porque repetir el POST lo convierte en el duplicado del paso 2.

```bash
curl -F "files=@$F/F01-case-event/loads/1/F01.json" http://localhost:8080/v1/event-files
curl http://localhost:8080/v1/accounts/ACC123456789/balance
curl "http://localhost:8080/v1/accounts/ACC123456789/transactions?limit=100&offset=0"
```

El extracto incluye la apertura (`opening_ACC123456789`, crédito de 200,000.00 contra
`COBRE_FUNDING_MXN`, D9) y el débito de 15,000.50. El reporte lista también las dos cuentas de Cobre
que tocó el asiento (`COBRE_FX_POSITION_MXN` y `COBRE_FX_POSITION_USD`).

**2. Idempotencia (AC03, D6).** Se carga el mismo archivo otra vez:

```bash
curl -F "files=@$F/F01-case-event/loads/1/F01.json" http://localhost:8080/v1/event-files
```

El evento sale en `duplicates` con `original_decision: "APPLIED"`, `applied` es 0 y el saldo no cambia.

**3. Archivo truncado junto a uno válido (AC13, D8):**

```bash
curl -F "files=@$F/F06-truncated-file/loads/1/F06-truncated.json" \
     -F "files=@$F/F06-truncated-file/loads/1/F06-valid.json" http://localhost:8080/v1/event-files
```

`F06-truncated.json` sale `UNREADABLE` y los 2 eventos de `F06-valid.json` se aplican.

**4. Límite de la carga (§6.2).** Se envían 1,001 archivos, uno más que el límite:

```bash
args=(); for i in $(seq 1001); do args+=(-F "files=@$F/F01-case-event/loads/1/F01.json"); done
curl -s "${args[@]}" http://localhost:8080/v1/event-files
```

Responde 413 `application/problem+json` con `"code": "PAYLOAD_TOO_LARGE"`, y no se aplica nada.
No conviene probar el límite con un archivo de más de 2 MB: Tomcat puede cortar la conexión sin
responder (ver "Límites conocidos").

**5. Rechazos con una semilla propia (AC05–AC09).** F04 necesita una cuenta `BLOCKED` que no está en la
semilla por defecto. La semilla se cambia sin reconstruir la imagen: el compose monta `./demo` en `/demo`.
Para leer y comparar el reporte se usa `jq`.

```bash
docker compose down -v      # borra saldos y ledger
cp $F/F04-validation-rejections/seed.json demo/seed.json
CBMM_SEED_PATH=file:/demo/seed.json docker compose up -d --wait
curl -s -F "files=@$F/F04-validation-rejections/loads/1/F04-invalid-events.json" \
     http://localhost:8080/v1/event-files > /tmp/F04-report.json
jq '.summary, .rejected' /tmp/F04-report.json
```

De 20 eventos se aplican 2 y se rechazan 18, cada uno con su código y su detalle: `INVALID_EVENT`
(monto ≤ 0, 3 decimales, misma moneda, campo faltante, fecha inválida, moneda no soportada, formato
inválido, monto de 18 dígitos enteros, `account_id` de 65 caracteres), `UNSUPPORTED_EVENT_TYPE`,
`CURRENCY_MISMATCH`, `ACCOUNT_NOT_FOUND` y `ACCOUNT_INACTIVE`. Sobre una base recién sembrada, el reporte
sin `metadata` es idéntico al `expected.json` del fixture, que se calculó a mano desde la spec:

```bash
jq -S 'del(.metadata)' /tmp/F04-report.json | diff - <(jq -S . $F/F04-validation-rejections/expected.json) && echo IDENTICAL
```

**6. Swagger, salud y métricas (D15).** Swagger se abre en el navegador, en
http://localhost:8080/swagger-ui.html.

```bash
curl http://localhost:8080/actuator/health
curl -s http://localhost:8080/actuator/prometheus | grep '^cbmm_'
```

`cbmm_events_total{result,code}` cuenta aplicados, rechazos por código y duplicados. Los logs son JSON
(ECS) con `load_id` y `event_id`: `docker compose logs accounts-service`.

**7. Archivos del panel.** Se dejan en `demo/` y se cargan todos en una sola llamada. Si las cuentas
de los archivos están en la semilla por defecto, basta el `curl`. Si no, primero se reinicia por uno de
dos caminos y después se cargan los archivos. Detalle en [demo/README.md](demo/README.md).

(a) Semilla propia, en `demo/seed.json` con el formato de §6.3:

```bash
docker compose down -v
cp <semilla del panel> demo/seed.json
CBMM_SEED_PATH=file:/demo/seed.json docker compose up -d --wait
curl -F "files=@demo/a.json" -F "files=@demo/b.json" http://localhost:8080/v1/event-files
```

(b) Cuentas nuevas con saldo 0 (D7):

```bash
docker compose down -v
CBMM_ACCOUNTS_AUTO_CREATE=true docker compose up -d --wait
curl -F "files=@demo/a.json" -F "files=@demo/b.json" http://localhost:8080/v1/event-files
```

**8. Volver al estado inicial:** `docker compose down -v`.

## API

| Operación | Respuesta |
|---|---|
| `POST /v1/event-files` (multipart, campo `files`) | 200 con el reporte de la carga; 400 `NO_FILES`; 409 `LOAD_IN_PROGRESS`; 413 `PAYLOAD_TOO_LARGE` |
| `GET /v1/accounts/{id}/balance` | 200 con `account_id`, `type`, `status`, `currency` y `balance`; 404 `ACCOUNT_NOT_FOUND` |
| `GET /v1/accounts/{id}/transactions?limit=100&offset=0` | 200 con las líneas ordenadas y el `total`; 400 `INVALID_PAGINATION`; 404 |
| `GET /actuator/health`, `GET /actuator/prometheus` | Salud y métricas; ningún otro endpoint de Actuator está expuesto |
| `/swagger-ui.html` | Documentación interactiva |

Los archivos aceptan tres formas: arreglo JSON, un objeto o un evento por línea (NDJSON). En la
entrada, `amount` es un número JSON o un texto decimal (§6.1). En el reporte y en las consultas, los
montos salen como texto con exactamente 2 decimales. Los errores responden `application/problem+json`
(RFC 9457) con `code`. El formato completo del reporte está en la spec, §8.1.

## Cómo funciona

```text
adapters/in/rest ──► application (casos de uso) ──► domain (Money, Account, JournalEntry, PostingService)
adapters/in/file ─┘          │ puertos de salida
                             ▼
               adapters/out/persistence (JdbcClient, PostgreSQL) · adapters/out/events (log JSON)
```

- **Partida doble (§7.1).** Cada CBMM es un asiento de 4 líneas: débito al cliente origen y crédito a
  `COBRE_FX_POSITION_<moneda origen>`; débito a `COBRE_FX_POSITION_<moneda destino>` y crédito al cliente
  destino. En cada moneda, la suma de todos los saldos es 0, y el reporte lo muestra en `invariant`.
- **Determinismo (D4, D5).** El orden de aplicación es global: `operation_date`, `event_id`, nombre de
  archivo y posición. Dos eventos corren en paralelo solo si no comparten cuentas de cliente. El reporte
  sale igual con 1, 4 o 16 hilos y con los archivos en cualquier orden (AC11).
- **Idempotencia (D6).** Cada `event_id` se decide una sola vez en la vida del módulo, aplicado o
  rechazado, y la clave primaria de `settlement_decision` lo garantiza en la base. Un rechazo por
  fondos sigue rechazado aunque después lleguen fondos (AC04).
- **Concurrencia (D12).** `SELECT ... FOR UPDATE` solo sobre las cuentas de cliente, siempre en orden
  ascendente de `account_id`. El saldo de las cuentas de Cobre es la suma de sus líneas (D10): no hay
  una fila que todos los asientos actualicen.
- **Ledger inmutable (D13).** El usuario de la aplicación no tiene UPDATE ni DELETE sobre asientos,
  líneas ni decisiones (AC18).
- **Dinero (D11).** `BigDecimal` con escala 2, leído desde el texto del JSON sin pasar por `double`.
  Un monto con más de 2 decimales significativos se rechaza (`10.500` es válido; `10.005`, no);
  nunca se redondea.
- **Una carga a la vez (AC16).** Un advisory lock de PostgreSQL; las consultas siguen respondiendo
  durante una carga.

Configuración principal (variables de entorno del compose entre paréntesis):

| Propiedad | Por defecto |
|---|---|
| `cbmm.seed.path` (`CBMM_SEED_PATH`) | `classpath:seed/accounts.json` (la semilla del enunciado) |
| `cbmm.accounts.auto-create` (`CBMM_ACCOUNTS_AUTO_CREATE`) | `false` (D7) |
| `cbmm.currencies` | `MXN,USD` |
| `cbmm.processing.max-concurrency` | `16` |
| Límites de una carga | 50 MB por archivo, 200 MB por carga, 1,000 archivos, 1,000,000 eventos |

## Desarrollo y pruebas

Requisitos: Java 25 y Docker en marcha (los tests usan Testcontainers con PostgreSQL 18).

```bash
./gradlew build                                              # 530 tests y reglas ArchUnit
./gradlew test --tests 'com.cobre.accounts.e2e.FixturesApiTest'   # una clase
./gradlew benchmark                                          # F07 por HTTP; fuera de build
```

Cada criterio de aceptación es un test con su id en el nombre. Los fixtures F01–F09 están en
`src/test/resources/fixtures/`, cada uno con su semilla, sus archivos, un `expected.json` derivado a mano
desde la spec y un README con el cálculo.

| Criterio | Test de punta a punta (HTTP real) | Otros tests |
|---|---|---|
| AC01, AC02 | `FixturesApiTest` (F01, F02) | `PostSettlementServiceIntegrationTest` |
| AC03, AC17 | `FixturesApiTest` (F03) | `LoadEventFilesServiceIntegrationTest`, `LoadEventFilesServiceTest` |
| AC04 | `FixturesApiTest` (F09) | `LoadEventFilesServiceIntegrationTest` |
| AC05–AC09 | `FixturesApiTest` (F04) | `PostSettlementServiceIntegrationTest`, `CbmmEventTranslatorTest` |
| AC10 | `FixturesApiTest` (F05) | `LoadEventFilesServiceIntegrationTest` |
| AC11 | `FixturesApiTest` (cuerpo HTTP byte a byte) | `LoadReportDeterminismTest`, `F07SequentialOracleTest` |
| AC12 | `FixturesApiTest` | `F07SequentialOracleTest`, `SeedLoaderTest`, `JdbcAccountReadModelTest` |
| AC13 | `FixturesApiTest` (F06) | `EventFileReaderTest` |
| AC14 | `FixturesApiTest`, `AccountsApiTest` | `JdbcAccountReadModelTest` |
| AC15 | `FixturesApiTest` (F08) | `PostSettlementServiceIntegrationTest`, `PostSettlementWiringTest` |
| AC16 | `EventFilesApiErrorsTest` | `LoadEventFilesServiceIntegrationTest`, `PostgresLoadLockTest` |
| AC18 | — | `JournalPermissionsTest` (como `accounts_app` contra PostgreSQL 18) |

`ArchitectureTest` verifica las reglas hexagonales: `domain` sin Spring ni JDBC, `application` sin
adaptadores ni Spring, y adaptadores independientes entre sí.

### Benchmark

`./gradlew benchmark` corre F07 por HTTP: 1,000 cuentas y 100,000 liquidaciones con muchas colisiones.
Cada corrida lee **107,963 eventos**, porque a las 100,000 liquidaciones se suman los duplicados que
agrega el generador, y los eventos por segundo se calculan sobre esos 107,963. En cada corrida verifica
AC12 y que el reporte sea idéntico entre concurrencias.

| `max-concurrency` | Eventos/s | Tiempo |
|---|---|---|
| 1 | 826 | 130.7 s |
| 4 | 2,602 | 41.5 s |
| 16 | 5,425 | 19.9 s |

Medido en un Apple M4 Pro de 14 núcleos y 26 GB, con PostgreSQL 18 en Docker en la misma máquina y
`-Xmx2g`. El caso no fija un objetivo numérico.

## Límites conocidos

Decisiones conscientes o hallazgos de las revisiones que no se resolvieron antes de la entrega.

**Seguridad**
- Sin autenticación (D14): el servicio corre local. En producción iría detrás del API Gateway con JWT
  (Task 1).
- Las contraseñas de desarrollo son valores por defecto en `application.yml` y en el compose, y se
  sobrescriben con variables de entorno (`ACCOUNTS_APP_PASSWORD`, `ACCOUNTS_OWNER_PASSWORD`,
  `POSTGRES_PASSWORD`).

**Memoria y tamaño de las cargas**
- La demo y el benchmark corren con `-Xmx2g`. Leer una carga en los límites de §6.2 (1,000 archivos,
  ~1M eventos) retiene ~1 GB, pero esa cifra mide solo el lector y es un piso. No incluye lo que la
  carga guarda en proporción a los eventos: la lista ordenada de D4, un resultado por evento y el
  reporte con `rejected` y `duplicates`. Una carga completa en los límites no se midió de punta a punta.
- Por encima de `server.tomcat.max-swallow-size` (2 MB por defecto), Tomcat puede cortar la conexión en
  lugar de responder 413 a una carga que excede el tamaño. El 413 por número de archivos o de eventos sí
  responde con cuerpo.

**Lock de carga (advisory lock de PostgreSQL)**
- La sesión del lock queda inactiva durante toda la carga. Si PostgreSQL o la red la cortan
  (`pg_terminate_backend`, `idle_session_timeout`, TCP), el lock se pierde sin aviso y otra carga podría
  empezar en paralelo. El dinero lo siguen protegiendo la clave primaria de `settlement_decision` y el
  `FOR UPDATE`, pero ya no se garantiza el orden entre cargas (D4: una a la vez, en orden de llegada).
- Si falla el `close()` de la conexión después de un `pg_try_advisory_lock` que devolvió `false`, la
  carga responde 500 en vez de 409, aunque el lock no estaba tomado. No mueve dinero ni duplica nada.
- Los WARN de `PostgresLoadLock` (no se pudo liberar o cerrar el lock) salen sin `load_id`, porque el
  `load_id` se genera después de tomar el lock. Se ubican por la hora y por la línea "Load finished" de
  la misma carga.

**Consultas**
- `/transactions` lee las líneas, la cuenta y el `total` en tres consultas sin un snapshot común: durante
  una carga, el `total` puede no cuadrar con las líneas de esa misma respuesta. Al terminar la carga
  cuadra.

## Uso de IA

El Task 2 se construyó con Claude Code siguiendo un método fijo: una tarea del backlog a la vez
(spec §12.5), plan aprobado por el humano, test del criterio primero, revisión con dos subagentes y un
registro por tarea. El repositorio incluye el harness con el que se trabajó (`CLAUDE.md`,
`.claude/rules`, `.claude/hooks`, `.claude/agents`, `.claude/skills`), y
[docs/harness-log.md](docs/harness-log.md) registra cómo evolucionó.

En [docs/ai-usage/](docs/ai-usage/README.md) están los casos de uso con ejemplos concretos, los prompts
(registrados automáticamente por un hook) y, por tarea, qué produjo el agente, qué encontraron los
revisores y qué corrigió o decidió el humano.
