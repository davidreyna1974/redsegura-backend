# Propuesta de módulo — config-backup-service

> Planificación **previa al código** (contract-first). Fuente de verdad del contrato:
> [`../openapi.yaml`](../openapi.yaml). RF/RNF: `proyecto_microservicios_redsegura.md §7.2/§8`.
> Eventos: `arquitectura/especificaciones/comunicacion_por_eventos.md`.

**Servicio:** config-backup-service · **Fase:** A · **Stack:** Python 3.12 / FastAPI ·
**Fecha:** 2026-07-17 · **Estado:** propuesto (pre-código)

---

## 1. Objetivo del módulo

Respaldar y **versionar** la configuración de los dispositivos de red del inventario (RF-06..10):
conectarse por **SSH (Netmiko/NAPALM)**, capturar `running-config` y `startup-config`, versionar el
contenido en un **repositorio Git interno**, guardar metadatos en PostgreSQL, y **detectar drift**
(cambios respecto al último respaldo) notificándolo por evento. Es el **primer consumidor** de los
eventos `asset.*` (mantiene su propia vista de dispositivos) → habilita el **Pact real**.

## 2. Alcance

**Incluye:** respaldo bajo demanda (individual y por lotes), historial y detalle de respaldos, diff
entre versiones, drift-check (individual/lote), programación recurrente (cron), jobs asíncronos,
cálculo de `unsavedChanges` (running ≠ startup), consumo de `asset.*`, producción de `config.*`.

**No incluye (fuera de alcance):** UI (repo `frontend`); auditoría de cumplimiento
(`compliance-audit-service`); envío de notificaciones (`notification-service`, que consume
`config.drift_detected`); conexión a **dispositivos reales** (RNF-07: solo red simulada GNS3/Packet
Tracer, acotada por configuración de CIDRs permitidos).

## 3. Endpoints (11, del contrato)

| Método + ruta | Operación | Roles | Notas |
|---|---|---|---|
| `GET /health/liveness` · `/readiness` | probes | público | readiness verifica BD/broker/Git |
| `POST /devices/{id}/backups` | backup individual | ADM/OPE | 201; SSH; `unsavedChanges`; `Idempotency-Key` |
| `POST /backups` | backup por lotes | ADM/OPE | 202 + `jobId` (asíncrono) |
| `GET /backups` | historial global | ADM/OPE/AUD | filtros: hostname/mgmtIp/deviceId/unsavedChanges/status/fechas; paginado |
| `GET /backups/jobs/{jobId}` | estado job backup | ADM/OPE/AUD | |
| `GET /backups/{backupId}` | detalle de respaldo | ADM/OPE/AUD | |
| `GET /devices/{id}/backups/diff` | diff entre 2 versiones | ADM/OPE/AUD | `from`/`to`/`configType` |
| `POST /devices/{id}/drift-check` | drift-check individual | ADM/OPE | 200; emite `config.drift_detected` si difiere |
| `POST /drift-checks` | drift-check por lotes | ADM/OPE | 202 + `jobId` |
| `GET /drift-checks/jobs/{jobId}` | estado job drift | ADM/OPE/AUD | |
| `POST /schedules` · `GET /schedules` | programaciones (cron) | ADM (crear) / todos (listar) | `Idempotency-Key` en crear |

## 4. Contratos con dependencias (verificados contra el catálogo)

### 4.1 Consume (entrante) — `asset.*` · cola `q.config-backup.asset-events` (binding `asset.*`)
- **`asset.created` / `asset.updated` / `asset.decommissioned`** (productor: `asset-inventory`,
  sobre `version` 1.1.0). Se usan para **mantener una vista local de dispositivos** (deviceId,
  hostname, `managementIpv4`/`managementIpv6` en claro, status) — necesaria para saber a qué
  dispositivos conectarse. **Consumidor idempotente** (dedupe por `eventId`, RNF-E1); reintentos con
  backoff → DLQ (RNF-E3).
