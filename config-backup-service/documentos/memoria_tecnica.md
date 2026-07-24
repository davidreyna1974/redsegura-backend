# Memoria técnica — config-backup-service

> Documento **vivo**: se actualiza por fase durante la implementación. Planificación:
> [`propuesta_modulo.md`](propuesta_modulo.md) · Casos: [`casos_de_prueba.md`](casos_de_prueba.md) ·
> RNF: [`matriz_rnf.md`](matriz_rnf.md) · Contrato: [`../openapi.yaml`](../openapi.yaml).

**Estado:** pre-código (planificación) · **Stack:** Python 3.12 / FastAPI · **Última actualización:** 2026-07-17

---

## 1. Contexto y responsabilidad
Respaldo y versionado de configuraciones de red (RF-06..10): SSH (Netmiko/NAPALM) → running/startup,
versionado en Git interno, metadatos en PostgreSQL, detección de drift, scheduling. Primer consumidor
de `asset.*` (habilita Pact). Detalle en `propuesta_modulo.md`.

## 2. Decisiones de arquitectura (se confirman al implementar)
| Área | Decisión (propuesta) | ADR / nota |
|---|---|---|
| Framework | FastAPI + Pydantic v2 | RNF-27 (Swagger nativo) |
| Persistencia | SQLAlchemy 2.0 + Alembic (migraciones) | ADR-01, RNF-14 |
| SSH a dispositivos | Netmiko tras interfaz `DeviceConnector` (mockeable); NAPALM opcional | RF-06, RNF-07/10 |
| Versionado de config | Repo Git interno (GitPython); diff por commits | RF-07 |
| Eventos entrantes | Consumidor idempotente de `asset.*` (aio-pika/pika) → vista local | RNF-E1, RNF-21 |
| Eventos salientes | Transactional outbox + confirms + relay `SKIP LOCKED` | ADR-04, RNF-30 |
| Seguridad | JWT (PyJWT + JWKS) iss/aud; RBAC por endpoint; secretos SSH externalizados | RNF-06/29 |
| Jobs asíncronos | `BackgroundTasks` + tablas `jobs`/`job_results` (dispatcher inyectable); avance incremental | RF-08 |
| Scheduling | Cron (`croniter`) + bucle de fondo `SKIP LOCKED`; tabla `schedules` con `next_run_at` | RF-08 |
| Errores | Handler RFC 7807 (`application/problem+json`) | ADR-08 |

## 3. Estructura del proyecto
```
config-backup-service/
├── pyproject.toml          # deps + config de ruff/mypy/pytest/coverage
├── requirements.txt        # deps + herramientas del gate (lo instala el CI)
├── app/
│   ├── main.py             # create_app() + instancia (uvicorn: app.main:app)
│   ├── config.py           # Settings 12-factor (pydantic-settings, prefijo CBS_)
│   ├── errors.py           # handlers RFC 7807 (application/problem+json)
│   ├── schemas.py          # modelos Pydantic del contrato (health/enums; resto por unidad)
│   └── api/health.py       # probes liveness/readiness (checks inyectables)
└── tests/                  # pytest + TestClient (health, errores, config)
```
_(se ampliará: `db/` (SQLAlchemy+Alembic), `connectors/` (SSH), `messaging/` (outbox+consumer),
`services/` al implementar el núcleo.)_

## 4. Contratos consumidos/producidos (verificados)
- **Consume:** `asset.*` (cola `q.config-backup.asset-events`) — ver `propuesta_modulo.md §4.1`.
- **Produce:** `config.backup_completed/failed`, `config.drift_detected`, `config.unsaved_changes_detected`
  (outbox) — ver `§4.2`. Esquemas compartidos en `contracts/events/` (a crear para `config.*`).

