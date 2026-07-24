# Casos de prueba — módulo config-backup-service

> Criterio de aceptación, definido **antes** de codificar. El servicio **no está "done"** mientras
> haya casos aplicables sin `✅ PASS`. Contrato: [`../openapi.yaml`](../openapi.yaml). Reglas:
> [`propuesta_modulo.md`](propuesta_modulo.md) §5 (RN-CB1..8).

**Estado:** ✅ **CERTIFICADO** (Protocolo de 4 fases). **R-C1** (1ª vuelta): detectó/corrigió 2
hallazgos de Fase 1 (`HALLAZGO-QA-CBS-01` filtros de `GET /backups`, `HALLAZGO-QA-CBS-02` idempotencia
RN-CB7). **R-C2** (2ª vuelta, 2026-07-24, commit `ceb48a8`): re-certificación tras los gates de
prevención + aceptación BDD → **0 hallazgos**. Ver
[`reporte_certificacion_qa.md`](reporte_certificacion_qa.md). **63 ✅ · 0 ⏳ · 0 ❌** · 99 tests ·
cobertura 95 %. **Estados:** `✅ PASS` · `❌ FAIL` · `⏳ PENDIENTE` · `N/A`. **Roles:** ADM · OPE · AUD.

> **Aplicabilidad (servicio de API Python, sin UI):** `UI`/`VIS` → **N/A** (frontend). Se refuerzan
> `SEC, RBAC, CRUD, VAL, FLOW, RN, ERR, CYBER` + **dominio** (SSH/Git/drift/scope) + **EVT** (eventos).

---

## Health / probes
| ID | Unidad | Cat. | Descripción | Rol | Esperado | Estado |
|---|---|---|---|---|---|---|
| HLTH-01 | GET /health/liveness | FLOW | Proceso vivo | público | 200 `{status:UP}` sin auth | ✅ |
| HLTH-02 | GET /health/readiness | FLOW | BD + broker + Git OK | público | 200 si dependencias sanas | ✅ |
| HLTH-03 | GET /health/readiness | ERR | Dependencia caída | público | 503 `{status:DOWN}` | ✅ |

## Respaldo bajo demanda — `POST /devices/{id}/backups`
| ID | Unidad | Cat. | Descripción | Rol | Esperado | Estado |
|---|---|---|---|---|---|---|
| CRUD-01 | POST backups | CRUD | Respaldo exitoso (conector doble) | ADM/OPE | 201 + `Backup` (commit, unsavedChanges) (`test_backups_api`) | ✅ |
| SEC-01 | POST backups | SEC | Respaldo con rol sin permiso | AUD | **403** problem+json (`test_backups_api`) | ✅ |
| SEC-02 | POST backups | SEC | Sin token / token inválido | — | **401** (`test_backups_api`) | ✅ |
| ERR-01 | POST backups | ERR | Dispositivo inexistente en la vista local | ADM | **404** (`test_backups_api`) | ✅ |
| RN-CB2 | (servicio) | RN | running ≠ startup → `unsavedChanges=true` + evento | — | `unsavedChanges:true`; emite `config.unsaved_changes_detected` (`test_backup_service`) | ✅ |
| RN-CB3 | (servicio) | RN | Respaldo exitoso crea **1 commit** en Git interno | — | Metadato `commit`; versionado (`test_backup_service`/`test_git_store`) | ✅ |
| RN-CB4 | (servicio) | RN | Fallo de conexión SSH (RF-10) | — | `status:FAILED` + `failureReason`; emite `config.backup_failed` | ✅ |
| RN-CB7 | POST backups/schedules | RN | Idempotencia: misma clave + mismo cuerpo | ADM | 2.ª = 1.ª, sin duplicar; distinto cuerpo → 409; clave acotada por actor; fallo libera la clave (`test_idempotency`) | ✅ |
| CYBER-01 | logs | CYBER | Credenciales SSH **no** aparecen en logs | — | Contraseña SSH redactada a `***` por el formateador JSON (RNF-17) (`test_observability`) | ✅ |
| **RNF07-01** | (servicio) | SEC/CYBER | Objetivo **fuera de los CIDRs autorizados** (RNF-07) | — | rechazado; **no** intenta conectar (`test_backup_service`) | ✅ |

