# Casos de prueba — módulo asset-inventory-service

> Criterio de aceptación del módulo, definido **antes** de codificar. El servicio **no está
> "done"** mientras haya casos aplicables sin `✅ PASS`. Contrato de referencia:
> [`../openapi.yaml`](../openapi.yaml). Reglas: propuesta de módulo §5 (RN1..RN11).

**Módulo:** asset-inventory-service · **Ronda:** R1 · **Fecha:** 2026-07-12 · **Versión de código:** (pre-código)

**Estados:** `✅ PASS` · `❌ FAIL` · `⏳ PENDIENTE` · `⚠️ ABIERTO` · `N/A`.
**Roles:** ADM=Administrador · OPE=Operador · AUD=Auditor.

> **Aplicabilidad de categorías (servicio de API, sin UI):** `UI`, `VIS` → **N/A** (viven en el
> repo frontend). Se refuerzan `SEC`, `RBAC`, `CRUD`, `VAL`, `BSRCH`, `FLOW`, `RN`, `ERR`, `CYBER`,
> `EMPTY`. Los casos cubren también las implementaciones de industria: RFC 7807, probes,
> Idempotency-Key, ETag/If-Match, identidad `serialNumber`, `deviceType`, ubicación DCIM, redacción
> de `mgmtIp` y bulk import.

---

## Health / probes

| ID | Unidad | Cat. | Descripción | Rol(es) | Resultado esperado | Estado |
|---|---|---|---|---|---|---|
| HLTH-01 | `GET /health/liveness` | FLOW | Proceso vivo | público | 200 `{status: UP}` sin autenticación | ✅ |
| HLTH-02 | `GET /health/readiness` | FLOW | Dependencias OK (PostgreSQL) | público | 200 cuando la BD responde | ✅ |
| HLTH-03 | `GET /health/readiness` | ERR | BD caída | público | 503 `{status: DOWN}` — no enruta tráfico | ✅ |
| OBS-01 | `/actuator/prometheus` | FLOW | Registro Prometheus cableado (RNF-15) | interno | `scrape()` produce métricas (`jvm_memory_used_bytes`) | ✅ |
| OBS-02 | (logs) | FLOW | Logs estructurados JSON con `traceId` (RNF-16/17) | interno | Línea JSON con `service` + `traceId`, sin datos sensibles | ✅ |

## Registrar dispositivo — `POST /devices`

| ID | Unidad | Cat. | Descripción | Rol(es) | Resultado esperado | Estado |
|---|---|---|---|---|---|---|
| CRUD-01 | POST /devices | CRUD | Alta válida y consulta posterior | ADM | 201 + `Location` + `ETag`; aparece en GET | ⏳ |
| SEC-01 | POST /devices | SEC | Alta con rol sin permiso | OPE, AUD | **403** (problem+json, `ACCESS_DENIED`), validado en el servicio | ✅ |
| SEC-02 | POST /devices | SEC | Alta sin token / token inválido | — | **401** (problem+json, `UNAUTHENTICATED`) | ✅ |
| VAL-01 | POST /devices | VAL | Falta `serialNumber` | ADM | 422, sin crear | ⏳ |
| VAL-02 | POST /devices | VAL | Falta `hostname`/`mgmtIp`/`deviceType`/`criticality` | ADM | 422 por cada requerido | ⏳ |
| VAL-03 | POST /devices | VAL | `criticality` fuera de enum | ADM | 422 | ⏳ |
| VAL-04 | POST /devices | VAL | `deviceType` fuera de enum | ADM | 422 | ⏳ |
| VAL-05 | POST /devices | VAL | `mgmtIp` con formato inválido (RN5) | ADM | 422 | ⏳ |
| VAL-06 | POST /devices | VAL | `rackUnit` fuera de rango (1–60) | ADM | 422 | ⏳ |
| RN-01 | POST /devices | RN | `serialNumber` duplicado (RN1) | ADM | **409** `DEVICE_ALREADY_EXISTS` | ⏳ |
| RN-02 | POST /devices | RN | `hostname` o `mgmtIp` duplicado (RN1) | ADM | **409** | ⏳ |
| RN-03 | POST /devices | RN | Alta confirmada escribe `asset.created` en outbox (RN11) | ADM | Fila outbox con sobre correcto en la misma transacción | ✅ |
| RN-03b | (relay) | RN | El relay publica el evento pendiente al broker (RN11) | — | `asset.created` llega a cola `asset.*`; fila marcada publicada | ✅ |
| RN-03c | PATCH/DELETE | RN | Edición/baja escriben `asset.updated` (con `changedFields`) / `asset.decommissioned` | ADM | Evento correcto en outbox | ✅ |
| RN-04 | POST /devices | RN | Idempotencia: mismo `Idempotency-Key` **y mismo cuerpo** (RN9) | ADM | 2ª respuesta = 1ª; **no** crea duplicado | ✅ |
| RN-04b | POST /devices | RN | Idempotencia: mismo `Idempotency-Key` con **cuerpo distinto** (RN9) | ADM | **409** `IDEMPOTENCY_KEY_CONFLICT`; no crea duplicado | ✅ |

