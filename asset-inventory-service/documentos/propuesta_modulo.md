# Propuesta de módulo — asset-inventory-service

**Estado:** propuesta · **Fecha:** 2026-07-12 · **Fase:** A · **Lenguaje:** Java 21 / Spring Boot

> Planificación previa a codificar. Contrato de referencia: [`../openapi.yaml`](../openapi.yaml).
> Contexto: memoria técnica global y estándares de desarrollo (repo `management`).

## 1. Objetivo del módulo

Mantener el **inventario único de activos de red** — la **fuente de verdad** del sistema
(RF-01..05). Permite registrar, consultar, editar y dar de baja dispositivos, exponerlos por API
REST para que los demás microservicios los consulten, y publicar eventos de dominio ante cada
cambio. Sirve al **Administrador** (gestión completa) y a **Operador/Auditor** (solo consulta).

## 2. Alcance

- **Incluye:**
  - CRUD de dispositivos: registrar (RF-01), consultar/editar/dar de baja con *soft delete* (RF-02).
  - **Identidad estable** por `serialNumber` (inmutable) + `assetTag` opcional; `deviceType`
    (ROUTER/SWITCH/FIREWALL/HOST/ACCESS_POINT/OTHER); **ubicación estructurada DCIM**
    (`site/room/row/rack/rackUnit`).
  - Listado con **búsqueda y filtrado** por hostname, IP, serial, tipo, site, rack, criticidad,
    fabricante, modelo y estado, paginado (RF-04).
  - **Importación masiva** de dispositivos (bulk, asíncrona → job).
  - Exposición del inventario como API REST — fuente única de verdad (RF-03).
  - Publicación de eventos `asset.created` / `asset.updated` / `asset.decommissioned` (RF-05).
  - Estándares de industria: errores **RFC 7807** (ADR-08), **Idempotency-Key** + **ETag/If-Match**
    (ADR-09), **probes** liveness/readiness (ADR-10), **redacción de `mgmtIp` por rol** + **log de
    auditoría de seguridad** (ADR-11), columnas de auditoría (ADR-07), RBAC por endpoint.
- **No incluye:** respaldos, auditoría de cumplimiento, escaneos, telemetría (otros servicios);
  gestión de usuarios/roles (la provee Keycloak); credenciales de acceso a los dispositivos
  (viven en `config-backup-service` / gestor de secretos, nunca aquí).
- **Dependencias:**
  - **PostgreSQL** (base de datos propia — *database-per-service*).
  - **RabbitMQ** (publicar eventos; con *transactional outbox*, ADR-04).
  - **Keycloak** (validación de JWT y roles).
  - POM padre del monorepo (Spring Boot, calidad, MapStruct).
  - **No consume** la API de ningún otro microservicio: es la fuente de verdad; los demás lo
    consumen a él.

## 3. Endpoints

| Unidad | Método / Ruta | Descripción | Rol(es) |
|---|---|---|---|
| Registrar dispositivo | `POST /api/v1/devices` | Alta (acepta `Idempotency-Key`) | ADM |
| Listar / filtrar | `GET /api/v1/devices` | Búsqueda paginada con filtros | ADM, OPE, AUD |
| Consultar detalle | `GET /api/v1/devices/{deviceId}` | Detalle (devuelve `ETag`) | ADM, OPE, AUD |
| Editar (completo) | `PUT /api/v1/devices/{deviceId}` | Reemplazo (exige `If-Match`) | ADM |
| Editar (parcial) | `PATCH /api/v1/devices/{deviceId}` | JSON Merge Patch (exige `If-Match`) | ADM |
| Dar de baja | `DELETE /api/v1/devices/{deviceId}` | *Soft delete* (exige `If-Match`) | ADM |
| Importación masiva | `POST /api/v1/devices/bulk` | Alta por lotes, asíncrona → `jobId` | ADM |
| Estado de importación | `GET /api/v1/devices/bulk/jobs/{jobId}` | Avance del job de importación | ADM, OPE, AUD |
| Liveness | `GET /health/liveness` | ¿El proceso vive? | público |
| Readiness | `GET /health/readiness` | ¿Listo para tráfico? (BD/broker) | público |

## 4. Contratos con dependencias (verificados)

Este servicio **no consume** endpoints de otros servicios. Sus contratos salientes son:

**a) API propia** — definida en [`../openapi.yaml`](../openapi.yaml) (validada con redocly, 0 errores).

**b) Eventos que publica** — verificados contra
`management/documentos/arquitectura/especificaciones/comunicacion_por_eventos.md §4.1`:

| Evento (routing key) | Cuándo | Payload (campos exactos) |
|---|---|---|
| `asset.created` | Alta confirmada | `deviceId, hostname, mgmtIp, vendor, model, location, criticality, status` |
| `asset.updated` | Edición confirmada | idem + `changedFields: [..]` |
| `asset.decommissioned` | Baja lógica | `deviceId, hostname, status: "BAJA"` |

