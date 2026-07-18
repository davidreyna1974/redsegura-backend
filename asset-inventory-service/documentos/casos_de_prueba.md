# Casos de prueba — módulo asset-inventory-service

> Criterio de aceptación del módulo, definido **antes** de codificar. El servicio **no está
> "done"** mientras haya casos aplicables sin `✅ PASS`. Contrato de referencia:
> [`../openapi.yaml`](../openapi.yaml). Reglas: propuesta de módulo §5 (RN1..RN11).

**Módulo:** asset-inventory-service · **Ronda:** R1 (2026-07-14) + **R1.1** (2026-07-15) + **R1.2**
(2026-07-15) + **R1.3** (2026-07-17, endurecimiento) **✅ CERTIFICADA** · **Versión de código:**
`develop`; **109 tests** automatizados en verde (98 R1.1 + 2 regresión PUT + 9 de endurecimiento
RNF-08/27/29/30, incl. **caminos negativos** JWT issuer/audience y confirm-failure del outbox).
Reporte consolidado:
[`../../../management/documentos/qa/reporte_qa.md`](../../../management/documentos/qa/reporte_qa.md) ·
verificación en vivo: [`verificacion_endpoints.md`](verificacion_endpoints.md).

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
| CRUD-01 | POST /devices | CRUD | Alta válida y consulta posterior | ADM | 201 + `Location` + `ETag`; aparece en GET | ✅ |
| SEC-01 | POST /devices | SEC | Alta con rol sin permiso | OPE, AUD | **403** (problem+json, `ACCESS_DENIED`), validado en el servicio | ✅ |
| SEC-02 | POST /devices | SEC | Alta sin token / token inválido | — | **401** (problem+json, `UNAUTHENTICATED`) | ✅ |
| VAL-01 | POST /devices | VAL | Falta `serialNumber` | ADM | 422, sin crear | ✅ |
| VAL-02 | POST /devices | VAL | Falta `hostname`/`mgmtIp`/`deviceType`/`criticality` | ADM | 422 por cada requerido | ✅ |
| VAL-03 | POST /devices | VAL | `criticality` fuera de enum | ADM | **400** (JSON no deserializable; sin fuga) | ✅ |
| VAL-04 | POST /devices | VAL | `deviceType` fuera de enum | ADM | **400** (test 1:1 con `deviceType`) | ✅ |
| VAL-05 | POST /devices | VAL | `mgmtIp` con formato inválido (RN5) | ADM | 422 | ✅ |
| VAL-06 | POST /devices | VAL | `rackUnit` fuera de rango (1–60) | ADM | 422 | ✅ |
| RN-01 | POST /devices | RN | `serialNumber` duplicado (RN1) | ADM | **409** `DEVICE_ALREADY_EXISTS` | ✅ |
| RN-02 | POST /devices | RN | `hostname` duplicado (RN1) — test 1:1 | ADM | **409** `DEVICE_ALREADY_EXISTS` | ✅ |
| RN-03 | POST /devices | RN | Alta confirmada escribe `asset.created` en outbox (RN11) | ADM | Fila outbox con sobre correcto en la misma transacción | ✅ |
| RN-03b | (relay) | RN | El relay publica el evento pendiente al broker (RN11) | — | `asset.created` llega a cola `asset.*`; fila marcada publicada | ✅ |
| RN-03c | PATCH/DELETE | RN | Edición/baja escriben `asset.updated` (con `changedFields`) / `asset.decommissioned` | ADM | Evento correcto en outbox | ✅ |
| RN-04 | POST /devices | RN | Idempotencia: mismo `Idempotency-Key` **y mismo cuerpo** (RN9) | ADM | 2ª respuesta = 1ª; **no** crea duplicado | ✅ |
| RN-04b | POST /devices | RN | Idempotencia: mismo `Idempotency-Key` con **cuerpo distinto** (RN9) | ADM | **409** `IDEMPOTENCY_KEY_CONFLICT`; no crea duplicado | ✅ |

## Direccionamiento dual-stack — RF-05a

> Los casos que antes usaban `mgmtIp` (VAL-05, RN-02, RBAC-01/02/03, CYBER-02) ya se expresan contra
> `managementIpv4`/`managementIpv6`.