## Listar / filtrar — `GET /devices`

| ID | Unidad | Cat. | Descripción | Rol(es) | Resultado esperado | Estado |
|---|---|---|---|---|---|---|
| CRUD-02 | GET /devices | CRUD | Listado paginado | ADM, OPE, AUD | 200 sobre de paginación estándar | ✅ |
| BSRCH-01 | GET /devices | BSRCH | Filtro por `hostname` parcial (insensible a mayúsculas) | ADM | Coincidencias parciales | ✅ |
| BSRCH-02 | GET /devices | BSRCH | Filtro insensible a **acentos** | ADM | `galon` encuentra `Galón` | ⏳ (pendiente `unaccent`) |
| BSRCH-03 | GET /devices | BSRCH | Filtros `deviceType`, `site`, `rack`, `criticidad`, `estado` | ADM | Resultados correctos (AND) | ⏳ |
| BSRCH-05 | GET /devices | BSRCH | Filtro por `fabricante`/`modelo` (parcial, insensible) | ADM, OPE, AUD | Solo el fabricante buscado | ✅ |
| BSRCH-04 | GET /devices | EMPTY | Búsqueda sin coincidencias | ADM | 200 lista vacía (distinto de "sin datos") | ⏳ |
| EMPTY-01 | GET /devices | EMPTY | Inventario sin dispositivos | ADM | 200 lista vacía inicial | ⏳ |
| FLOW-01 | GET /devices | FLOW | Excluye dados de baja por defecto (RN6) | ADM | No aparecen `BAJA` salvo `estado=BAJA` | ✅ |
| SORT-01 | GET /devices | VAL | `sort` con campo no permitido | AUD | **400** `INVALID_REQUEST` problem+json (no 500) | ✅ |
| RBAC-01 | GET /devices | RBAC | Redacción de `mgmtIp` (RN10/ADR-11) | AUD | `mgmtIp` **enmascarada** (`10.0.0.***`) | ✅ |
| RBAC-02 | GET /devices | RBAC | `mgmtIp` en claro | ADM, OPE | `mgmtIp` completa | ✅ |
| VAL-07 | GET /devices | VAL | `size` > máximo (100) | ADM | Acotado a 100 (cap implementado; test HTTP pendiente) | ⏳ |

## Consultar — `GET /devices/{id}`

| ID | Unidad | Cat. | Descripción | Rol(es) | Resultado esperado | Estado |
|---|---|---|---|---|---|---|
| CRUD-03 | GET /devices/{id} | CRUD | Detalle existente | ADM, OPE, AUD | 200 + cabecera `ETag` | ⏳ |
| ERR-01 | GET /devices/{id} | ERR | Id inexistente | ADM | **404** problem+json | ⏳ |
| RBAC-03 | GET /devices/{id} | RBAC | `mgmtIp` enmascarada para Auditor | AUD | `10.0.0.***` | ✅ |

## Editar — `PUT` / `PATCH /devices/{id}`

| ID | Unidad | Cat. | Descripción | Rol(es) | Resultado esperado | Estado |
|---|---|---|---|---|---|---|
| CRUD-04 | PUT /devices/{id} | CRUD | Edición completa con `If-Match` válido | ADM | 200 + nuevo `ETag`; cambios persistidos | ⏳ |
| CRUD-05 | PATCH /devices/{id} | CRUD | Edición parcial (JSON Merge Patch) | ADM | 200; solo cambian campos enviados | ⏳ |
| SEC-03 | PATCH /devices/{id} | SEC | Editar con rol sin permiso | OPE, AUD | **403** | ⏳ |
| RN-05 | PUT /devices/{id} | RN | Intento de cambiar `serialNumber` (RN2, inmutable) | ADM | Ignorado o **422** | ⏳ |
| RN-06 | PUT /devices/{id} | RN | Editar dispositivo en `BAJA` (RN6) | ADM | **409** `DEVICE_DECOMMISSIONED` | ⏳ |
| RN-07 | PATCH /devices/{id} | RN | Intento de modificar `status` directo (RN7) | ADM | Rechazado (campo protegido) | ⏳ |
| FLOW-02 | PUT /devices/{id} | FLOW | `If-Match` con ETag obsoleto (edición concurrente, RN8) | ADM | **412** Precondition Failed | ⏳ |
| FLOW-03 | PUT /devices/{id} | FLOW | Falta cabecera `If-Match` (RN8) | ADM | **428** Precondition Required | ⏳ |
| RN-08 | PATCH /devices/{id} | RN | Edición confirmada publica `asset.updated` (RN11) | ADM | Evento con `changedFields` | ⏳ |

