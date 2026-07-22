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
| Jobs asíncronos | Runner in-process + tabla de jobs (patrón bulk de asset-inventory) | RF-08 |
| Scheduling | Cron (APScheduler) | RF-08 |
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
| Persistencia + consumo asset.* + Pact | ✅ | SQLAlchemy 2.0 + Alembic (vista de dispositivos + processed_events); consumidor idempotente de `asset.*`; **Pact consumidor** verificado (INT-CONS). Tests con Testcontainers Postgres + Alembic real (14 tests, cobertura 98%). **Pendiente (núcleo):** wiring del broker (pika) que entrega los mensajes al handler. |
| Núcleo (backup/git/diff/drift/schedules/jobs) | 🟡 | **Respaldo individual** hecho: conector abstracto + doble (RNF-07 alcance + RNF-10 reintentos), versionado Git + diff, `unsavedChanges` (ADR-02), persistencia `Backup`, producción `config.*` vía outbox. 25 tests, cobertura 98%. endpoints HTTP + RBAC + conector Netmiko real ✅; **drift-check (RF-09) ✅**. **Pendiente:** schedules (cron), jobs por lotes, relay del outbox + consumidor pika. |
| Seguridad + observabilidad + endurecimiento | 🟡 | **JWT (iss/aud) + RBAC por endpoint** hechos (`security.py`); RFC 7807; API de respaldo (POST/GET/diff). Pendiente: observabilidad, endurecimiento (non-root/graceful/retención/métricas/SCA). |
| QA 4 fases + verificación en vivo + CI | ⏳ | |

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