## Respaldo por lotes — `POST /backups` (asíncrono)
| ID | Unidad | Cat. | Descripción | Rol | Esperado | Estado |
|---|---|---|---|---|---|---|
| FLOW-01 | POST /backups | FLOW | Lote encolado | ADM/OPE | 202 + `jobId` (QUEUED) (`test_jobs`) | ✅ |
| FLOW-02 | GET jobs/{id} | FLOW | Seguimiento hasta COMPLETED | ADM/OPE/AUD | estado con `completed`/`failed` por dispositivo; mixto ok/fuera-de-alcance/inexistente (`test_jobs`) | ✅ |
| SEC-05 | POST /backups | SEC | Lote con rol de solo lectura | AUD | **403** (`test_jobs`) | ✅ |
| VAL-01 | POST /backups | VAL | `BatchTargetRequest` con 0 o >1 de scope/deviceIds/filter | ADM | **422** (exactamente uno, RN-CB8) (`test_jobs`) | ✅ |
| ERR-03 | GET jobs/{id} | ERR | jobId inexistente **o** de otro tipo en la ruta | ADM/OPE/AUD | **404** (aislamiento de tipo) (`test_jobs`) | ✅ |

## Historial y detalle — `GET /backups`, `GET /backups/{id}`
| ID | Unidad | Cat. | Descripción | Rol | Esperado | Estado |
|---|---|---|---|---|---|---|
| CRUD-02 | GET /backups | CRUD | Historial paginado | ADM/OPE/AUD | 200 página estándar (`test_backups_api`) | ✅ |
| BSRCH-01 | GET /backups | BSRCH | Filtros hostname/mgmtIp/deviceId/unsavedChanges/status/fechas | ADM | Resultados correctos (AND) | ✅ |
| EMPTY-01 | GET /backups | EMPTY | Sin respaldos / sin coincidencias | ADM | 200 lista vacía | ✅ |
| CRUD-03 | GET /backups/{id} | CRUD | Detalle existente | ADM/OPE/AUD | 200 `Backup` | ✅ |
| ERR-02 | GET /backups/{id} | ERR | Id inexistente | ADM | **404** (`test_backups_api`) | ✅ |

## Diff — `GET /devices/{id}/backups/diff`
| ID | Unidad | Cat. | Descripción | Rol | Esperado | Estado |
|---|---|---|---|---|---|---|
| CRUD-04 | GET diff | CRUD | Diff entre 2 versiones (running) | ADM/OPE/AUD | 200 `DiffResult.diff` (unificado) | ✅ |
| VAL-02 | GET diff | VAL | `from`/`to` ausentes o inexistentes | ADM | **400/404** | ✅ |
| RN-CB3b | GET diff | RN | `configType=startup` | ADM | diff de startup-config | ✅ |

## Drift-check — `POST /devices/{id}/drift-check`, `POST /drift-checks`
| ID | Unidad | Cat. | Descripción | Rol | Esperado | Estado |
|---|---|---|---|---|---|---|
| RN-CB5 | POST drift-check | RN | running en vivo ≠ último respaldo (RF-09) | ADM/OPE | 200 `drift:true`; emite `config.drift_detected` (`test_drift`) | ✅ |
| DRIFT-02 | POST drift-check | FLOW | Sin cambios | ADM | 200 `drift:false`; **sin** evento (`test_drift`) | ✅ |
| SEC-03 | POST drift-check | SEC | Rol sin permiso | AUD | **403** (`test_drift`) | ✅ |
| FLOW-03 | POST /drift-checks | FLOW | Drift-check por lotes | ADM/OPE | 202 + `jobId`; seguimiento a COMPLETED (`test_jobs`) | ✅ |

