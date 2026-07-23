# Verificación en vivo de endpoints — config-backup-service

> Verificación **en vivo** sobre `docker-compose.dev.yml` (Postgres + RabbitMQ + Keycloak + el
> servicio en su imagen Docker real, usuario no-root), con **JWT reales de Keycloak** por rol
> (`admin`/`operador`/`auditor`, cliente `redsegura-postman`). Complementa —no sustituye— los tests
> automatizados: cubre despliegue, configuración, semántica HTTP, RBAC extremo a extremo y
> comportamiento del *plumbing* de mensajería ante fallos reales. Ver
> `../../management/documentos/qa/estrategia_de_pruebas.md §1b`.

- **Fecha:** 2026-07-22
- **Imagen:** `config-backup-service` (Dockerfile multi-stage, `appuser` no-root, HEALTHCHECK) · puerto `8082→8000`
- **Migraciones:** Alembic `head` aplicado al arrancar (`CBS_RUN_MIGRATIONS=1`)
- **Mensajería:** `CBS_MESSAGING_ENABLED=true` (consumidor `asset.*` + relay `config.*` + scheduler + retención activos)
- **Tokens:** obtenidos por `password grant` contra Keycloak; roles en `realm_access.roles`

## Resultado: 14/14 operaciones OpenAPI ✅ (+ hallazgo corregido y re-verificado)

| # | Operación (OpenAPI) | Método/Ruta | Caso | Rol | Esperado | Obtenido |
|---|---|---|---|---|---|---|
| 1 | getLiveness | GET /health/liveness | Probe | — | 200 | ✅ 200 |
| 2 | getReadiness | GET /health/readiness | BD/broker/Git OK | — | 200 | ✅ 200 |
| 3 | (observabilidad) | GET /metrics | Prometheus abierto | — | 200 | ✅ 200 |
| 4 | createSchedule | POST /schedules | Sin token | — | 401 | ✅ 401 `problem+json` |
| 5 | createSchedule | POST /schedules | Rol de lectura | AUD | 403 | ✅ 403 |
| 6 | createSchedule | POST /schedules | Operador (solo ADM crea) | OPE | 403 | ✅ 403 |
| 7 | createSchedule | POST /schedules | Cron inválido | ADM | 400 | ✅ 400 |
| 8 | backupBatch | POST /backups | `target` vacío `{}` (RN-CB8) | ADM | 422 | ✅ 422 |
| 9 | createSchedule | POST /schedules | Cron válido | ADM | 201 | ✅ 201 `Schedule` |
| 10 | listSchedules | GET /schedules?type=&estado= | Listar con filtro | AUD | 200 | ✅ 200 página |
| 11 | backupBatch | POST /backups | `scope=all` | ADM | 202 + `jobId` | ✅ 202 QUEUED |
| 12 | getBackupJob | GET /backups/jobs/{id} | Seguimiento a terminal | AUD | 200 COMPLETED | ✅ 200 `COMPLETED total=1 failed=1` |
| 13 | driftCheckBatch | POST /drift-checks | `scope=all` | OPE | 202 + `jobId` | ✅ 202 |
| 14 | listBackups | GET /backups?deviceId= | Historial | AUD | 200 | ✅ 200 |
| 15 | getBackup | GET /backups/{id} | Detalle | AUD | 200 | ✅ 200 |
| 16 | backupDevice | POST /devices/{id}/backups | Dispositivo no en la vista | ADM | 404 | ✅ 404 |
| 17 | getBackup | GET /backups/{rand} | Id inexistente | AUD | 404 | ✅ 404 |
| 18 | getDriftJob | GET /drift-checks/jobs/{backupJobId} | **Aislamiento de tipo** | AUD | 404 | ✅ 404 |
| 19 | driftCheckDevice | POST /devices/{id}/drift-check | Sin línea base | ADM | 200 `drift:false` | ✅ 200 `drift=false` |
| 20 | diffBackups | GET /devices/{id}/backups/diff | Refs inválidas | AUD | 400 | ✅ 400 |