**c) Identidad (Keycloak):** JWT *Bearer*; claim de rol con valores `ADM` / `OPE` / `AUD`.

**DTOs relevantes** (nombres exactos del contrato):
```
Location             { site, room, row, rack, rackUnit }
Device               { id, serialNumber, assetTag, hostname, mgmtIp, deviceType, vendor, model,
                       location, criticality, status, createdAt, createdBy, updatedAt, updatedBy }
DeviceCreateRequest  { serialNumber*, assetTag, hostname*, mgmtIp*, deviceType*, vendor, model,
                       location, criticality* }                                    (* requerido)
DeviceUpdateFull     { hostname*, mgmtIp*, deviceType*, criticality*, assetTag, vendor, model, location }
DeviceUpdateRequest  { …campos opcionales… }  (JSON Merge Patch; serialNumber inmutable)
BulkImportRequest    { devices: [DeviceCreateRequest] }   →   Job { jobId, status, total, succeeded, failed, results[] }
PageDevice           { content[], page, size, totalElements, totalPages, first, last }
Problem              { type, title, status, detail, instance, traceId }   (RFC 7807)
Enums: Criticality {ALTA,MEDIA,BAJA} · DeviceStatus {ACTIVO,BAJA} · DeviceType {ROUTER,SWITCH,FIREWALL,HOST,ACCESS_POINT,OTHER}
```

## 5. Reglas de negocio

- **RN1 — Identidad y unicidad:** `serialNumber` es la **identidad estable** (inmutable) y es
  **único**; `hostname` y `mgmtIp` también son únicos entre los activos, pero son *atributos*
  (mutables). Duplicar cualquiera → **409** `DEVICE_ALREADY_EXISTS`.
- **RN2 — `serialNumber` inmutable:** no editable vía `PUT`/`PATCH` → intento de cambiarlo se
  ignora o **422**.
- **RN3 — Campos requeridos:** `serialNumber`, `hostname`, `mgmtIp`, `deviceType`, `criticality`
  obligatorios en alta → **400/422**.
- **RN4 — Enumeraciones válidas:** `criticality ∈ {ALTA,MEDIA,BAJA}`, `deviceType` en su enum →
  si no, **422**.
- **RN5 — IP de gestión válida:** `mgmtIp` con formato IPv4/IPv6 válido → si no, **422**.
- **RN6 — Soft delete:** dar de baja cambia `status` ACTIVO→BAJA (no borra). Un dispositivo en
  BAJA **no** es editable → **409** `DEVICE_DECOMMISSIONED`. Los listados excluyen los de baja
  salvo filtro `estado=BAJA`.
- **RN7 — `status` protegido:** solo lectura vía API; cambia solo por alta/baja (estándares §11).
- **RN8 — Concurrencia (ETag/If-Match, ADR-09):** `PUT`/`PATCH`/`DELETE` exigen `If-Match`; si no
  coincide con el ETag actual → **412** (edición concurrente); si falta la cabecera → **428**.
- **RN9 — Idempotencia (ADR-09):** un `POST` con `Idempotency-Key` ya visto devuelve la respuesta
  original, sin crear duplicado.
- **RN10 — Redacción de `mgmtIp` (ADR-11):** ADM y OPE ven la IP en claro; **Auditor** la ve
  **enmascarada** (`10.0.0.***`), aplicado en el servidor; el acceso queda en el log de seguridad.
- **RN11 — Publicación de evento:** tras **confirmar** el cambio en BD, se publica el evento vía
  *transactional outbox* (ADR-04); si la transacción no confirma, no hay evento.

## 6. Seguridad / RBAC del módulo

- **Escritura** (`POST`, `PUT`, `PATCH`, `DELETE /devices`, `POST /devices/bulk`) → **solo ADM**.
- **Lectura** (`GET /devices`, `GET /devices/{id}`, `GET …/bulk/jobs/{jobId}`) → **ADM, OPE, AUD**.
- **`/health/liveness` y `/health/readiness`** → públicos (sin autenticación).
- **Gate de seguridad (obligatorio):** probar el acceso a los endpoints de escritura con el rol
  **menos** privilegiado (Auditor) → **403**; validado **en el servicio**, no asumido del Gateway.
- **Redacción de campos por rol (server-side, ADR-11):**

  | Campo | ADM | OPE | AUD |
  |---|:--:|:--:|:--:|
  | `mgmtIp` | claro | claro | **enmascarado** (`10.0.0.***`) |

- **Log de auditoría de seguridad (ADR-11):** registrar accesos denegados (403), autenticaciones
  fallidas (401) y mutaciones con su actor; y el acceso de Auditor al inventario (por `mgmtIp`).
- **Campos sensibles:** este servicio **no** almacena credenciales de dispositivos ni secretos.
  Nunca se expone la entidad JPA (solo DTOs).