## Programaciones — `POST/GET /schedules`
| ID | Unidad | Cat. | Descripción | Rol | Esperado | Estado |
|---|---|---|---|---|---|---|
| CRUD-05 | POST /schedules | CRUD | Crear schedule (cron válido) | ADM | 201 `Schedule` (`next_run_at` calculado) (`test_schedules`) | ✅ |
| SEC-04 | POST /schedules | SEC | Crear con OPE/AUD | OPE, AUD | **403** (solo ADM) (`test_schedules`) | ✅ |
| VAL-03 | POST /schedules | VAL | `cron` inválido / `type` fuera de enum | ADM | **400/422** (`test_schedules`) | ✅ |
| FLOW-04 | (scheduler) | FLOW | El schedule vencido dispara el backup/drift | — | Job COMPLETED; `createdBy:scheduler:<id>`; `next_run_at` avanza (`test_schedules`) | ✅ |
| RN-CB9 | due_schedules | RN | Solo dispara programaciones **activas y vencidas** (no futuras ni pausadas) | — | selección correcta (`test_schedules`) | ✅ |
| CRUD-06 | GET /schedules | CRUD | Listar con filtros | ADM/OPE/AUD | 200 página (`test_schedules`) | ✅ |

## Consumo de eventos `asset.*` (vista de dispositivos) + Pact
| ID | Cat. | Descripción | Esperado | Estado |
|---|---|---|---|---|
| EVT-IN-01 | EVT | `asset.created` → alta en la vista local | dispositivo disponible para respaldo (`test_asset_event_handler`) | ✅ |
| EVT-IN-02 | EVT | `asset.updated` → actualiza hostname/IP | vista consistente | ✅ |
| EVT-IN-03 | EVT | `asset.decommissioned` → marca BAJA (RN-CB6) | status BAJA en la vista | ✅ |
| EVT-IN-04 | EVT | Reproceso del mismo `eventId` (idempotencia, RNF-E1) | 2.ª = `duplicate`, no pisa un update posterior | ✅ |
| PACT-01 | EVT/contract | **Pact consumidor** de `asset.*` contra `asset-event.schema.json` (INT-CONS) | consumidor procesa el esquema del productor; schema con dientes (`test_asset_event_contract`) | ✅ |

## Producción de eventos `config.*` (outbox)
| ID | Cat. | Descripción | Esperado | Estado |
|---|---|---|---|---|
| EVT-OUT-01 | EVT | `config.backup_completed` tras éxito | evento en outbox (`test_backup_service`) | ✅ |
| EVT-OUT-02 | EVT | `config.backup_failed` tras fallo | evento con `failureReason` | ✅ |
| EVT-OUT-03 | EVT | `config.drift_detected` tras drift | evento con `driftRef` (`test_drift`) | ✅ |
| EVT-OUT-04 | EVT | Operación fallida **no** emite evento de éxito | solo `config.backup_failed` (RNF-E4) | ✅ |
| EVT-CONF-01 | EVT/contract | `config.backup_completed` + `unsaved_changes_detected` cumplen el JSON Schema (sobre §3.3 + payload §4.2/4.5) | conformes (`test_config_event_contract`) | ✅ |
| EVT-CONF-02 | EVT/contract | `config.backup_failed` cumple el esquema (payload §4.3: `reason`/`attemptedAt`) | conforme (`test_config_event_contract`) | ✅ |
| EVT-CONF-03 | EVT/contract | `config.drift_detected` cumple el esquema (payload §4.4: `baselineBackupId`/`detectedBy`/`detectedAt`) | conforme (`test_config_event_contract`) | ✅ |
| EVT-CONF-04 | EVT/contract | El **sobre común** se publica (no el payload desnudo) | envelope con `eventId/eventType/version/source` — broker real + en vivo (`test_broker`) | ✅ |
| EVT-CONF-05 | EVT/contract | Esquema con **dientes**: rechaza payload sin sobre / sin campos del catálogo / `source` erróneo | rechazado (`test_config_event_contract`) | ✅ |