- **Pact (INT-CONS):** este servicio es el **primer consumidor** → se verifica el consumidor contra
  el JSON Schema compartido `contracts/events/asset-event.schema.json`; el productor
  (`asset-inventory`) ya lo verifica (`AssetEventContractIT`). Cierra el par consumidor↔productor.

### 4.2 Produce (saliente) — `config.*` vía **transactional outbox** (ADR-04, RNF-E4)
| Evento | Cuándo | Payload núcleo |
|---|---|---|
| `config.backup_completed` | respaldo exitoso | `deviceId`, `backupId`, `commit`, `unsavedChanges` |
| `config.backup_failed` | respaldo fallido (RF-10) | `deviceId`, `failureReason` |
| `config.drift_detected` | drift-check detecta cambio (RF-09) | `deviceId`, `driftRef`, `checkedAt` |
| `config.unsaved_changes_detected` | running ≠ startup (ADR-02) | `deviceId`, `backupId` |

> **Consumidores de `config.*`:** `alerting-service` (`q.alerting.config-events`). Un cambio de este
> esquema es **blast radius global** → re-probar consumidores con Pact.

## 5. Reglas de negocio

- **RN-CB1 (RNF-07):** solo se conecta a IPs dentro de los **CIDRs autorizados** (config por entorno;
  en dev/test = red simulada). Un objetivo fuera de alcance se **rechaza** (control técnico, no solo política).
- **RN-CB2:** el respaldo captura `running-config` **y** `startup-config`; `unsavedChanges = (running ≠ startup)`.
- **RN-CB3:** cada respaldo exitoso es **un commit** en el repo Git interno (ruta por dispositivo);
  el `commit` se guarda como metadato. El diff usa el repo Git.
- **RN-CB4:** el resultado (SUCCESS/FAILED + `failureReason`) se registra **siempre** (RF-10).
- **RN-CB5:** drift = `running-config` en vivo ≠ contenido del último respaldo conocido.
- **RN-CB6:** un dispositivo dado de baja (`asset.decommissioned`) no se respalda (se marca en la vista local).
- **RN-CB7 (idempotencia, ADR-09):** `Idempotency-Key` en creaciones (backup/lote/schedule), acotada por usuario + hash de cuerpo.
- **RN-CB8:** los lotes se ejecutan **asíncronos** (job + estado); el objetivo se indica con exactamente uno de `scope`/`deviceIds`/`filter`.

## 6. Seguridad / RBAC del módulo

- Escritura de respaldos/drift (POST) → **ADM, OPE**; crear schedule → **ADM**; lectura → **ADM, OPE, AUD**.
- **JWT validado en el servicio** (firma + issuer + audience + expiración, RNF-29); RBAC por endpoint
  (no confiar en el Gateway).
- **Secretos de dispositivos (credenciales SSH):** nunca en código/BD en claro; vía gestor de secretos
  / variables de entorno (RNF-06). En logs/respuestas **jamás** aparecen (RNF-17).
- Errores en `application/problem+json` sin filtrar internos (RNF-09).

## 7. Riesgos y decisiones de diseño

| Riesgo/decisión | Resolución |
|---|---|
| Conexión a dispositivos reales por error | Control técnico de CIDRs (RN-CB1); en tests, **driver SSH mockeado** (nunca red real) |
| Credenciales SSH sensibles | Externalizadas (RNF-06); redactadas en logs (RNF-17) |
| Repo Git interno concurrente | Serializar escrituras por dispositivo (lock/branch por device) o repo por dispositivo |
| Entrega de `config.*` | Transactional outbox + publisher confirms + relay `SKIP LOCKED` (RNF-30, patrón asset-inventory) |
| Vista de dispositivos desincronizada | Consumidor idempotente de `asset.*`; reconciliación diferible |
| **Decisiones de stack** | Pydantic v2 (validación), SQLAlchemy 2.0 + **Alembic** (migraciones), **Netmiko** (SSH) tras una interfaz `DeviceConnector` (mockeable), **GitPython** (repo interno), broker vía `aio-pika`/`pika`, JWT con `PyJWT`+JWKS. Se confirman en la memoria técnica al implementar. |