## Evidencias transversales

- **Golden path event-driven (cross-service):** se publicó un `asset.created` a RabbitMQ →
  el **consumidor real** lo procesó → el dispositivo apareció en la vista local → `POST /backups
  scope=all` lo resolvió (`total=1`). Demuestra el contrato de eventos extremo a extremo sin mocks.
- **RF-10 (respaldo resiliente) en vivo:** el respaldo de un dispositivo con IP en alcance pero sin
  SSH real terminó `FAILED` con `detail="fallo SSH con 10.0.0.21: TCP connection to device failed"`
  y el job igualmente `COMPLETED` (un fallo por dispositivo no tumba el lote). El camino SSH
  **exitoso** se cubre con los tests automatizados (dobles), no habiendo dispositivos reales en dev.
- **RNF-09 (sin fuga de internos):** los errores salen en `application/problem+json` (RFC 7807) con
  `type/title/status/detail/instance`, sin stack traces.
- **RNF-17 (logs sin secretos):** la contraseña SSH (`changeme`) aparece **0 veces** en los logs
  (redacción del formateador JSON verificada en el contenedor real).
- **RNF-15 (métricas de dominio):** `/metrics` expone `config_backups_total`,
  `config_drift_checks_total`, `config_jobs_total`, `config_events_published_total` con valores
  coherentes con las operaciones ejecutadas.
- **RNF-13 (no-root):** `whoami` dentro del contenedor = `appuser`; liveness 200 y HEALTHCHECK verde.

## HALLAZGO-LIVE-CBS-01 — relay sin reconexión (corregido) 🔴→✅

**Detección (en vivo):** tras generar respaldos fallidos, los eventos `config.backup_failed`
quedaron **pendientes en el outbox, sin publicar**. Causa raíz en los logs:
```
Exception in thread Thread-2 (relay_forever):
pika.exceptions.StreamLostError: Stream connection lost: ConnectionResetError(104, 'Connection reset by peer')
```
El hilo del relay (y por el mismo patrón el consumidor/scheduler/retención) **moría** ante una caída
de conexión al broker y no se reconectaba → el outbox dejaba de drenarse permanentemente. Brecha de
resiliencia del *plumbing* (RNF-10/RNF-30) que los tests con Testcontainers no exponen (no simulan
cortes de conexión).

**Corrección:** se extrajo `run_resilient(serve, should_continue, sleep, backoff)` en
`app/messaging/runner.py` — bucle que reejecuta cada `serve` reconectando con **backoff exponencial
(1→2→…→30 s)** ante errores recuperables (`pika.AMQPError`, `SQLAlchemyError`, `OSError`), y
propaga los no recuperables. Los 4 hilos de fondo se envuelven con él. 5 tests de regresión nuevos
(`tests/test_runner.py`).

**Blast radius:** local al servicio (plumbing de mensajería); no cambia contratos. Re-probar solo
este servicio.

**Re-verificación en vivo (código corregido):**
1. Al reiniciar el servicio, el relay **drenó los 4 eventos atascados** (outbox pendientes: 4→0;
   `config_events_published_total{config.backup_failed}=4`).
2. **Reinicio de RabbitMQ** (el mismo reset que antes mataba el hilo) → se generó un 5.º evento →
   se **publicó** (outbox pendientes=0, total=5, métrica=5) y **`Exception in thread` = 0**: el hilo
   sobrevivió y reconectó. ✅

## Cómo reproducir

```bash
docker compose -f docker-compose.dev.yml up -d --build postgres rabbitmq keycloak config-backup
# Token (admin/operador/auditor, contraseña "password"):
curl -s -X POST http://localhost:8080/realms/redsegura/protocol/openid-connect/token \
  -d grant_type=password -d client_id=redsegura-postman -d username=admin -d password=password | jq -r .access_token
# Endpoints en http://localhost:8082/api/v1 (ver colección Postman en ../postman/).
```