## Resiliencia SSH (RNF-10 — aplica desde DEV)
| ID | Cat. | Descripción | Esperado | Estado |
|---|---|---|---|---|
| RES-01 | RN/ERR | Timeout de conexión SSH | falla acotada (timeout) + `FAILED`, no cuelga | ✅ |
| RES-02 | RN | Reintento con backoff ante fallo transitorio | reintenta y luego SUCCESS (`test_scope_resilience`) | ✅ |
| RUN-01 | RN | Hilo de fondo reconecta tras caída recuperable (backoff exponencial) | reejecuta; sleeps 1,2,… (`test_runner`) | ✅ |
| RUN-02 | RN | Backoff tope en `max_backoff` | no crece indefinido (`test_runner`) | ✅ |
| RUN-03 | ERR | Error **no** recuperable se propaga (no se traga bugs) | excepción propagada (`test_runner`) | ✅ |
| RUN-04/05 | RN | Parada limpia + set de errores recuperables (broker/BD/OS) | (`test_runner`) | ✅ |
| RNF30-LIVE | CYBER/RN | **Relay sobrevive a reinicio de RabbitMQ** y drena el outbox (HALLAZGO-LIVE-CBS-01) | verificado en vivo: outbox→0, `Exception in thread`=0 | ✅ |

## Aceptación BDD (ACPT — Gherkin, base de la UAT, patrón E)
| ID | Cat. | Descripción | Esperado | Estado |
|---|---|---|---|---|
| ACPT-01 | ACPT/FLOW | Respaldo exitoso dentro del alcance | 201 SUCCESS (`respaldo.feature`) | ✅ |
| ACPT-02 | ACPT/SEC | Respaldo fuera del alcance autorizado (RNF-07) | rechazado (422) (`respaldo.feature`) | ✅ |
| ACPT-03 | ACPT/RBAC | Auditor no puede respaldar | 403 (`respaldo.feature`) | ✅ |
| ACPT-04 | ACPT/RN | Detección de drift tras cambio en vivo (RF-09) | drift reportado (`respaldo.feature`) | ✅ |

## Conformidad de contrato↔implementación (CONF, L-QA-08)
| ID | Cat. | Descripción | Esperado | Estado |
|---|---|---|---|---|
| CONF-01 | CONF | Cada operación del `openapi.yaml` está implementada (path+método) | 0 operaciones declaradas sin implementar (`test_contract_conformance`) | ✅ |
| CONF-02 | CONF | Cada parámetro de query/cabecera declarado se honra (filtros de listado, `Idempotency-Key`) | 0 parámetros declarados sin implementar; gate con dientes (`test_contract_conformance`) | ✅ |

## Observabilidad y endurecimiento (RNF-13/15/16/17)
| ID | Cat. | Descripción | Esperado | Estado |
|---|---|---|---|---|
| OBS-01 | RNF | Endpoint `/metrics` abierto en formato Prometheus | 200; expone `http_requests_total` (`test_observability`) | ✅ |
| OBS-02 | RNF | Métrica de dominio incrementa | Tras respaldo, `config_backups_total{status="SUCCESS"}` (`test_observability`) | ✅ |
| OBS-03 | RNF/CYBER | Logs JSON estructurados + redacción de secreto | Campos timestamp/level/service/message; secreto → `***` (`test_observability`) | ✅ |
| HARD-01 | RNF | Imagen corre como **no-root** + liveness/HEALTHCHECK | Contenedor `appuser`; liveness 200 (verificación en vivo) | ✅ |
| RET-01 | RNF | Retención purga datos operativos, conserva respaldos | Purga jobs/eventos antiguos; `backups` intactos (`test_retention`) | ✅ |

## Categorías N/A
`UI`, `VIS` → repo `frontend`.

---
> **Resumen:** casos definidos pre-código. Se irán a `✅ PASS` por unidad durante la implementación
> (Propuesta B). El servicio se certifica con el Protocolo de 4 fases + verificación en vivo + matriz
> de RNF verde + **Pact en verde** (par con `asset-inventory`).