| ID | Unidad | Cat. | Descripción | Rol(es) | Resultado esperado | Estado |
|---|---|---|---|---|---|---|
| IP-01 | POST /devices | RN | Alta solo con `managementIpv4` | ADM | 201; dispositivo con IPv4, sin IPv6 | ✅ |
| IP-02 | POST /devices | RN | Alta solo con `managementIpv6` (no canónica) | ADM | 201; IPv6 **canonicalizada** (RFC 5952) | ✅ |
| IP-03 | POST /devices | RN | Alta con **ambas** (IPv4 + IPv6) | ADM | 201; ambas presentes | ✅ |
| IP-04 | POST /devices | VAL | **Sin** ninguna dirección de gestión | ADM | **422** `ADDRESS_INVALID` (al menos una, RF-05a) | ✅ |
| IP-05 | POST /devices | VAL | `address` con formato/familia inválidos | ADM | **422** `ADDRESS_INVALID` | ✅ |
| IP-06 | POST /devices | RN | Unicidad IPv6 con **formas distintas** de la misma dirección | ADM | **409** (canonicalización → misma IP) | ✅ |
| IP-07 | GET /devices/{id} | RBAC | Redacción IPv6 para Auditor (host bajo el prefijo) | AUD | `2001:db8:acad:1::***`; valor real no viaja | ✅ |
| IP-08 | GET /devices | BSRCH | Filtro `mgmtIp` por dirección IPv4 **o** IPv6 | ADM | Coincide en la familia correcta | ✅ |
| IP-09 | (evento) | RN | `asset.*` transporta `managementIpv4`/`managementIpv6` en claro | — | Payload dual-stack correcto (`version` 1.1.0) | ✅ |

## Listar / filtrar — `GET /devices`

| ID | Unidad | Cat. | Descripción | Rol(es) | Resultado esperado | Estado |
|---|---|---|---|---|---|---|
| CRUD-02 | GET /devices | CRUD | Listado paginado | ADM, OPE, AUD | 200 sobre de paginación estándar | ✅ |
| BSRCH-01 | GET /devices | BSRCH | Filtro por `hostname` parcial (insensible a mayúsculas) | ADM | Coincidencias parciales | ✅ |
| BSRCH-02 | GET /devices | BSRCH | Filtro insensible a **acentos** | ADM | `galon` encuentra `Galón` (`unaccent`, Flyway V6) | ✅ |
| BSRCH-03 | GET /devices | BSRCH | Filtros `deviceType`, `site`, `rack`, `criticidad`, `estado` | ADM | Resultados correctos (AND) | ✅ |
| BSRCH-05 | GET /devices | BSRCH | Filtro por `fabricante`/`modelo` (parcial, insensible) | ADM, OPE, AUD | Solo el fabricante buscado | ✅ |
| BSRCH-04 | GET /devices | EMPTY | Búsqueda sin coincidencias | ADM | 200 lista vacía (distinto de "sin datos") | ✅ |
| EMPTY-01 | GET /devices | EMPTY | Inventario sin dispositivos | ADM | 200 lista vacía inicial | ✅ |
| FLOW-01 | GET /devices | FLOW | Excluye dados de baja por defecto (RN6) | ADM | No aparecen `BAJA` salvo `estado=BAJA` | ✅ |
| SORT-01 | GET /devices | VAL | `sort` con campo no permitido | AUD | **400** `INVALID_REQUEST` problem+json (no 500) | ✅ |
| RBAC-01 | GET /devices | RBAC | Redacción de `mgmtIp` (RN10/ADR-11) | AUD | `mgmtIp` **enmascarada** (`10.0.0.***`) | ✅ |
| RBAC-02 | GET /devices | RBAC | `mgmtIp` en claro | ADM, OPE | `mgmtIp` completa | ✅ |
| VAL-07 | GET /devices | VAL | `size` > máximo del contrato (100) | ADM | **422** `VALIDATION_ERROR` (rechazado por `@Max`) | ✅ |

## Consultar — `GET /devices/{id}`

| ID | Unidad | Cat. | Descripción | Rol(es) | Resultado esperado | Estado |
|---|---|---|---|---|---|---|
| CRUD-03 | GET /devices/{id} | CRUD | Detalle existente | ADM, OPE, AUD | 200 + cabecera `ETag` | ✅ |
| ERR-01 | GET /devices/{id} | ERR | Id inexistente | ADM | **404** problem+json | ✅ |
| RBAC-03 | GET /devices/{id} | RBAC | `mgmtIp` enmascarada para Auditor | AUD | `10.0.0.***` | ✅ |

## Editar — `PUT` / `PATCH /devices/{id}`