## Dar de baja — `DELETE /devices/{id}`

| ID | Unidad | Cat. | Descripción | Rol(es) | Resultado esperado | Estado |
|---|---|---|---|---|---|---|
| CRUD-06 | DELETE /devices/{id} | CRUD | Baja lógica con `If-Match` | ADM | 204; `status`→BAJA (no borra) | ⏳ |
| SEC-04 | DELETE /devices/{id} | SEC | Baja con rol sin permiso | OPE, AUD | **403** | ⏳ |
| FLOW-04 | DELETE /devices/{id} | FLOW | Baja sin/`If-Match` obsoleto | ADM | 428 / 412 | ⏳ |
| RN-09 | DELETE /devices/{id} | RN | Baja publica `asset.decommissioned` (RN11) | ADM | Evento en broker | ⏳ |

## Importación masiva — `POST /devices/bulk`

| ID | Unidad | Cat. | Descripción | Rol(es) | Resultado esperado | Estado |
|---|---|---|---|---|---|---|
| CRUD-07 | POST /devices/bulk | CRUD | Importación válida | ADM | **202** + `jobId` (asíncrono); job termina COMPLETED | ✅ |
| SEC-05 | POST /devices/bulk | SEC | Importación con rol sin permiso | OPE, AUD | **403** | ✅ |
| FLOW-05 | GET /devices/bulk/jobs/{jobId} | FLOW | Seguimiento del job | ADM, OPE, AUD | Estado QUEUED→…→COMPLETED (polling) | ✅ |
| FLOW-05b | GET /devices/bulk/jobs/{jobId} | ERR | Job inexistente | ADM | **404** `JOB_NOT_FOUND` | ✅ |
| RN-10 | POST /devices/bulk | RN | Lote con algunos inválidos (serial dup) | ADM | Job con resultado **por dispositivo** (succeeded/failed) | ✅ |
| RN-10b | POST /devices/bulk | RN | Misma `Idempotency-Key` + mismo cuerpo | ADM | Mismo `jobId` (replay); cuerpo distinto → 409 | ✅ |
| VAL-08 | POST /devices/bulk | VAL | Lote vacío (`minItems`) | ADM | **422** | ✅ |
| VAL-08b | POST /devices/bulk | VAL | Lote > 1000 (`maxItems`) | ADM | 422 (cubierto por Bean Validation del contrato) | ⏳ |

## Transversal — errores, seguridad, ciberseguridad

| ID | Unidad | Cat. | Descripción | Rol(es) | Resultado esperado | Estado |
|---|---|---|---|---|---|---|
| ERR-02 | (todas) | ERR | Formato de error uniforme (ADR-08) | — | `application/problem+json` con `type,title,status,detail,instance,traceId` | ⏳ |
| ERR-03 | POST /devices | ERR | Cuerpo JSON malformado | ADM | 400 sin filtrar internos | ⏳ |
| CYBER-01 | GET /devices | CYBER | Inyección en filtros (SQL/`unaccent`) | ADM | Consulta parametrizada; sin ejecución maliciosa | ⏳ |
| CYBER-02 | GET /devices/{id} | CYBER | `mgmtIp` para Auditor **ausente en la respuesta**, no solo oculta | AUD | Valor real **no** viaja; solo el enmascarado (ADR-11) | ✅ |
| CYBER-03 | (errores) | CYBER | Ningún 4xx/5xx expone stack trace / nombres de tablas (RNF-09) | — | Mensaje genérico + `traceId` | ⏳ |
| CYBER-04 | (escritura) | CYBER | Autorización validada server-side, no asumida del Gateway | AUD | 403 aunque el Gateway no filtrara | ⏳ |
| SEC-06 | (denegaciones) | SEC | Acceso denegado y auth fallida se registran en log de seguridad (ADR-11) | AUD | Entrada `security` con actor/hora (`event=access_denied`) | ✅ |

---

## Patrones que han causado bugs reales (revisar siempre)

- Dato sensible (`mgmtIp`) "enmascarado" pero con el valor real presente en la respuesta/DOM → verificar ausencia real (CYBER-02).
- Campo de solo lectura (`status`, `serialNumber`) "bloqueado" pero editable de verdad → RN-05/RN-07.
- Chequeo de unicidad solo previo (sin índice único) → carrera crea duplicado → RN-01/RN-02.
- Evento publicado aunque la transacción falló (sin outbox) → RN-03/RN-08/RN-09.

## Resumen de la ronda

- Total casos: **43** · ✅ PASS: 0 · ⏳ PENDIENTE: 43 · N/A: `UI`, `VIS`.
- Cobertura de categorías: SEC, RBAC, CRUD, VAL, BSRCH, EMPTY, FLOW, RN, ERR, CYBER — completa
  (UI/VIS pertenecen al frontend).
