# Casos de prueba — módulo config-backup-service

> Criterio de aceptación, definido **antes** de codificar. El servicio **no está "done"** mientras
> haya casos aplicables sin `✅ PASS`. Contrato: [`../openapi.yaml`](../openapi.yaml). Reglas:
> [`propuesta_modulo.md`](propuesta_modulo.md) §5 (RN-CB1..8).

**Estado:** pre-código (todos `⏳ PENDIENTE` hasta implementar). **Estados:** `✅ PASS` · `❌ FAIL` ·
`⏳ PENDIENTE` · `N/A`. **Roles:** ADM · OPE · AUD.

> **Aplicabilidad (servicio de API Python, sin UI):** `UI`/`VIS` → **N/A** (frontend). Se refuerzan
> `SEC, RBAC, CRUD, VAL, FLOW, RN, ERR, CYBER` + **dominio** (SSH/Git/drift/scope) + **EVT** (eventos).

---

## Health / probes
| ID | Unidad | Cat. | Descripción | Rol | Esperado | Estado |
|---|---|---|---|---|---|---|
| HLTH-01 | GET /health/liveness | FLOW | Proceso vivo | público | 200 `{status:UP}` sin auth | ⏳ |
| HLTH-02 | GET /health/readiness | FLOW | BD + broker + Git OK | público | 200 si dependencias sanas | ⏳ |
| HLTH-03 | GET /health/readiness | ERR | Dependencia caída | público | 503 `{status:DOWN}` | ⏳ |

## Respaldo bajo demanda — `POST /devices/{id}/backups`
| ID | Unidad | Cat. | Descripción | Rol | Esperado | Estado |
|---|---|---|---|---|---|---|
| CRUD-01 | POST backups | CRUD | Respaldo exitoso (SSH mock) | ADM/OPE | 201 + `Backup` (commit, capturedAt, unsavedChanges) | ⏳ |
| SEC-01 | POST backups | SEC | Respaldo con rol sin permiso | AUD | **403** problem+json | ⏳ |
| SEC-02 | POST backups | SEC | Sin token / token inválido | — | **401** | ⏳ |
| ERR-01 | POST backups | ERR | Dispositivo inexistente en la vista local | ADM | **404** | ⏳ |
| RN-CB2 | (servicio) | RN | running ≠ startup → `unsavedChanges=true` + evento | — | `unsavedChanges:true`; emite `config.unsaved_changes_detected` (`test_backup_service`) | ✅ |
| RN-CB3 | (servicio) | RN | Respaldo exitoso crea **1 commit** en Git interno | — | Metadato `commit`; versionado (`test_backup_service`/`test_git_store`) | ✅ |
| RN-CB4 | (servicio) | RN | Fallo de conexión SSH (RF-10) | — | `status:FAILED` + `failureReason`; emite `config.backup_failed` | ✅ |
| RN-CB7 | POST backups | RN | Idempotencia: misma clave + mismo cuerpo | ADM | 2.ª = 1.ª, sin duplicar | ⏳ |
| CYBER-01 | POST backups | CYBER | Credenciales SSH **no** aparecen en respuesta/logs | ADM | Sin secretos filtrados (RNF-17) | ⏳ |
| **RNF07-01** | (servicio) | SEC/CYBER | Objetivo **fuera de los CIDRs autorizados** (RNF-07) | — | rechazado; **no** intenta conectar (`test_backup_service`) | ✅ |

## Respaldo por lotes — `POST /backups` (asíncrono)
| ID | Unidad | Cat. | Descripción | Rol | Esperado | Estado |
|---|---|---|---|---|---|---|
| FLOW-01 | POST /backups | FLOW | Lote encolado | ADM/OPE | 202 + `jobId` (QUEUED) | ⏳ |
| FLOW-02 | GET jobs/{id} | FLOW | Seguimiento hasta COMPLETED | ADM/OPE/AUD | estado con `completed`/`failed` por dispositivo | ⏳ |
| VAL-01 | POST /backups | VAL | `BatchTargetRequest` con 0 o >1 de scope/deviceIds/filter | ADM | **400/422** (exactamente uno, RN-CB8) | ⏳ |