| ID | Unidad | Cat. | Descripción | Rol(es) | Resultado esperado | Estado |
|---|---|---|---|---|---|---|
| CRUD-04 | PUT /devices/{id} | CRUD | Edición completa con `If-Match` válido | ADM | 200 + nuevo `ETag`; cambios persistidos | ✅ |
| CRUD-04b | PUT /devices/{id} | CRUD | **Reemplazo completo (RFC 9110):** PUT con solo IPv6 → campos omitidos (`managementIpv4`, `vendor`, `model`) quedan en `null` | ADM | 200; IPv4 y demás omitidos → `null`; IPv6 fijado | ✅ |
| CRUD-04c | PUT /devices/{id} | RN | PUT que dejaría al dispositivo **sin ninguna dirección** de gestión (RF-05a) | ADM | **422** `ADDRESS_INVALID` | ✅ |
| CRUD-05 | PATCH /devices/{id} | CRUD | Edición parcial (JSON Merge Patch) | ADM | 200; solo cambian campos enviados | ✅ |
| SEC-03 | PATCH /devices/{id} | SEC | Editar con rol sin permiso | OPE, AUD | **403** | ✅ |
| RN-05 | PUT /devices/{id} | RN | Intento de cambiar `serialNumber` (RN2, inmutable) | ADM | Imposible por construcción: el DTO de edición no expone `serialNumber` | N/A |
| RN-06 | PUT /devices/{id} | RN | Editar dispositivo en `BAJA` (RN6) | ADM | **409** `DEVICE_DECOMMISSIONED` | ✅ |
| RN-07 | PATCH /devices/{id} | RN | Intento de modificar `status` directo (RN7) | ADM | Imposible por construcción: `status` no está en el DTO de edición (solo cambia por baja) | N/A |
| FLOW-02 | PUT /devices/{id} | FLOW | `If-Match` con ETag obsoleto (edición concurrente, RN8) | ADM | **412** Precondition Failed | ✅ |
| FLOW-03 | PUT /devices/{id} | FLOW | Falta cabecera `If-Match` (RN8) | ADM | **428** Precondition Required | ✅ |
| RN-08 | PATCH /devices/{id} | RN | Edición confirmada publica `asset.updated` (RN11) | ADM | Evento con `changedFields` | ✅ |

## Dar de baja — `DELETE /devices/{id}`

| ID | Unidad | Cat. | Descripción | Rol(es) | Resultado esperado | Estado |
|---|---|---|---|---|---|---|
| CRUD-06 | DELETE /devices/{id} | CRUD | Baja lógica con `If-Match` | ADM | 204; `status`→BAJA (no borra) | ✅ |
| SEC-04 | DELETE /devices/{id} | SEC | Baja con rol sin permiso | OPE, AUD | **403** | ✅ |
| FLOW-04 | DELETE /devices/{id} | FLOW | Baja sin/`If-Match` obsoleto | ADM | 428 / 412 | ✅ |
| RN-09 | DELETE /devices/{id} | RN | Baja publica `asset.decommissioned` (RN11) | ADM | Evento en broker | ✅ |

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
| VAL-08b | POST /devices/bulk | VAL | Lote > 1000 (`maxItems`) | ADM | 422 (cubierto por Bean Validation del contrato) | ✅ |

## Transversal — errores, seguridad, ciberseguridad

| ID | Unidad | Cat. | Descripción | Rol(es) | Resultado esperado | Estado |
|---|---|---|---|---|---|---|
| ERR-02 | (todas) | ERR | Formato de error uniforme (ADR-08) | — | `application/problem+json` con `type,title,status,detail,instance,traceId` | ✅ |
| ERR-03 | POST /devices | ERR | Cuerpo JSON malformado | ADM | 400 sin filtrar internos | ✅ |
| CYBER-01 | GET /devices | CYBER | Inyección en filtros (SQL/`unaccent`) | ADM | Consulta parametrizada; sin ejecución maliciosa | ✅ |
| CYBER-02 | GET /devices/{id} | CYBER | `mgmtIp` para Auditor **ausente en la respuesta**, no solo oculta | AUD | Valor real **no** viaja; solo el enmascarado (ADR-11) | ✅ |
| CYBER-03 | (errores) | CYBER | Ningún 4xx/5xx expone stack trace / nombres de tablas (RNF-09) | — | Mensaje genérico + `traceId` | ✅ |
| CYBER-04 | (escritura) | CYBER | Autorización validada server-side, no asumida del Gateway | AUD | 403 aunque el Gateway no filtrara | ✅ |
| SEC-06 | (denegaciones) | SEC | Acceso denegado y auth fallida se registran en log de seguridad (ADR-11) | AUD | Entrada `security` con actor/hora (`event=access_denied`) | ✅ |