## 7. Riesgos y decisiones de diseño

- **Contract-first (ADR-05):** las interfaces de API y los DTOs se **generan** desde `openapi.yaml`
  con `openapi-generator`; el controlador implementa la interfaz generada.
- **Mapeo entidad↔DTO (ADR-06):** **MapStruct**; nunca exponer la entidad JPA.
- **Auditoría (ADR-07):** `Device` es entidad **mutable** → cuarteto `created_at/by`,
  `updated_at/by` con JPA Auditing (`AuditorAware` desde el JWT).
- **Unicidad (RN1):** índice único en BD sobre `hostname` y `mgmtIp`; capturar
  `DataIntegrityViolationException` → **409** (no confiar solo en un chequeo previo, por carreras).
- **Soft delete (RN5):** columna `status`; filtro por defecto que excluye `BAJA` en los listados.
- **Concurrencia:** **bloqueo optimista** (`@Version`) en `Device`, **expuesto por HTTP** con
  `ETag`/`If-Match` (RFC 7232, ADR-09) → 412 en conflicto.
- **Errores RFC 7807 (ADR-08):** `application/problem+json`; `PATCH` = JSON Merge Patch (RFC 7386).
- **Idempotencia (ADR-09):** `Idempotency-Key` en `POST` (dedupe de reintentos) — tabla de claves.
- **Probes (ADR-10):** `readiness` verifica PostgreSQL y RabbitMQ; `liveness` solo el proceso.
- **Redacción + auditoría de seguridad (ADR-11):** `mgmtIp` enmascarada para Auditor; log `security`.
- **Identidad estable:** `serialNumber` inmutable como identidad de negocio; índice único en BD.
- **Ubicación estructurada (DCIM):** objeto embebido `{site,room,row,rack,rackUnit}` (sin recursos
  Site/Rack aparte por ahora).
- **Bulk import:** asíncrono (202 + `jobId`), con resultado por dispositivo.
- **Consistencia evento↔BD:** *transactional outbox* (ADR-04).
- **Blast radius GLOBAL:** al ser la fuente de verdad, cambiar su contrato de API o el esquema de
  sus eventos obliga a re-probar los consumidores (config-backup, compliance-audit) con Pact.
- **Persistencia:** migraciones versionadas con **Flyway** (estándares §11).

## 8. Revisión contra estándares de industria

**a) Cumplimiento de estándares globales** (heredados; gobernados por Spectral/gatekeeper):
```
[x] Errores RFC 7807 · [x] probes liveness/readiness · [x] Idempotency-Key en creaciones
[x] ETag/If-Match en mutaciones · [x] columnas de auditoría · [x] redacción de campos por rol
[x] paginación y filtrado estándar · [x] RBAC por operación · [x] logging estructurado
```

**b) Estándares específicos del dominio (CMDB · DCIM · IPAM):**

| Estándar/patrón del dominio | ¿Aplica? | Cómo se incorpora / decisión |
|---|---|---|
| Identidad estable (serial/asset tag) — ITIL CMDB | **sí** | `serialNumber` inmutable + `assetTag` (RN1/RN2) |
| Tipo/rol de dispositivo (CIM) | **sí** | `deviceType` (ROUTER/SWITCH/FIREWALL/HOST/AP/OTHER) |
| Ubicación estructurada — DCIM (NetBox) | **sí** | `Location {site,room,row,rack,rackUnit}` embebida |
| Sites/Racks como recursos con CRUD | no (por ahora) | embebido; recursos aparte → backlog |
| Ciclo de vida rico (staging→active→…→disposed) | no (por ahora) | `status` ACTIVO/BAJA; resto → backlog |
| Reconciliación / auto-discovery (SNMP/LLDP) | no (futuro) | backlog; `telemetry-collector` podría alimentarlo |
| IPAM (espacio IP/VLAN) | no (futuro) | backlog |

**c) Hallazgos transversales promovidos:** esta revisión originó **ADR-08** (RFC 7807), **ADR-09**
(Idempotency-Key + ETag/If-Match), **ADR-10** (probes), **ADR-11** (redacción por rol + log de
seguridad) y **ADR-12** (gobernanza con Spectral) — ahora globales y aplicables a los 10 servicios.

## 9. Checklist de apertura (antes de codificar)

```
[x] Propuesta creada (este documento).
[ ] casos_de_prueba.md creado desde el TEMPLATE (categorías: SEC, RBAC, CRUD, VAL, FLOW, RN, ERR, CYBER).
[ ] memoria_tecnica.md del módulo iniciada.
[x] Contrato propio verificado (openapi.yaml, redocly 0 errores).
[x] Contratos salientes (eventos) verificados contra comunicacion_por_eventos.md §4.1.
[x] Revisión contra estándares de industria (Sección 8) completada.
[x] Gate de seguridad previsto para todos los endpoints (escritura solo ADM; probar Auditor→403).
```