## Historial y detalle — `GET /backups`, `GET /backups/{id}`
| ID | Unidad | Cat. | Descripción | Rol | Esperado | Estado |
|---|---|---|---|---|---|---|
| CRUD-02 | GET /backups | CRUD | Historial paginado | ADM/OPE/AUD | 200 página estándar | ⏳ |
| BSRCH-01 | GET /backups | BSRCH | Filtros hostname/mgmtIp/deviceId/unsavedChanges/status/fechas | ADM | Resultados correctos (AND) | ⏳ |
| EMPTY-01 | GET /backups | EMPTY | Sin respaldos / sin coincidencias | ADM | 200 lista vacía | ⏳ |
| CRUD-03 | GET /backups/{id} | CRUD | Detalle existente | ADM/OPE/AUD | 200 `Backup` | ⏳ |
| ERR-02 | GET /backups/{id} | ERR | Id inexistente | ADM | **404** | ⏳ |

## Diff — `GET /devices/{id}/backups/diff`
| ID | Unidad | Cat. | Descripción | Rol | Esperado | Estado |
|---|---|---|---|---|---|---|
| CRUD-04 | GET diff | CRUD | Diff entre 2 versiones (running) | ADM/OPE/AUD | 200 `DiffResult.diff` (unificado) | ⏳ |
| VAL-02 | GET diff | VAL | `from`/`to` ausentes o inexistentes | ADM | **400/404** | ⏳ |
| RN-CB3b | GET diff | RN | `configType=startup` | ADM | diff de startup-config | ⏳ |

## Drift-check — `POST /devices/{id}/drift-check`, `POST /drift-checks`
| ID | Unidad | Cat. | Descripción | Rol | Esperado | Estado |
|---|---|---|---|---|---|---|
| RN-CB5 | POST drift-check | RN | running en vivo ≠ último respaldo (RF-09) | ADM/OPE | 200 `drift:true`; emite `config.drift_detected` | ⏳ |
| DRIFT-02 | POST drift-check | FLOW | Sin cambios | ADM | 200 `drift:false`; **sin** evento | ⏳ |
| SEC-03 | POST drift-check | SEC | Rol sin permiso | AUD | **403** | ⏳ |
| FLOW-03 | POST /drift-checks | FLOW | Drift-check por lotes | ADM/OPE | 202 + `jobId` | ⏳ |

## Programaciones — `POST/GET /schedules`
| ID | Unidad | Cat. | Descripción | Rol | Esperado | Estado |
|---|---|---|---|---|---|---|
| CRUD-05 | POST /schedules | CRUD | Crear schedule (cron válido) | ADM | 201 `Schedule` | ⏳ |
| SEC-04 | POST /schedules | SEC | Crear con OPE/AUD | OPE, AUD | **403** (solo ADM) | ⏳ |
| VAL-03 | POST /schedules | VAL | `cron` inválido / `type` fuera de enum | ADM | **400/422** | ⏳ |
| FLOW-04 | (scheduler) | FLOW | El schedule dispara el backup/drift a su hora | — | Ejecución registrada; `createdBy:scheduler` | ⏳ |
| CRUD-06 | GET /schedules | CRUD | Listar con filtros | ADM/OPE/AUD | 200 página | ⏳ |

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
| EVT-OUT-03 | EVT | `config.drift_detected` tras drift | evento con `driftRef` | ⏳ |
| EVT-OUT-04 | EVT | Operación fallida **no** emite evento de éxito | solo `config.backup_failed` (RNF-E4) | ✅ |

## Resiliencia SSH (RNF-10 — aplica desde DEV)
| ID | Cat. | Descripción | Esperado | Estado |
|---|---|---|---|---|
| RES-01 | RN/ERR | Timeout de conexión SSH | falla acotada (timeout) + `FAILED`, no cuelga | ⏳ |
| RES-02 | RN | Reintento con backoff ante fallo transitorio | reintenta y luego SUCCESS (`test_scope_resilience`) | ✅ |

## Categorías N/A
`UI`, `VIS` → repo `frontend`.

---
> **Resumen:** casos definidos pre-código. Se irán a `✅ PASS` por unidad durante la implementación
> (Propuesta B). El servicio se certifica con el Protocolo de 4 fases + verificación en vivo + matriz
> de RNF verde + **Pact en verde** (par con `asset-inventory`).