## 8. Revisión contra estándares de industria

**a) Estándares globales heredados (confirmar, ya son ADR + gobernados por Spectral en CI):**
```
[x] Errores RFC 7807 · [x] probes liveness/readiness · [x] Idempotency-Key en creaciones
[x] paginación y filtrado estándar · [x] RBAC por operación · [x] logging estructurado
[x] JWT en profundidad (iss/aud) · [x] entrega de eventos con outbox
```
**b) Estándares específicos del DOMINIO (automatización de red):**

| Estándar/patrón | ¿Aplica? | Cómo se incorpora |
|---|---|---|
| Netmiko/NAPALM (multi-vendor SSH) | sí | Interfaz `DeviceConnector`; Netmiko por defecto; mock en tests |
| running vs startup config (Cisco/IOS) | sí | `unsavedChanges`; ambos se capturan |
| Config-as-code / GitOps (versionado en Git) | sí | Repo Git interno; diff por commits (RF-07) |
| Detección de drift (config compliance) | sí | Comparación running-vivo vs último respaldo (RF-09) |
| Programación tipo cron | sí | Expresiones cron; APScheduler |

**c) Hallazgos transversales a promover:** ninguno nuevo previsto (reusa ADR-04/08/09/11/14/15).

## 8b. Revisión de aplicabilidad de RNF (production readiness)

Recorrido de los RNF (§8) para este servicio; detalle con evidencia en `matriz_rnf.md`, diferidos en
`arquitectura/preparacion_produccion.md`.

| Bloque de RNF | Aplica en DEV | N/A (justificado) | Diferido (disparador) |
|---|---|---|---|
| Seguridad | RNF-03/04/06/09/29 | RNF-07 → **sí aplica aquí** (alcance de conexión SSH) | secretos-gestor (DEPLOY); TLS (DEPLOY) |
| Cadena de suministro | RNF-08 (pip-audit + Trivy imagen) | | |
| Resiliencia/eventos | RNF-12, RNF-30 (produce), **RNF-10 (SÍ: Netmiko hace llamadas SSH salientes → Resilience: timeout/retry)** | | RNF-21 Pact → **INT-CONS ahora** |
| Observabilidad · Calidad/CI/docs · API | RNF-15/16/17, RNF-18/19/20/27/28 | | dashboards+SLO (DEPLOY) |
| Escalabilidad/despliegue · Rendimiento | RNF-14 (BD propia) | RNF-24 (infra) · 25/26 (frontend) | RNF-13/22/23 (DEPLOY); RNF-01/RNF-02 (**RNF-02 respaldo < 30 s** → PRE-REL con red simulada) |

> **Diferencias clave vs asset-inventory:** **RNF-07 aplica** (control de alcance SSH) y **RNF-10
> aplica desde DEV** (Netmiko hace llamadas salientes reales a dispositivos → timeouts/reintentos son
> obligatorios, no diferibles a INT-SYNC). **RNF-21 (Pact) se activa ahora** (INT-CONS, primer consumidor).

- [ ] **Matriz de RNF creada** (`matriz_rnf.md`).
- [ ] Diferidos registrados en `preparacion_produccion.md`.

## 9. Checklist de apertura (antes de codificar)

```
[x] Propuesta creada (este documento).
[ ] casos_de_prueba.md creado desde el TEMPLATE (SEC, RBAC, CRUD, VAL, FLOW, RN, ERR, CYBER + dominio).
[ ] memoria_tecnica.md iniciada.
[x] Contrato propio verificado (openapi.yaml; validar con redocly/Spectral).
[x] Contratos de eventos verificados: consume asset.* (§4.1) + produce config.* (§4.2) contra el catálogo.
[ ] matriz_rnf.md creada (revisión de RNF §8b materializada).
[x] Gate de seguridad previsto para todos los endpoints (escritura ADM/OPE; schedule solo ADM; probar AUD→403).
[x] RNF-07: control técnico de alcance de conexión SSH previsto (CIDRs autorizados).
```
