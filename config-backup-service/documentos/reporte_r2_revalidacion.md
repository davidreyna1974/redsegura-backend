# Reporte R2 — Revalidación integral de config-backup-service (2ª iteración)

> **Segunda vuelta de pruebas.** Todos los elementos del microservicio
> (objetos/módulos/funciones/endpoints/RF/RNF) se reinician a **"no verificado"** y se **revalidan
> completamente** desde cero. No sustituye ni sobrescribe la 1ª iteración (R1, documentada en
> [`verificacion_endpoints.md`](verificacion_endpoints.md) y las columnas ✅ de
> [`casos_de_prueba.md`](casos_de_prueba.md)); es una pasada de confirmación **previa** al QA formal
> de 4 fases.

- **Fecha:** 2026-07-24
- **Versión de código:** rama `develop` (post-fix `HALLAZGO-LIVE-CBS-01`)
- **Alcance:** batería automatizada (gate) **+** verificación en vivo **+** regresión de resiliencia
- **Método:** cachés de test purgadas (`.pytest_cache`/`.mypy_cache`/`.ruff_cache`/`.coverage`) →
  ejecución limpia; entorno en vivo reconstruido (`docker compose up --build`).

## 1. Resultado global

| Batería | Resultado R2 |
|---|---|
| Lint (ruff) | ✅ All checks passed |
| Tipos (mypy strict) | ✅ Success — 58 archivos, 0 errores |
| Tests (pytest + Testcontainers Postgres/RabbitMQ) | ✅ **76 passed** |
| Cobertura | ✅ **94.97 %** (umbral 70 %) |
| Verificación en vivo (endpoints) | ✅ **20/20 comprobaciones** |
| Regresión de resiliencia en vivo | ✅ relay sobrevive a reinicio de RabbitMQ |

## 2. Batería automatizada — 76 tests por módulo (todos ✅)

| Archivo de test | Tests | Elementos validados |
|---|---|---|
| `test_health.py` | 3 | probes liveness/readiness (checks inyectables) |
| `test_config.py` | 2 | `Settings` 12-factor (prefijo `CBS_`, overrides) |
| `test_errors.py` | 2 | handlers RFC 7807 → `problem+json` |
| `test_security.py` | 2 | JWT (JWKS+iss+aud) + `require_roles` (RBAC) |
| `test_asset_event_handler.py` | 4 | consumidor idempotente `asset.*` (vista + dedupe) |
| `test_asset_event_contract.py` | 3 | conformidad de contrato (Pact consumidor, INT-CONS) |
| `test_git_store.py` | 2 | `GitStore` save/diff/read (versionado RF-07) |
| `test_scope_resilience.py` | 4 | `assert_in_scope` (RNF-07) + `with_retries` (RNF-10) |
| `test_netmiko_connector.py` | 2 | `NetmikoConnector` (envuelve errores SSH) |
| `test_backup_service.py` | 5 | `backup_device` (SUCCESS/FAILED/unsaved/scope/outbox) |
| `test_backups_api.py` | 7 | endpoints backup + RBAC (201/401/403/404/422) |
| `test_drift.py` | 5 | `drift_check_device` + endpoint (RF-09) |
| `test_jobs.py` | 12 | `resolve_targets`/`run_job` + lotes API (RF-08) |
| `test_schedules.py` | 9 | cron/`create_schedule`/`tick_schedules` + API (RF-08) |
| `test_broker.py` | 2 | relay publica `config.*` / consumidor procesa `asset.*` (RabbitMQ real) |
| `test_observability.py` | 5 | `/metrics`, métricas de dominio, logs JSON + redacción (RNF-15/17) |
| `test_retention.py` | 2 | `purge_old` (conserva respaldos, purga operativo) |
| `test_runner.py` | 5 | `run_resilient` (reconexión + backoff, RNF-10/30) |
| **Total** | **76** | |

## 3. Verificación en vivo — 20/20 (docker-compose.dev.yml, JWT reales de Keycloak)