## Conformidad de contrato de eventos (productor-side, "mini Pact")

Validación del payload `asset.*` contra su JSON Schema (`contracts/asset-event.schema.json`).

| ID | Cat. | Descripción | Resultado esperado | Estado |
|---|---|---|---|---|
| EVT-01 | happy | create/update/decommission emiten evento válido | 0 errores de schema; `version` 1.1.0; `changedFields` en update | ✅ |
| EVT-02 | edge | solo-IPv6 y dual-stack | validan; familias opcionales presentes/ausentes según el caso | ✅ |
| EVT-03 | sad | (1) operación fallida no emite evento; (2) payload malformado | (1) 0 eventos nuevos; (2) el schema **rechaza** (tiene dientes) | ✅ |

## Endurecimiento a producción (RNF-08/27/29/30)

Cierre de los RNF de etapa DEV (ver [`matriz_rnf.md`](matriz_rnf.md)).

| ID | Cat. | Descripción | Resultado esperado | Estado |
|---|---|---|---|---|
| SEC-05 | SEC | Token con `aud`/`azp` de la audiencia esperada | aceptado (`AudienceValidatorTest`) | ✅ |
| SEC-06 | SEC | Token dirigido a otra audiencia/servicio | rechazado (`invalid_token` → 401) | ✅ |
| SEC-07 | SEC | (config) `issuer`/`audience` externalizados; token de otro realm → 401 | validado por `JwtDecoder` (iss+aud+exp) | ✅ |
| SEC-08 | SEC | **(negativo)** validador de producción con token de **otro issuer** → rechazado; **otra audiencia** → rechazado; correcto → aceptado | `JwtIssuerAudienceValidationTest` (composición iss+aud cableada) | ✅ |
| EVT-04 | RN30 | Relay marca publicado **solo tras ACK** del broker (publisher confirms) | evento entregado y `publishedAt` set (`OutboxRelayIT`) | ✅ |
| EVT-05 | RN30 | Lote tomado con `FOR UPDATE SKIP LOCKED` (seguro multi-réplica) | query nativa; lote correcto | ✅ |
| EVT-06 | RN30 | **(negativo)** el broker **no confirma** (nack/timeout) → el evento **no** se marca publicado (queda pendiente, se reintenta) | `OutboxRelayConfirmFailureTest` | ✅ |
| APIDOC-01 | DOC | `/openapi.yaml` servido en runtime sin token | 200 + contrato (`ApiDocsIT`) | ✅ |
| APIDOC-02 | DOC | `/v3/api-docs` (springdoc) disponible sin token | 200 (`ApiDocsIT`) | ✅ |

---

## Patrones que han causado bugs reales (revisar siempre)

- Dato sensible (`mgmtIp`) "enmascarado" pero con el valor real presente en la respuesta/DOM → verificar ausencia real (CYBER-02).
- Campo de solo lectura (`status`, `serialNumber`) "bloqueado" pero editable de verdad → RN-05/RN-07.
- Chequeo de unicidad solo previo (sin índice único) → carrera crea duplicado → RN-01/RN-02.
- Evento publicado aunque la transacción falló (sin outbox) → RN-03/RN-08/RN-09.

## Resumen de la ronda

- **Ronda R1 certificada (2026-07-14):** Total **73** casos · ✅ PASS: **71** · N/A: **2** (RN-05/RN-07,
  imposibles por construcción) · ⏳ diferido: **0**. BSRCH-02 (acentos) cerrado con `unaccent` (V6)
  tras la certificación. Verificado con `mvn verify` (**109 tests** automatizados —98 R1.1 + 2 de
  regresión PUT + 9 de endurecimiento RNF-08/27/29/30 incl. caminos negativos—, cobertura ≥70%,
  0 lint). Categorías `UI/VIS` son del repo `frontend`.
- **Verificación en vivo de endpoints (2026-07-15):** los 10 endpoints por HTTP real
  (curl/Postman) contra el entorno Docker Compose → 10/10 ✅. Detectó `HALLAZGO-LIVE-01` (PUT no
  cumplía reemplazo completo RFC 9110), corregido + regresión. Detalle:
  [`verificacion_endpoints.md`](verificacion_endpoints.md).
- Cobertura de categorías: SEC, RBAC, CRUD, VAL, BSRCH, EMPTY, FLOW, RN, ERR, CYBER — completa
  (UI/VIS pertenecen al frontend).
