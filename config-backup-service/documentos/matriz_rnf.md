# Matriz de trazabilidad de RNF — config-backup-service

Rastrea, RNF por RNF, cómo lo cumple este servicio y dónde está la evidencia (al RNF lo que
[`casos_de_prueba.md`](casos_de_prueba.md) es a lo funcional).

**Última actualización:** 2026-07-17 · **Estado:** pre-código (planificación) · **Fuente de RNF:**
[`proyecto_microservicios_redsegura.md §8`](../../../management/documentos/proyecto_microservicios_redsegura.md) ·
**Endurecimiento por etapas:** [`preparacion_produccion.md`](../../../management/documentos/arquitectura/preparacion_produccion.md)

**Leyenda:** ✅ cumplido · 🟡 planificado/este ciclo · 🔵 diferido (con disparador) · ⬜ N/A (justificado).

| RNF | Estado | Cómo se satisface (plan) | Evidencia (a producir) | Disparador / motivo |
|---|---|---|---|---|
| RNF-01 GET p95 < 300 ms | 🔵 | — | — | **PRE-REL** (prueba de carga) |
| **RNF-02 respaldo < 30 s** | 🔵 | Respaldo SSH acotado en tiempo | — | **PRE-REL** (con red simulada) — **aplica a este servicio** |
| RNF-03 auth OAuth2/JWT | 🟡 | FastAPI + validación JWT Keycloak | `SecurityDependency`, tests SEC | DEV |
| RNF-04 RBAC e2e server-side | 🟡 | Dependencia RBAC por endpoint | tests SEC-01..04 | DEV |
| RNF-05 TLS + segmentación | 🔵 | — | — | **DEPLOY** |
| RNF-06 secretos externalizados | 🟡 | **Credenciales SSH** por env/gestor; nunca en BD/logs | config, CYBER-01 | gestor en **DEPLOY** |
| **RNF-07 alcance de conexión** | 🟡 | **Control técnico de CIDRs autorizados** (solo red simulada); rechaza fuera de alcance | RN-CB1, RNF07-01 | **DEV — aplica a este servicio** |
| RNF-08 SCA + imagen (CI) | 🟡 | `pip-audit` + Trivy imagen, bloqueante en crítico | workflow | DEV |
| RNF-09 sin fuga de internos | 🟡 | Handler RFC 7807 en FastAPI | ERR-*, tests | DEV |
| **RNF-10 Resilience (timeout/retry)** | 🟡 | **Timeouts + reintentos con backoff en las llamadas SSH (Netmiko)** | RES-01/02 | **DEV — aplica: hay llamadas salientes reales** |
| RNF-11 degradación con gracia | 🟡 | Fallo de un dispositivo no tumba el lote | FLOW-02 | DEV |
| RNF-12 health probes | 🟡 | liveness/readiness (BD/broker/Git) | HLTH-01..03 | DEV |
| RNF-13 stateless + HPA | 🟢/🔵 | App stateless (estado en BD/Git/broker); graceful shutdown | — | HPA en **DEPLOY** |
| RNF-14 database-per-service | 🟢 | BD propia (Postgres) + **Alembic** (vista de dispositivos, processed_events); repo Git al implementar núcleo | `alembic/`, `RetentionCleanupIT`→N/A; `test_asset_event_handler` (Testcontainers) | DEV |
| RNF-15 métricas Prometheus + dominio | 🟡 | `prometheus-fastapi-instrumentator` + contadores (respaldos, drift) | ObservabilityIT | DEV; dashboards **DEPLOY** |
| RNF-16 trazas distribuidas | 🟡 | OpenTelemetry FastAPI; traceId en logs | — | export por entorno **DEPLOY** |
| RNF-17 logs sin PII/secretos | 🟡 | Logs JSON; **credenciales SSH redactadas** | CYBER-01 | DEV |
| RNF-18 cobertura ≥ 70 % | 🟡 | `pytest --cov` en el gate | CI | DEV |
| RNF-19 gatekeeper en CI | 🟡 | ruff + mypy + pytest (activar workflow) | CI | DEV |
| RNF-20 documentación pre-código | ✅ | propuesta/casos/memoria/matriz (este paquete) | `documentos/` | — |
| **RNF-21 Pact** | 🟢 | **Consumidor de `asset.*` verificado (INT-CONS)** contra el esquema compartido; par con `asset-inventory` cerrado. Falta el wiring del broker (pika) → núcleo | `test_asset_event_contract` (PACT-01) | **INT-CONS activado** |
| RNF-22 paridad Compose ↔ k8s | 🔵 | 12-factor | — | **DEPLOY** |
| RNF-23 IaC (Terraform) | 🔵 | — | — | **DEPLOY** |
| RNF-24 presupuesto AWS | ⬜ | — | — | Nivel infra |
| RNF-25/26 accesibilidad/UX | ⬜ | — | — | Repo `frontend` |
| RNF-27 OpenAPI + Swagger runtime | 🟡 | FastAPI sirve `/docs` + `/openapi.json`; alinear con contrato | — | DEV |
| RNF-28 SemVer + CHANGELOG | 🟡 | Tag `config-backup-service-vX`; CHANGELOG | `CHANGELOG.md` | DEV |
| RNF-29 token issuer/audience | 🟡 | Validación iss+aud+exp en el servicio | tests SEC | DEV |
| RNF-30 entrega de eventos (produce) | 🟡 | Transactional outbox + confirms + relay `SKIP LOCKED` (patrón asset-inventory) | EVT-OUT-*, tests | DEV |
| RNF-31 readiness (esta matriz) | 🟡 | Matriz + checklist mantenidas | este archivo | DEV |

## Diferencias notables vs `asset-inventory-service`
- **RNF-07 aplica** (control técnico de alcance de conexión SSH — no solo del scan-orchestrator).
- **RNF-10 aplica desde DEV** (Netmiko hace llamadas SSH salientes reales → timeouts/reintentos
  obligatorios ya, no diferibles a INT-SYNC).
- **RNF-21 (Pact) se activa ahora** (INT-CONS): es el primer consumidor de `asset.*`.
- **RNF-02** (respaldo < 30 s) aplica a este servicio (verificación en PRE-REL con red simulada).

> **Estado:** planificación pre-código. Los 🟡 se cierran durante la implementación; los 🔵 son
> obligatorios en su etapa (rastreados en `preparacion_produccion.md`).