| # | Operación | Caso | Rol | Esperado | R2 |
|---|---|---|---|---|---|
| 01 | GET /health/liveness | probe | — | 200 | ✅ |
| 02 | GET /health/readiness | BD/broker/Git | — | 200 | ✅ |
| 03 | GET /metrics | Prometheus | — | 200 | ✅ |
| 04 | POST /schedules | sin token | — | 401 | ✅ |
| 05 | POST /schedules | rol lectura | AUD | 403 | ✅ |
| 06 | POST /schedules | operador (solo ADM) | OPE | 403 | ✅ |
| 07 | POST /schedules | cron inválido | ADM | 400 | ✅ |
| 08 | POST /backups | target vacío (RN-CB8) | ADM | 422 | ✅ |
| 09 | POST /schedules | cron válido | ADM | 201 | ✅ |
| 10 | GET /schedules | filtros | AUD | 200 | ✅ |
| 11 | POST /backups | scope=all | ADM | 202+jobId | ✅ |
| 12 | GET /backups/jobs/{id} | seguimiento | AUD | 200 COMPLETED | ✅ |
| 13 | POST /drift-checks | scope=all | OPE | 202+jobId | ✅ |
| 14 | GET /backups | historial | AUD | 200 | ✅ |
| 15 | GET /backups/{id} | detalle | AUD | 200 | ✅ |
| 16 | POST /devices/{rand}/backups | no en vista | ADM | 404 | ✅ |
| 17 | GET /backups/{rand} | inexistente | AUD | 404 | ✅ |
| 18 | GET /drift-checks/jobs/{backupJobId} | aislamiento de tipo | AUD | 404 | ✅ |
| 19 | POST /devices/{id}/drift-check | sin baseline | ADM | 200 drift:false | ✅ |
| 20 | GET /devices/{id}/backups/diff | refs inválidas | AUD | 400 | ✅ |

**Evidencias transversales (re-capturadas en R2):**
- **RNF-09:** 401 en `application/problem+json` (`type/title/status/detail/instance`), sin stack traces.
- **RNF-13:** `whoami` en el contenedor = `appuser` (no-root).
- **RNF-15:** `/metrics` expone `config_backups_total`, `config_drift_checks_total`,
  `config_jobs_total`, `config_events_published_total` con valores coherentes.
- **RNF-17:** la contraseña SSH (`changeme`) aparece **0 veces** en los logs.
- **Golden path event-driven:** `asset.created`→RabbitMQ→consumidor→vista→`scope=all` resolvió el dispositivo.
- **RF-10:** respaldo a dispositivo sin SSH real → `FAILED` con `detail` acotado; el lote igual `COMPLETED`.

## 4. Regresión de resiliencia en vivo (HALLAZGO-LIVE-CBS-01)

Se reinició **RabbitMQ** (el reset que en R1 mataba el hilo del relay). Resultado R2:
- Log del bucle resiliente: `hilo de fondo: fallo recuperable (Stream connection lost:
  ConnectionResetError(104…)); reintentando en 1.0s` → **reconectó**.
- `Exception in thread` tras el reinicio: **0**.
- Outbox pendientes: **0** (drenó); `config_events_published_total` **incrementó** tras el restart.

Confirma que el fix (`run_resilient`) se mantiene: los hilos de fondo sobreviven a caídas de conexión.

## 5. Trazabilidad RF/RNF (revalidada en R2)

- **RF-06/07/10** (respaldo/versionado/estado): `test_backup_service`, `test_git_store`, live 11/12/14/15.
- **RF-08** (lotes + schedules): `test_jobs` (12), `test_schedules` (9), live 08/09/10/11/12/13.
- **RF-09** (drift): `test_drift` (5), live 19.
- **RNF-04/06/29** (RBAC/secretos/JWT): `test_security`, live 04/05/06 + redacción.
- **RNF-07/10** (alcance/resiliencia): `test_scope_resilience`, `test_runner`, regresión §4.
- **RNF-09** (sin fuga): `test_errors`, live problem+json.
- **RNF-13/15/16/17** (endurecimiento/observabilidad): `test_observability`, live §3 evidencias.
- **RNF-14** (retención/BD propia): `test_retention`, Alembic real en tests.
- **RNF-21/30** (Pact/entrega de eventos): `test_asset_event_contract`, `test_broker`, `test_runner`, live golden path.
- **RNF-18/19** (cobertura/gate): §1 (94.97 %, ruff+mypy+pytest verdes).

Detalle completo y estados por RNF en [`matriz_rnf.md`](matriz_rnf.md) (sin ningún 🟡 de etapa DEV).

## 6. Conclusión R2

**Sin hallazgos nuevos.** Los 76 tests automatizados, las 20 comprobaciones en vivo y la regresión de
resiliencia pasan sobre código congelado de `develop`. El servicio queda **revalidado íntegramente**
en 2ª iteración y listo para la ronda formal de QA de 4 fases (certificación).
