# Memoria técnica — config-backup-service

> Documento **vivo**: se actualiza por fase durante la implementación. Planificación:
> [`propuesta_modulo.md`](propuesta_modulo.md) · Casos: [`casos_de_prueba.md`](casos_de_prueba.md) ·
> RNF: [`matriz_rnf.md`](matriz_rnf.md) · Contrato: [`../openapi.yaml`](../openapi.yaml).

**Estado:** ✅ implementado y **certificado (R-C1 + R-C2)** · **Stack:** Python 3.12 / FastAPI · **Última actualización:** 2026-07-24

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
├── pyproject.toml · requirements.txt   # deps + config de ruff/mypy/pytest/coverage
├── Dockerfile · docker-entrypoint.sh   # imagen non-root + HEALTHCHECK + graceful shutdown
├── openapi.yaml                        # contrato (fuente de verdad)
├── alembic/                            # migraciones 0001..0004 (vista, backups/outbox, jobs/schedules, idempotency)
├── app/
│   ├── main.py · config.py · errors.py · schemas.py   # app, Settings CBS_, RFC 7807, DTOs
│   ├── security.py · deps.py · idempotency.py · observability.py  # JWT+RBAC, DI, RN-CB7, 3 pilares
│   ├── git_store.py                    # repo Git interno de configuraciones (RF-07)
│   ├── api/        health · backups · drift · schedules
│   ├── db/         base · models · session (SQLAlchemy 2.0 + Alembic)
│   ├── connectors/ base · netmiko_connector · scope (RNF-07) · resilience (RNF-10)
│   ├── messaging/  asset_events · consumer · outbox · relay · runner · topology
│   └── services/   backup · drift · jobs · schedules · retention
└── tests/                              # pytest + Testcontainers; incl. test_contract_conformance
                                        # (L-QA-08) y test_acceptance (BDD, features/respaldo.feature)
```

## 4. Contratos consumidos/producidos (verificados)
- **Consume:** `asset.*` (cola `q.config-backup.asset-events`) — ver `propuesta_modulo.md §4.1`.
  Conformidad verificada contra `contracts/events/asset-event.schema.json` (Pact/INT-CONS,
  `test_asset_event_contract`).
- **Produce:** `config.backup_completed/failed`, `config.drift_detected`, `config.unsaved_changes_detected`
  con el **sobre común (§3.3)** —`eventId`/`eventType`/`version`/`occurredAt`/`source`/`payload`—
  construido al publicar (`outbox.to_envelope`) y **payloads conformes al catálogo §4.2–4.5**.
  **JSON Schema formal:** `contracts/events/config-event.schema.json`; **conformidad productor-side**
  verificada (`test_config_event_contract`, happy/sad) + broker real (`test_broker`) + en vivo
  (envelope real observado en el broker). Cierra **HALLAZGO-EVT-CBS-01** (antes se publicaba el
  payload desnudo, sin sobre, con campos que no coincidían con el catálogo).

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
| Certificación QA 4 fases (R-C2, 2ª vuelta) | ✅ | Re-certificación (2026-07-24, commit `ceb48a8`) tras los 3 gates de prevención + aceptación BDD. Ronda íntegra sobre código congelado, todo partiendo de "no validado": **0 hallazgos** (99 tests, 95 %, conformidad 13/13, en vivo 20/20 + 13/13). No sobrescribe R-C1. Reporte `reporte_certificacion_qa.md`. |
| Certificación QA 4 fases (R-C1, 1ª vuelta) | ✅ | Ronda formal (2026-07-24) sobre código congelado. **Fase 1** destapó 2 divergencias contrato↔implementación: `HALLAZGO-QA-CBS-01` (`GET /backups` ignoraba filtros del contrato: hostname/mgmtIp/status/from/to/sort) y `HALLAZGO-QA-CBS-02` (`Idempotency-Key` declarada, no honrada). **Fase 2** los corrigió: filtros completos (`test_backups_filters`) + módulo de **idempotencia de escritura** (`app/idempotency.py` + tabla `idempotency_keys`/migración 0004 + cabecera en los 3 POST; reserva por actor, replay, 409, liberación en fallo — `test_idempotency`) + tests de diff (`test_diff_api`). **Fase 3** re-ejecución limpia (93 tests, 95 %) + en vivo 20/20 + 13/13 de los fixes. **Fase 4** ✅ CERTIFICADO. Reporte `documentos/reporte_certificacion_qa.md`. Contrato: +respuesta 409 en los 3 POST. |

## 6. Deuda / diferidos
Rastreados en [`matriz_rnf.md`](matriz_rnf.md) y `preparacion_produccion.md` (DEPLOY/PRE-REL).

> **Fidelidad de integración con dispositivos (deuda PRE-REL, `preparacion_produccion.md §2.9`):** los
> tests mockean el dispositivo (estándar correcto, herméticos). Antes de producción se cierra el hueco
> de fidelidad con: (1) **fixtures de output real grabado** por plataforma, (2) **matriz de
> compatibilidad** (vendor×modelo×OS) validada en emulador con **imágenes reales** (GNS3/Containerlab/CML,
> **no** Packet Tracer, que es simulador de baja fidelidad para automatización), y (3) **smoke
> pre-producción** contra dispositivos reales/representativos. No bloquean DEV.

> **Seguridad de config-as-code (deuda DEPLOY, `preparacion_produccion.md §2.10`):** las running/startup
> config contienen secretos. Por estándar de gestión de redes (RANCID/Oxidized) se guardan **completas**
> (se necesitan para restore/audit; redactarlas rompería drift y restore); el control es **cifrado
> at-rest + acceso restringido** del repo Git (DEPLOY) + no exponerlas fuera de rol (RBAC en `diff` ✅,
> logs redactados ✅). No bloquea DEV.

> **Tests adicionales según estándares (análisis 2026-07-24):** **implementados en DEV** — aceptación
> **BDD** (`tests/test_acceptance.py` + `features/respaldo.feature`, pytest-bdd; patrón E) y
> **conformidad de contrato↔impl** (`test_contract_conformance.py`, L-QA-08). **Diferidos con
> disparador:** prueba de carga de RNF-02 (respaldo < 30 s) y perf (PRE-REL); **fuzzing de API con
> Schemathesis** y **mutation testing** (PRE-REL, complementarios — la cobertura de rama ya es 95 %).

## 7. Cumplimiento ("done") — ✅ CERTIFICADO (R-C1 + R-C2)
Definición de done D1..D7 del `CLAUDE.md`:
- **D1 — casos en ✅ PASS (0 ⏳):** ✅ 63 ✅ / 0 ⏳ (gate anti-⏳ verde).
- **D2 — gatekeeper + cobertura ≥ 70 %:** ✅ 99 tests, cobertura 95 %, ruff+mypy verdes, CI verde.
- **D3 — contrato con Pact:** ✅ consume `asset.*` (INT-CONS); produce `config.*` (mini-Pact diferido INT-SYNC, ver §4).
- **D4 — documentación del módulo:** ✅ propuesta + casos + memoria + matriz + reportes al día.
- **D5 — verificación EN VIVO:** ✅ R-C1 20/20 + R-C2 20/20 + 13/13 (idempotencia/filtros).
- **D6 — RNF sin 🟡 DEV:** ✅ (los 🔵 son diferidos con disparador registrado).
- **D7 — conformidad de contrato:** ✅ `test_contract_conformance` (13/13 operaciones, 0 params sin honrar).
