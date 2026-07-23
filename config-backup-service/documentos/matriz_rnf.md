# Matriz de trazabilidad de RNF — config-backup-service

Rastrea, RNF por RNF, cómo lo cumple este servicio y dónde está la evidencia (al RNF lo que
[`casos_de_prueba.md`](casos_de_prueba.md) es a lo funcional).

**Última actualización:** 2026-07-21 · **Estado:** implementación en curso · **Fuente de RNF:**
[`proyecto_microservicios_redsegura.md §8`](../../../management/documentos/proyecto_microservicios_redsegura.md) ·
**Endurecimiento por etapas:** [`preparacion_produccion.md`](../../../management/documentos/arquitectura/preparacion_produccion.md)

**Leyenda:** ✅ cumplido · 🟡 planificado/este ciclo · 🔵 diferido (con disparador) · ⬜ N/A (justificado).

| RNF | Estado | Cómo se satisface (plan) | Evidencia (a producir) | Disparador / motivo |
|---|---|---|---|---|
| RNF-01 GET p95 < 300 ms | 🔵 | — | — | **PRE-REL** (prueba de carga) |
| **RNF-02 respaldo < 30 s** | 🔵 | Respaldo SSH acotado en tiempo | — | **PRE-REL** (con red simulada) — **aplica a este servicio** |
| RNF-03 auth OAuth2/JWT | 🟢 | `get_principal`: valida JWT de Keycloak (firma JWKS + iss + aud + exp) | `security.py`, `test_security`, `test_backups_api` (401) | DEV |
| RNF-04 RBAC e2e server-side | 🟢 | `require_roles` por endpoint (escritura ADM/OPE; lectura +AUD) | `test_backups_api` (SEC-01/02: 401/403) | falta cubrir todos los endpoints (drift/schedules) |
| RNF-05 TLS + segmentación | 🔵 | — | — | **DEPLOY** |
| RNF-06 secretos externalizados | 🟢 | **Credenciales SSH** por env (`CBS_SSH_*`), nunca en código/BD | `config.py` | gestor de secretos en **DEPLOY** |
| **RNF-07 alcance de conexión** | 🟢 | **Control técnico de CIDRs autorizados**: `assert_in_scope` rechaza fuera de alcance **antes** de conectar | `connectors/scope.py`, `test_scope_resilience`, `test_backup_service`, `test_backups_api` (RNF07-01: POST fuera de alcance → 422) | — |
| RNF-08 SCA + imagen (CI) | 🟢 | `pip-audit` (informativo) + **Trivy imagen bloqueante en CRÍTICO** sobre la imagen real construida en CI | `.github/workflows/ci-config-backup-service.yml`, `Dockerfile` | — |
| RNF-09 sin fuga de internos | 🟢 | Handlers RFC 7807 (`errors.py`) → `application/problem+json` | `test_errors`, `test_backups_api` | DEV |
| **RNF-10 Resilience (timeout/retry)** | 🟢 | **Reintentos con backoff** en SSH (`with_retries`); **reconexión con backoff de los hilos de fondo** (`run_resilient`: consumidor/relay/scheduler/retención sobreviven a caídas del broker/BD — HALLAZGO-LIVE-CBS-01) | `connectors/resilience.py` (RES-02), `messaging/runner.py`, `test_runner` (RUN-01..05) | timeout Netmiko al añadir el conector real |
| RNF-11 degradación con gracia | 🟢 | Fallo de un dispositivo no tumba el lote (job sigue); **graceful shutdown** de uvicorn | FLOW-02 (`test_jobs`) | DEV |
| RNF-12 health probes | 🟢 | liveness/readiness (BD/broker/Git); **HEALTHCHECK** en la imagen | HLTH-01..03 (`test_health`) | DEV |
| RNF-13 stateless + HPA | 🟢/🔵 | App stateless (estado en BD/Git/broker); **graceful shutdown** (`--timeout-graceful-shutdown`); **usuario no-root** en la imagen | `Dockerfile`, `docker-entrypoint.sh` | HPA en **DEPLOY** |
| RNF-14 database-per-service | 🟢 | BD propia (Postgres) + **Alembic**; **retención** de datos operativos (purga jobs/eventos antiguos, conserva respaldos) | `alembic/`, `services/retention.py`, `test_retention` | DEV |
| RNF-15 métricas Prometheus + dominio | 🟢 | Endpoint `/metrics`; HTTP (peticiones+latencia) + **dominio** (respaldos, drift, eventos publicados, jobs) | `observability.py`, `test_observability` (OBS-01/02) | dashboards **DEPLOY** |
| RNF-16 trazas distribuidas | 🟢 | Instrumentación OpenTelemetry FastAPI; `traceId`/`spanId` en logs JSON | `observability.py` | export OTLP por entorno **DEPLOY** |
| RNF-17 logs sin PII/secretos | 🟢 | Logs **JSON**; **contraseña SSH redactada** por filtro (aunque se filtre por error) | `observability.py`, `test_observability` (OBS-03/CYBER-01) | DEV |
| RNF-18 cobertura ≥ 70 % | 🟢 | `pytest --cov --cov-fail-under=70` en el gate (cobertura real ~95 %) | CI | DEV |
| RNF-19 gatekeeper en CI | 🟢 | ruff + mypy(strict) + pytest + pip-audit + Trivy, **activo y verde** | `.github/workflows/ci-config-backup-service.yml` | DEV |
| RNF-20 documentación pre-código | ✅ | propuesta/casos/memoria/matriz (este paquete) | `documentos/` | — |
| **RNF-21 Pact** | 🟢 | **Consumidor de `asset.*` verificado (INT-CONS)** contra el esquema compartido + **consumo real** desde RabbitMQ | `test_asset_event_contract` (PACT-01), `test_broker` | **INT-CONS cerrado** |
| RNF-22 paridad Compose ↔ k8s | 🔵 | 12-factor | — | **DEPLOY** |
| RNF-23 IaC (Terraform) | 🔵 | — | — | **DEPLOY** |
| RNF-24 presupuesto AWS | ⬜ | — | — | Nivel infra |
| RNF-25/26 accesibilidad/UX | ⬜ | — | — | Repo `frontend` |
| RNF-27 OpenAPI + Swagger runtime | 🟢 | FastAPI sirve `/docs` + `/openapi.json` en runtime | (nativo FastAPI) | verificar divergencia contra `openapi.yaml` |
| RNF-28 SemVer + CHANGELOG | 🟡 | Tag `config-backup-service-vX`; CHANGELOG | `CHANGELOG.md` | DEV |
| RNF-29 token issuer/audience | 🟢 | `jwt.decode` con `issuer`+`audience`+exp (PyJWKClient) | `security.py`, `test_security` | DEV |
| RNF-30 entrega de eventos (produce) | 🟢 | Outbox + **relay** con `SKIP LOCKED` + publisher confirms + DLQ; **relay auto-recuperable** ante caída del broker (reconecta y drena el outbox; verificado en vivo reiniciando RabbitMQ) | `messaging/relay.py`, `messaging/runner.py`, `test_broker`, `test_runner` | DEV |
| RNF-31 readiness (esta matriz) | 🟡 | Matriz + checklist mantenidas | este archivo | DEV |

## Diferencias notables vs `asset-inventory-service`
- **RNF-07 aplica** (control técnico de alcance de conexión SSH — no solo del scan-orchestrator).
- **RNF-10 aplica desde DEV** (Netmiko hace llamadas SSH salientes reales → timeouts/reintentos
  obligatorios ya, no diferibles a INT-SYNC).
- **RNF-21 (Pact) se activa ahora** (INT-CONS): es el primer consumidor de `asset.*`.
- **RNF-02** (respaldo < 30 s) aplica a este servicio (verificación en PRE-REL con red simulada).

> **Estado:** planificación pre-código. Los 🟡 se cierran durante la implementación; los 🔵 son
> obligatorios en su etapa (rastreados en `preparacion_produccion.md`).