## 5. Hitos de implementación
_(bitácora por fase — se llena al avanzar.)_
| Hito | Estado | Nota |
|---|---|---|
| Scaffold + gatekeeper | ✅ | FastAPI + health + errores RFC 7807 + config; ruff/mypy(strict)/pytest verdes, cobertura 100%; CI activo (path-triggered) |
| Persistencia + consumo asset.* + Pact | ✅ | SQLAlchemy 2.0 + Alembic (vista de dispositivos + processed_events); consumidor idempotente de `asset.*`; **Pact consumidor** verificado (INT-CONS). Tests con Testcontainers Postgres + Alembic real. Broker real (pika + RabbitMQ Testcontainers): relay publica `config.*`, consumidor procesa `asset.*` — **RNF-30/RNF-21 cerrados**. |
| Núcleo (backup/git/diff/drift/schedules/jobs) | ✅ | **Respaldo individual** (conector abstracto + doble, RNF-07 alcance + RNF-10 reintentos, Git + diff, `unsavedChanges` ADR-02, `config.*` vía outbox) + endpoints HTTP + RBAC + Netmiko real; **drift-check (RF-09)**; **jobs por lotes** (`POST /backups`, `/drift-checks` async → `jobId`; dispatcher inyectable; `jobs`/`job_results`; resolución de objetivo por scope/deviceIds/filter, RN-CB8); **schedules cron (RF-08)** (`croniter` + bucle `SKIP LOCKED`; `next_run_at`; disparo `scheduler:<id>`). 64 tests, cobertura 95%. |
| Seguridad + observabilidad + endurecimiento | ✅ | **JWT (iss/aud) + RBAC por endpoint** (`security.py`); RFC 7807. **Observabilidad 3 pilares** (`observability.py`): métricas Prometheus `/metrics` (HTTP + dominio: respaldos/drift/eventos/jobs), trazas OTel (traceId en logs), **logs JSON con redacción de secretos SSH** (RNF-15/16/17). **Endurecimiento**: Dockerfile **non-root** + HEALTHCHECK, **graceful shutdown** uvicorn, **retención** de datos operativos (`services/retention.py`), **SCA pip-audit + Trivy imagen (bloqueante crítico)** en CI. 71 tests, cobertura 95%; imagen construida y verificada en vivo (liveness 200, `/metrics`, usuario `appuser`). |
| Verificación EN VIVO de endpoints | ✅ | **14/14 operaciones OpenAPI** sobre docker-compose.dev.yml con **JWT reales de Keycloak** por rol (curl/Postman): salud, RBAC (401/403), validación (400/422), CRUD schedules, jobs por lotes async, drift, aislamiento de tipo (404), golden path event-driven `asset.created`→vista, RF-10 (SSH FAILED con gracia), RNF-09/13/15/17 verificados. Reporte `documentos/verificacion_endpoints.md` + colección Postman + guía. Esa pasada detectó **HALLAZGO-LIVE-CBS-01** (relay/consumidor morían ante caída de conexión al broker; sin reconexión → outbox no se drenaba). **Corregido** con `run_resilient` (reconexión + backoff exponencial en `runner.py`, 5 tests de regresión) y **re-verificado en vivo** (reinicio de RabbitMQ → el hilo sobrevive y publica; outbox drenado). 76 tests, cobertura 95%. |
| Revalidación integral R2 (2ª iteración) | ✅ | Cachés purgadas → gate limpio (**76 tests, 94.97 %**, ruff+mypy verdes) + **20/20 en vivo** + regresión de resiliencia (reinicio de RabbitMQ, relay sobrevive). Sin hallazgos nuevos. Reporte `documentos/reporte_r2_revalidacion.md`. |
| Certificación QA 4 fases + entorno dev | 🟡 | **Entorno dev** (`docker-compose.dev.yml` extendido: 2.ª BD `config_backup`, servicio Python en 8082, Keycloak sembrado) listo. Pendiente: ronda formal de 4 fases sobre código congelado + `chore(qa)`. |

## 6. Deuda / diferidos
Rastreados en [`matriz_rnf.md`](matriz_rnf.md) y `preparacion_produccion.md` (DEPLOY/PRE-REL).

> **Fidelidad de integración con dispositivos (deuda PRE-REL, `preparacion_produccion.md §2.9`):** los
> tests mockean el dispositivo (estándar correcto, herméticos). Antes de producción se cierra el hueco
> de fidelidad con: (1) **fixtures de output real grabado** por plataforma, (2) **matriz de
> compatibilidad** (vendor×modelo×OS) validada en emulador con **imágenes reales** (GNS3/Containerlab/CML,
> **no** Packet Tracer, que es simulador de baja fidelidad para automatización), y (3) **smoke
> pre-producción** contra dispositivos reales/representativos. No bloquean DEV.

## 7. Cumplimiento ("done")
_(se marca al certificar; ver la Definición de done D1..D6 del `CLAUDE.md`.)_
